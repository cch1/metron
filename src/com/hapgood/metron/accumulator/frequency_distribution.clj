(ns com.hapgood.metron.accumulator.frequency-distribution
  "Support accumulating values in a frequency distribution"
  (:require [com.hapgood.metron.accumulator.protocol]))

(deftype FrequencyDistribution [store m]
  com.hapgood.metron.accumulator.protocol.Accumulate
  (accumulate [this v] (FrequencyDistribution. (update store v (fnil inc 0)) m))
  (reset [this] (FrequencyDistribution. (empty store) m))
  clojure.lang.Counted
  (count [this] (reduce + (vals store)))
  clojure.lang.IMeta
  (meta [this] m)
  clojure.lang.IObj
  (withMeta [this m] (FrequencyDistribution. store m))
  Object
  (toString [this] (str "FrequencyDistribution:[" (pr-str store) "〛"))
  (hashCode [this] (hash store))
  (equals [this that] (and (= (type this) (type that))
                           (= (.hashCode this) (.hashCode that))))
  clojure.core.protocols/Datafiable
  (datafy [this] store))

(def EMPTY
  "An empty persistent data structure that captures a frequency distribution of accumulated values"
  (->FrequencyDistribution {} {}))
