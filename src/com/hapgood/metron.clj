(ns com.hapgood.metron
  "A Clojure library for recording application metrics and flushing them to AWS Cloudwatch"
  (:require [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [com.hapgood.metron.buffer :as buffer]
            [com.hapgood.metron.cloudwatch :as cw])
  (:import (java.time Instant)))

(s/def ::nym (s/or :named ident? :tuple vector? :stringable (constantly true)))

(def ^:dynamic *dimensions* {}) ;; keyword keys and values
(def ^:dynamic *namespace* "") ;; concatenation of period-separated string segments
(def ^:dynamic *accumulator* (atom (buffer/accumulator {:resolution (* 1000 60) :accumulator :statistic-set})))

(defn- valid-metric-name-tuple? [[ns n]] (and (s/valid? ::cw/namespace ns) (s/valid? ::cw/name n)))

(defmulti metric-name "Determine the namespace and name of identified metric" type)
(defmethod metric-name clojure.lang.Named
  [nym]
  {:post [(valid-metric-name-tuple? %)]}
  [(if (qualified-ident? nym) (namespace nym) *namespace*) (name nym)])
(defmethod metric-name clojure.lang.IPersistentVector
  [nym]
  {:post [(valid-metric-name-tuple? %)]}
  (mapv str nym))
(defmethod metric-name :default
  [nym]
  {:post [(valid-metric-name-tuple? %)]}
  [*namespace* (str nym)])
(s/fdef metric-name
  :args (s/cat :nym ::nym)
  :ret (s/tuple ::cw/namespace ::cw/name))

(defn- now [] (inst-ms (Instant/now)))

(defn configure-metric
  [nym options]
  (swap! *accumulator* buffer/set-template-at (metric-name nym) options))

(defn effective-namespace
  [segment]
  (string/join "." (remove string/blank? [*namespace* (str segment)])))

(defn effective-dimensions [dimensions]
  {:pre [(map? dimensions)] :post [(map? %)]}
  (merge *dimensions* (into {} (map (fn [[k v]] [(keyword k) (name v)]) dimensions))))

(defmacro with-dimensions
  "Execute body with the given dimension key/value pair added to the effective dimension map"
  [dimensions & body]
  `(binding [*dimensions* (effective-dimensions ~dimensions)] ~@body))

(defmacro with-dimension
  "Execute body with the given dimension key/value pair added to the effective dimension map"
  [key value & body]
  `(with-dimensions {~key ~value} ~@body))

(defmacro with-namespace
  "Execute body with the given namespace appended to the effective namespace"
  [namespace & body]
  `(binding [*namespace* (effective-namespace ~namespace)
             *dimensions* {}] ~@body))

(defmacro from-root
  "Execute body with the given namespace as the effective namespace and clear effective dimensions"
  [namespace & body]
  `(binding [*namespace* (str ~namespace) *dimensions* {}] ~@body))

(def create-client
  "Create client with given cognitect.credentials and options"
  cw/create-client)

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
  "Flush the buffer and publish it to Cloudwatch.  Return count of issued requests by namespace."
  [client]
  (let [[old _] (swap-vals! *accumulator* buffer/flush!)]
    (publish client (buffer/report old))))

(defn record
  "Record a metric of value `value` in the given `unit` identified by `nym` by accumulating it in the system accumulator."
  [nym value unit & {:keys [dimensions timestamp]}]
  (let [[ns n] (metric-name nym)
        t (or (some-> timestamp inst-ms) (now))
        dimensions (or dimensions *dimensions*)
        unit (or unit :None)]
    (swap! *accumulator* buffer/accumulate-at [ns n t dimensions unit] value)))

(s/def ::dimension-key (s/or :string string?
                             :named (partial instance? clojure.lang.Named)))
(s/def ::dimension-value string?)
(s/def ::dimensions (s/map-of ::dimension-key ::dimension-value))
(s/fdef record
  :args (s/cat :nym ::nym :value number? :unit :cw/unit
               :options (s/keys* :opt-un [::dimensions :cw/timestamp]))
  :ret associative?)

(defn increment-counter
  "Increment the counter identified by `nym`."
  [nym & options]
  (apply record nym 1.0 :Count options))

(defn decrement-counter
  "Decrement the counter identified by `nym`."
  [nym & options]
  (apply record nym -1.0 :Count options))

(defn record-duration
  "Wrap the given function `f` within a recorder of an elapsed time metric identified by `nym`.
  Note that dynamic namespace and dimension determinations are performed at the time the wrapped
  function is invoked."
  [f nym & options]
  (fn [& args]
    (let [start (System/currentTimeMillis)
          result (apply f args)]
      (apply record nym (- (System/currentTimeMillis) start) :Milliseconds options)
      result)))

(defmacro record-delta-t
  "Record the execution time of the body in a metric identified by `nym`."
  [nym & body]
  `((record-duration (fn [] ~@body) ~nym)))
