(ns com.hapgood.metron.statistic-set
  "Support statistically accumulating values in a store")

(deftype StatisticSet [store m]
  clojure.lang.IPersistentCollection
  (empty [this] (StatisticSet. (empty store) m))
  (cons [this v] (let [store' (-> store
                                  (update :max (fnil max (Double/NEGATIVE_INFINITY)) v)
                                  (update :min (fnil min (Double/POSITIVE_INFINITY)) v)
                                  (update :sum (fnil + 0) v)
                                  (update :count (fnil inc 0)))]
                   (StatisticSet. store' m)))
  (equiv [this that] (and (instance? StatisticSet that)
                          (= store (.-store that))))
  clojure.lang.Counted
  (count [this] (:count store))
  clojure.lang.IMeta
  (meta [this] m)
  clojure.lang.IObj
  (withMeta [this m] (StatisticSet. store m))
  clojure.core.protocols/Datafiable
  (datafy [this] store))

(def EMPTY
  "An empty persistent data structure that statisically aggregates accumulated values"
  (->StatisticSet {} {}))
