(ns com.hapgood.metron-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.datafy :refer [datafy]]
            [clojure.spec.test.alpha :as stest]
            [com.hapgood.metron :refer :all]
            [com.hapgood.metron.buffer :as buffer]
            [com.hapgood.metron.cloudwatch :as cw]))

(stest/instrument 'com.hapgood.metron.cloudwatch/metric-datum)
(stest/instrument 'com.hapgood.metron.cloudwatch/put-metric-data-request)

(deftest manage-namespaces-incrementally
  (with-namespace "abc"
    (is (= "abc" *namespace*))
    (with-namespace "def"
      (is (= "abc.def" *namespace*))
      (from-root "xyz"
                 (is (= "xyz" *namespace*))))))

(deftest manage-dimensions-incrementally
  (with-dimension :A "1"
    (is (= {:A "1"} *dimensions*))
    (with-dimension :B "2"
      (is (= {:A "1" :B "2"} *dimensions*))
      (with-dimensions {:C "3" :D "4"}
        (is (= {:A "1" :B "2" :C "3" :D "4"} *dimensions*))))))

(deftest from-root-resets-namespace-and-dimension-scope
  (with-namespace "abd"
    (with-dimensions {:A "1" :B "2"}
      (from-root "xyz"
                 (is (= {} *dimensions*))))))

(deftest accumulate-metric
  (with-redefs [com.hapgood.metron/now (constantly 0)]
    (binding [*accumulator* (atom (buffer/accumulator {}))]
      (with-namespace "abc"
        (with-dimension :X "1"
          (record "y" 1 :None)))
      (let [buffer @*accumulator*]
        (is (associative? (get-in buffer ["abc"])))
        (is (associative? (get-in buffer ["abc" "y"])))
        (is (associative? (get-in buffer ["abc" "y" 0])))
        (is (associative? (get-in buffer ["abc" "y" 0 {:X "1"}])))
        (is (instance? clojure.lang.IPersistentCollection (get-in buffer ["abc" "y" 0 {:X "1"} :None])))
        ;; Default accumulator is a list
        (is (= '(1) (get-in buffer ["abc" "y" 0 {:X "1"} :None])))))))

(deftest accumulate-with-nym-variations
  (with-redefs [com.hapgood.metron/now (constantly 0)]
    (binding [*accumulator* (atom (buffer/accumulator {}))]
      (record :abc/x 1 :None)
      (record ["abc" "x"] 1 :None)
      (with-namespace "abc"
        (record "x" 1 :None)
        (record :x 1 :None)
        (record :abc/x 1 :None)
        (record ["abc" "x"] 1 :None))
      (let [buffer @*accumulator*]
        (is (= '(1 1 1 1 1 1) (get-in buffer ["abc" "x" 0 {} :None])))))))

(deftest configure-metric-accumulator
  (with-redefs [com.hapgood.metron/now (constantly 0)]
    (binding [*accumulator* (atom (buffer/accumulator {}))]
      (configure-metric ["abc" "y"] {:accumulator :statistic-set})
      (with-namespace "abc" (record "y" 1 :None))
      (let [accumulator (-> *accumulator* deref (get-in ["abc" "y" 0 {} :None]) datafy)]
        (is (= {:max 1 :min 1 :sum 1 :count 1} accumulator))))))

(deftest can-flush!
  (let [client :client
        store (atom [])]
    (with-redefs [com.hapgood.metron/now (constantly 1)
                  cw/put-metric-data (fn [client request] (swap! store conj request))]
      (binding [*accumulator* (atom (buffer/accumulator {}))]
        (configure-metric ["abc" "y"] {:accumulator :statistic-set})
        (with-namespace "abc" (record "y" 1 :None))
        (is (= {"abc" 1} (flush! client)))
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
        (is (empty? (get-in @*accumulator* ["abc" "y"])))))))
