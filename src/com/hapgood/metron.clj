(ns com.hapgood.metron
  "A Clojure library for recording application metrics and flushing them to AWS Cloudwatch"
  (:require [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [com.hapgood.metron.buffer :as buffer]
            [com.hapgood.metron.branchable :refer [Branchable]]
            [com.hapgood.metron.cloudwatch :as cw])
  (:import (java.time Instant)))

(defn- valid-metric-name-tuple? [[ns n]] (and (s/valid? ::cw/namespace ns) (s/valid? ::cw/name n)))

(defn metric-name-tuple
  [nym]
  {:pre [(qualified-ident? nym)] :post [(valid-metric-name-tuple? %)]}
  [(namespace nym) (name nym)])

(defn- now [] (inst-ms (Instant/now)))
(def ^:dynamic *dimensions* {}) ;; keyword keys and values

(defn configure-metric
  [acc nym options]
  (swap! acc buffer/set-template-at (metric-name-tuple nym) options))

(defn effective-dimensions [dimensions]
  {:pre [(map? dimensions)]
   :post [(map? %) (every? (every-pred string? (complement empty?)) (vals %))]}
  (merge *dimensions* (into {} (map (fn [[k v]] [(keyword k) (name v)]) dimensions))))

(defmacro with-dimensions
  "Execute body with the given dimension key/value pair added to the effective dimension map"
  [dimensions & body]
  `(binding [*dimensions* (effective-dimensions ~dimensions)] ~@body))

(defmacro with-dimension
  "Execute body with the given dimension key/value pair added to the effective dimension map"
  [key value & body]
  `(with-dimensions {~key ~value} ~@body))

(defn- metric-datums
  "Convert the k-v leaves of the metrics report into a sequence of AWS MetricDatum"
  [[[n t d-map unit :as k] accumulator]]
  (let [resolution (as-> (-> accumulator meta :resolution) %
                     (if (zero? %) Long/MAX_VALUE %))
        dimensions (into {} (map (juxt (comp name key) (comp str val))) d-map)
        data (case (type accumulator)
               ::buffer/statistic-set [{:type :statistic-set :statistic-set accumulator}]
               ::buffer/frequency-distribution (map #({:type :frequency-distribution :frequency-distribution %})
                                                    (partition-all 150 accumulator))
               ::buffer/list (map #({:type :value :value %}) accumulator))]
    (map (partial cw/metric-datum n t dimensions unit resolution) data)))

(defn- publish
  "Publish a metrics report (a map of `{namespace {k accumulator}}`) to CloudWatch; return count of issued
  requests by namespace."
  [client report]
  (reduce-kv (fn [acc ns k->accs]
               (let [now (now)
                     inject-time (fn [[[n t d-map unit :as k] v]] [[n (or t now) d-map unit] v])
                     xform (comp (map inject-time)
                                 (mapcat metric-datums)
                                 (partition-all 20)
                                 (map (partial cw/put-metric-data-request ns)))
                     requests (sequence xform k->accs)]
                 (run! (partial cw/put-metric-data client) requests)
                 (assoc acc ns (count requests))))
             {}
             report))

(defn flush!
  "Flush the accumulator and publish it to Cloudwatch.  Return count of issued requests by namespace."
  [acc client]
  (let [[old _] (swap-vals! acc buffer/flush!)]
    (publish client (buffer/report old))))

(defn accumulator
  [& {:as options}]
  (let [options (merge {:resolution (* 1000 60) :accumulator :statistic-set} options)]
    (atom (buffer/accumulator options))))

(def ^:dynamic *accumulator*)

(defmacro with-accumulator
  [acc & body]
  `(let [acc# ~acc]
     (binding [*accumulator* acc#] ~@body)))

(defn record*
  "Record a metric of value `value` in the given `unit` identified by `nym` by accumulating it in the system accumulator."
  [>buffer nym value unit & {:keys [dimensions timestamp]}]
  {:pre [(satisfies? Branchable @>buffer)]}
  (let [t (or (some-> timestamp inst-ms) (now))
        dimensions (or dimensions *dimensions*)
        unit (or unit :None)
        [ns n] (metric-name-tuple nym)]
    (swap! >buffer buffer/accumulate-at [ns n t dimensions unit] value)))

(s/def ::dimension-key (s/or :string string?
                             :named (partial instance? clojure.lang.Named)))
(s/def ::dimension-value (s/and string? (complement empty?)))
(s/def ::dimensions (s/map-of ::dimension-key ::dimension-value))
(s/def ::buffer (fn [obj] (and (instance? clojure.lang.IDeref obj)
                               (satisfies? Branchable @obj))))
(s/def ::nym qualified-ident?)
(s/fdef record*
  :args (s/cat :buffer ::buffer :nym ::nym :value number? :unit ::cw/unit
               :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)

(defn increment-counter*
  "Increment the counter identified by `nym`."
  [acc nym & options]
  (apply record* acc nym 1.0 :Count options))

(s/fdef increment-counter*
  :args (s/cat :buffer ::buffer :nym ::nym :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)

(defn decrement-counter*
  "Decrement the counter identified by `nym`."
  [acc nym & options]
  (apply record* acc nym -1.0 :Count options))

(s/fdef decrement-counter*
  :args (s/cat :buffer ::buffer :nym ::nym :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)

(defn record-duration*
  "Wrap the given function `f` within a recorder of an elapsed time metric
  identified by `nym`.  Note that dynamic dimension determination is performed
  at the time the wrapped function is invoked."
  [acc f nym & options]
  (fn [& args]
    (let [start (System/currentTimeMillis)
          result (apply f args)]
      (apply record* acc nym (- (System/currentTimeMillis) start) :Milliseconds options)
      result)))

(s/fdef record-duration*
  :args (s/cat :buffer ::buffer :f fn? :nym ::nym :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)

(defmacro record-delta-t*
  "Record the execution time of the body in a metric identified by `nym`."
  [acc nym & body]
  `(let [acc# ~acc]
     ((record-duration* acc# (fn [] ~@body) ~nym))))

;;; Convenience wrappers that forego the explicit accumlator arg in favor of *accumlator*
(defn record [& args] (apply record* *accumulator* args))
(s/fdef record
  :args (s/cat :nym ::nym :value number? :unit ::cw/unit
               :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)
(defn increment-counter [& args] (apply increment-counter* *accumulator* args))
(s/fdef increment-counter
  :args (s/cat :nym ::nym :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)
(defn decrement-counter [& args] (apply decrement-counter* *accumulator* args))
(s/fdef decrement-counter
  :args (s/cat :nym ::nym :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)
(defn record-duration [& args] (apply record-duration* *accumulator* args))
(s/fdef record-duration
  :args (s/cat :f fn? :nym ::nym :options (s/keys* :opt-un [::dimensions ::cw/timestamp]))
  :ret associative?)
(defmacro record-delta-t [& args] `(record-delta-t* *accumulator* ~@args))
