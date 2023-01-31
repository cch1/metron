(ns com.hapgood.metron.cloudwatch-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.spec.test.alpha :as stest]
            [com.hapgood.metron.cloudwatch :refer :all]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as creds])
  (:import (cognitect.aws.client Client)))

(stest/instrument 'com.hapgood.metron.cloudwatch/metric-datum)
(stest/instrument 'com.hapgood.metron.cloudwatch/put-metric-data-request)

(deftest can-create-client
  (is (instance? Client (create-client))))

(deftest can-build-metric-datum
  (testing "basic"
    (let [md (metric-datum "testTime" 123456789000 {} :Seconds 1000 {:type :value :value 25})]
      (is (map? md))
      (is (= "testTime" (:MetricName md)))
      (is (= 25.0 (:Value md)))
      (is (= "Seconds" (:Unit md)))
      (is (= 123456789000 (:Timestamp md)))))
  (testing "with dimensions"
    (let [md (metric-datum "testTime" 123456789000 {"Partner" "CHC"} :Seconds 1000 {:type :value :value 10})]
      (is (= [{:Name "Partner" :Value "CHC"}] (:Dimensions md)))))
  (testing "with statistics set"
    (let [md (metric-datum "testTime" 123456789000 {} :Seconds 1000 {:type :statistic-set :statistic-set {:min 1 :max 10 :sum 21 :count 3}})]
      (is (= {:Maximum 10.0 :Minimum 1.0 :SampleCount 3.0 :Sum 21.0}
             (:StatisticValues md)))))
  (testing "with frequency distribution"
    (let [md (metric-datum "testTime" 123456789000 {} :Seconds 1000 {:type :frequency-distribution :frequency-distribution {1 1 10 2}})]
      (is (= [1.0 10.0] (:Values md)))
      (is (= [1.0 2.0] (:Counts md))))))

(deftest can-build-put-metric-data-request
  (is (= {:Namespace "TestNS" :MetricData `({})}
         (put-metric-data-request "TestNS" '({})))))
