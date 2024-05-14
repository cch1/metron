(ns com.hapgood.metron-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.datafy :refer [datafy]]
            [clojure.spec.test.alpha :as stest]
            [com.hapgood.metron :refer :all]
            [com.hapgood.metron.buffer :as buffer]
            [com.hapgood.metron.cloudwatch :as cw]))

(stest/instrument)

(deftest manage-dimensions-incrementally
  (with-dimension :A "1"
    (is (= {:A "1"} *dimensions*))
    (with-dimension :B "2"
      (is (= {:A "1" :B "2"} *dimensions*))
      (with-dimensions {:C "3" :D "4"}
        (is (= {:A "1" :B "2" :C "3" :D "4"} *dimensions*))))))

(deftest accumulate-metric
  (testing "explicit"
    (let [acc (atom (buffer/accumulator {}))]
      (with-redefs [com.hapgood.metron/now (constantly 0)]
        (with-dimension :X "1"
          (record* acc :abc/y 1 :None))
        (let [buffer @acc]
          (is (associative? (get-in buffer ["abc"])))
          (is (associative? (get-in buffer ["abc" "y"])))
          (is (associative? (get-in buffer ["abc" "y" 0])))
          (is (associative? (get-in buffer ["abc" "y" 0 {:X "1"}])))
          (is (instance? clojure.lang.IPersistentCollection (get-in buffer ["abc" "y" 0 {:X "1"} :None])))
          ;; Default accumulator is a list
          (is (= '(1) (get-in buffer ["abc" "y" 0 {:X "1"} :None])))))))
  (testing "dynamic"
    (with-accumulator (atom (buffer/accumulator {}))
      (with-redefs [com.hapgood.metron/now (constantly 0)]
        (with-dimension :X "1"
          (record :abc/y 1 :None))
        (let [buffer @*accumulator*]
          (is (associative? (get-in buffer ["abc"])))
          (is (associative? (get-in buffer ["abc" "y"])))
          (is (associative? (get-in buffer ["abc" "y" 0])))
          (is (associative? (get-in buffer ["abc" "y" 0 {:X "1"}])))
          (is (instance? clojure.lang.IPersistentCollection (get-in buffer ["abc" "y" 0 {:X "1"} :None])))
          ;; Default accumulator is a list
          (is (= '(1) (get-in buffer ["abc" "y" 0 {:X "1"} :None]))))))))

(deftest configure-metric-accumulator
  (let [acc (atom (buffer/accumulator {}))]
    (with-redefs [com.hapgood.metron/now (constantly 0)]
      (configure-metric acc :abc/y {:accumulator :statistic-set})
      (record* acc :abc/y 1 :None)
      (let [accumulator (-> acc deref (get-in ["abc" "y" 0 {} :None]) datafy)]
        (is (= {:max 1 :min 1 :sum 1 :count 1} accumulator))))))

(deftest can-flush!
  (let [acc (atom (buffer/accumulator {}))
        client :client
        store (atom [])]
    (with-redefs [com.hapgood.metron/now (constantly 1)
                  cw/put-metric-data (fn [client request] (swap! store conj request))]
      (configure-metric acc :abc/y {:accumulator :statistic-set})
      (record* acc :abc/y 1 :None)
      (is (= {"abc" 1} (flush! acc client)))
      (is (= [{:Namespace "abc"
               :MetricData [{:StatisticValues {:Maximum 1.0
                                               :Minimum 1.0
                                               :Sum 1.0
                                               :SampleCount 1.0}
                             :MetricName "y"
                             :Unit "None"
                             :Dimensions ()
                             :Timestamp 1
                             :StorageResolution 60}]}]
             @store))
      (is (empty? (get-in @acc ["abc" "y"]))))))
