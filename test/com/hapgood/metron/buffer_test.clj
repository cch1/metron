(ns com.hapgood.metron.buffer-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.hapgood.metron.buffer :as uat :refer :all]
            [com.hapgood.metron.coalescing-map :as cm]
            [com.hapgood.metron.branchable :as branchable])
  (:import (com.hapgood.metron.coalescing_map CoalescingMap)))

(deftest create-accumulator
  (is (associative? (accumulator {}))))

(deftest accumulate
  (let [t 1650843000000
        result (-> (accumulator {})
                   (accumulate-at [:ns1 :k1 t {:D1 "D1"} :Second] 3))]
    (is (map? (get-in result [:ns1])))
    (is (partial instance? CoalescingMap (get-in result [:ns1 :k1])))
    (is (map? (get-in result [:ns1 :k1 t])))
    (is (map? (get-in result [:ns1 :k1 t {:D1 "D1"}])))
    (is (instance? clojure.lang.IPersistentCollection (get-in result [:ns1 :k1 t {:D1 "D1"} :Second])))))

(deftest can-report
  (let [t 1650843000000
        result (-> (accumulator {})
                   (accumulate-at [:ns1 :k1 t {:D1 "D1"} :Second] 3)
                   report)]
    (is (= {:ns1 {[:k1 nil {:D1 "D1"} :Second] '(3)}} result))))

(deftest adjust-accumulator-behavior
  (testing "baseline"
    (let [t0 1650843000000
          t1 (+ t0 1)
          t2 (+ t0 1000)
          result (-> (accumulator {})
                     (accumulate-at [:ns1 :k1 t0 {:D1 "D1"} :Second] 3)
                     (accumulate-at [:ns1 :k1 t1 {:D1 "D1"} :Second] 4)
                     (accumulate-at [:ns1 :k1 t2 {:D1 "D1"} :Second] 5)
                     report)]
      (is (= {:ns1 {[:k1 nil {:D1 "D1"} :Second] '(5 4 3)}} result))
      (is (= ::uat/list (type (get-in result [:ns1 [:k1 nil {:D1 "D1"} :Second]]))))))
  (testing "coalesce timestamps"
    (let [t0 1650843000000
          t1 (+ t0 1)
          t2 (+ t0 1000)
          result (-> (accumulator {:resolution 10})
                     (accumulate-at [:ns1 :k1 t0 {:D1 "D1"} :Second] 3)
                     (accumulate-at [:ns1 :k1 t1 {:D1 "D1"} :Second] 4)
                     (accumulate-at [:ns1 :k1 t2 {:D1 "D1"} :Second] 5)
                     report)]
      (is (= {:ns1 {[:k1 t0 {:D1 "D1"} :Second] '(4 3)
                    [:k1 t2 {:D1 "D1"} :Second] '(5)}}
             result))))
  (testing "accumulate in frequency distribution"
    (let [t0 1650843000000
          t1 (+ t0 1)
          t2 (+ t0 1000)
          result (-> (accumulator {:accumulator :frequency-distribution})
                     (accumulate-at [:ns1 :k1 t0 {:D1 "D1"} :Second] 3)
                     (accumulate-at [:ns1 :k1 t1 {:D1 "D1"} :Second] 4)
                     (accumulate-at [:ns1 :k1 t2 {:D1 "D1"} :Second] 5)
                     report)]
      (is (= {:ns1 {[:k1 nil {:D1 "D1"} :Second] {3 1 4 1 5 1}}} result))
      (is (= ::uat/frequency-distribution (type (get-in result [:ns1 [:k1 nil {:D1 "D1"} :Second]]))))))
  (testing "accumulate in statistics set"
    (let [t0 1650843000000
          t1 (+ t0 1)
          t2 (+ t0 1000)
          result (-> (accumulator {:accumulator :statistic-set})
                     (accumulate-at [:ns1 :k1 t0 {:D1 "D1"} :Second] 3)
                     (accumulate-at [:ns1 :k1 t1 {:D1 "D1"} :Second] 4)
                     (accumulate-at [:ns1 :k1 t2 {:D1 "D1"} :Second] 5)
                     report)]
      (is (= {:ns1 {[:k1 nil {:D1 "D1"} :Second] {:min 3 :max 5 :sum 12 :count 3}}} result))
      (is (= ::uat/statistic-set (type (get-in result [:ns1 [:k1 nil {:D1 "D1"} :Second]])))))))

(deftest adjust-accumulator-in-existing-store
  (let [t0 1650843000000
        t1 (+ t0 1)
        t2 (+ t0 1000)
        t3 (+ t0 1001)
        result (-> (accumulator {})
                   (set-template-at [:ns1 :k1] {:accumulator :frequency-distribution})
                   (set-template-at [:ns1 :k2] {:accumulator :statistic-set})
                   (set-template-at [:ns1 :k3] {:resolution 10000 :accumulator :statistic-set})
                   (accumulate-at [:ns1 :k1 t0 {:D1 "D1"} :Second] 3)
                   (accumulate-at [:ns1 :k1 t1 {:D1 "D1"} :Second] 4)
                   (accumulate-at [:ns1 :k2 t0 {:D1 "D1"} :Second] 3)
                   (accumulate-at [:ns1 :k2 t1 {:D1 "D1"} :Second] 4)
                   (accumulate-at [:ns1 :k3 t0 {:D1 "D1"} :Second] 3)
                   (accumulate-at [:ns1 :k3 t3 {:D1 "D1"} :Second] 4)
                   report)]
    (is (= {:ns1 {[:k1 nil {:D1 "D1"} :Second] {3 1 4 1}
                  [:k2 nil {:D1 "D1"} :Second] {:min 3 :max 4 :sum 7 :count 2}
                  [:k3 t0 {:D1 "D1"} :Second] {:min 3 :max 4 :sum 7 :count 2}}}
           result))))

(deftest can-flush
  (let [t 1650843000000]
    (is (= {:namespace {}}
           (-> (accumulator {})
               (set-template-at [:namespace :name] {:resolution 0})
               (accumulate-at [:namespace :name t {} :Count] 100)
               flush!
               report)))))

(deftest auto-zero
  (let [t 1650843000000]
    (is (= {:namespace {[:name nil {} :Count] '(0)}}
           (-> (accumulator {})
               (set-template-at [:namespace :name] {:auto-zero? true :resolution 0})
               (accumulate-at [:namespace :name t {} :Count] 100)
               flush!
               report)))))
