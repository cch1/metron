(ns com.hapgood.metron.frequency-distribution-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.hapgood.metron.frequency-distribution :refer :all]))

(deftest accumulate
  (is (= 1
         (-> EMPTY
             (conj 0)
             count))))

(deftest preserve-metadata
  (is (= {:x true}
         (-> (with-meta EMPTY {:x true})
             (conj 0)
             meta))))
