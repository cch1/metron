(ns com.hapgood.metron.frequency-distribution
  "Support accumulating values in a frequency distribution")

(deftype FrequencyDistribution [store m]
  clojure.lang.IPersistentCollection
  (empty [this] (FrequencyDistribution. (empty store) m))
  (cons [this v] (FrequencyDistribution. (update store v (fnil inc 0)) m))
  (equiv [this that] (and (instance? FrequencyDistribution that)
                          (= store (.-store that))))
  clojure.lang.Counted
  (count [this] (reduce + (vals store)))
  clojure.lang.IMeta
  (meta [this] m)
  clojure.lang.IObj
  (withMeta [this m] (FrequencyDistribution. store m))
  clojure.core.protocols/Datafiable
  (datafy [this] store))

(def EMPTY
  "An empty persistent data structure that captures a frequency distribution of accumulated values"
  (->FrequencyDistribution {} {}))
