(ns com.hapgood.metron.accumulator.statistic-set
  "Support statistically accumulating values in a store"
  (:require [com.hapgood.metron.accumulator.protocol]))

(deftype StatisticSet [store m]
  com.hapgood.metron.accumulator.protocol.Accumulate
  (accumulate [this v] (let [store' (-> store
                                        (update :max (fnil max (Double/NEGATIVE_INFINITY)) v)
                                        (update :min (fnil min (Double/POSITIVE_INFINITY)) v)
                                        (update :sum (fnil + 0) v)
                                        (update :count (fnil inc 0)))]
                         (StatisticSet. store' m)))
  (reset [this] (StatisticSet. (empty store) m))
  clojure.lang.Counted
  (count [this] (:count store))
  clojure.lang.IMeta
  (meta [this] m)
  clojure.lang.IObj
  (withMeta [this m] (StatisticSet. store m))
  Object
  (toString [this] (str "StatisticSet:[" (pr-str store) "〛"))
  (hashCode [this] (hash store))
  (equals [this that] (and (= (type this) (type that))
                           (= (.hashCode this) (.hashCode that))))
  clojure.core.protocols/Datafiable
  (datafy [this] store))

(def EMPTY
  "An empty persistent data structure that statisically aggregates accumulated values"
  (->StatisticSet {} {}))
