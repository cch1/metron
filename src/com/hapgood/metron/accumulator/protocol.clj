(ns com.hapgood.metron.accumulator.protocol
  "Accumulate metrics in a buffer for eventual flushing to Cloudwatch"
  (:require [clojure.datafy :refer [datafy]]))

(defprotocol Accumulate
  (accumulate [this v])
  (reset [this]))

(extend-protocol Accumulate
  clojure.lang.IPersistentList
  (accumulate [this v] (conj this v))
  (reset [this] (empty this)))
