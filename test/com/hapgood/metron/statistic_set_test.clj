(ns com.hapgood.metron.statistic-set-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.hapgood.metron.accumulator.protocol :as accumulator :refer [accumulate reset]]
            [com.hapgood.metron.accumulator.statistic-set :refer :all]))

(deftest counted
  (is (= 1
         (-> EMPTY
             (accumulate 0)
             count))))

(deftest preserve-metadata
  (is (= {:x true}
         (-> (with-meta EMPTY {:x true})
             (accumulate 0)
             meta))))

(deftest can-reset
  (is (= EMPTY
         (-> EMPTY
             (accumulate 0)
             (reset)))))
