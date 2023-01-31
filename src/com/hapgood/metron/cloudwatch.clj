(ns com.hapgood.metron.cloudwatch
  "A Clojure library for posting metrics to Amazon Cloudwatch

  http://aws.amazon.com/cloudwatch/

  Built on top of the Cognitect AWS API, which is based on the JS API

  https://github.com/cognitect-labs/aws-api
  https://docs.aws.amazon.com/AWSJavaScriptSDK/latest/AWS/CloudWatch.html"
  (:require [clojure.spec.alpha :as s]
            [cognitect.aws.client.api :as aws]))

;;; https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_MetricDatum.html
(def units #{"Seconds" "Microseconds" "Milliseconds"
             "Bytes" "Kilobytes" "Megabytes" "Gigabytes" "Terabytes"
             "Bits" "Kilobits" "Megabits" "Gigabits" "Terabits"
             "Percent"
             "Count"
             "Bytes/Second" "Kilobytes/Second" "Megabytes/Second" "Gigabytes/Second" "Terabytes/Second"
             "Bits/Second" "Kilobits/Second" "Megabits/Second" "Gigabits/Second" "Terabits/Second"
             "Count/Second"
             "None"})

(def kw-units (let [vs (seq units)
                    ks (map (fn [s] (keyword (clojure.string/replace s #"/" "Per"))) vs)]
                (zipmap ks vs)))

(s/def ::unit kw-units)
(s/def ::dimension-name (s/and string? #(re-matches #"[^:].{0,254}" %)))
(s/def ::dimension-value (s/and string? #(re-matches #".{1,255}" %)))
(s/def ::dimensions (s/map-of ::dimension-name ::dimension-value))
(s/def ::name (s/and string? #(re-matches #".{1,255}" %)))
(s/def ::namespace (s/and string? #(re-matches #"[^:].{0,254}" %)))
(s/def ::timestamp pos-int?)

(defmulti data :type)
(s/def :com.hapgood.metron.cloudwatch.data.value/value number?)
(defmethod data :value
  [_]
  (s/keys :req-un [:com.hapgood.metron.cloudwatch.data.value/value]))
(s/def :com.hapgood.metron.cloudwatch.data.statistic-set/min number?)
(s/def :com.hapgood.metron.cloudwatch.data.statistic-set/max number?)
(s/def :com.hapgood.metron.cloudwatch.data.statistic-set/sum number?)
(s/def :com.hapgood.metron.cloudwatch.data.statistic-set/count nat-int?)
(s/def :com.hapgood.metron.cloudwatch.data/statistic-set
  (s/keys :req-un [:com.hapgood.metron.cloudwatch.data.statistic-set/min
                   :com.hapgood.metron.cloudwatch.data.statistic-set/max
                   :com.hapgood.metron.cloudwatch.data.statistic-set/sum
                   :com.hapgood.metron.cloudwatch.data.statistic-set/count]))
(defmethod data :statistic-set
  [_]
  (s/keys :req-un [:com.hapgood.metron.cloudwatch.data/statistic-set]))
(s/def :com.hapgood.metron.cloudwatch.data/frequency-distribution (s/map-of number? pos-int?))
(defmethod data :frequency-distribution
  [_]
  (s/keys :req-un [:com.hapgood.metron.cloudwatch.data/frequency-distribution]))
(s/def ::data (s/multi-spec data :type))
(s/def ::resolution nat-int?)

(defn- translate-unit
  "Convert a unit string or keyword to a standard AWS Unit enumerable"
  [unit]
  (or (kw-units unit) (units unit)))

(defn create-client
  "Create a Cognitect AWS monitoring client for talking to AWS CloudWatch.  Supports
  all options supported natively by the Cognitect AWS `client` API."
  [& options] (aws/client (merge options {:api :monitoring})))

(defmulti metric-datum* :type)

(defmethod metric-datum* :statistic-set
  [{{:keys [max min sum count]} :statistic-set :as data}]
  {:pre [(>= max min) (pos-int? count) (number? sum)]}
  {:StatisticValues {:Maximum (double max)
                     :Minimum (double min)
                     :Sum (double sum)
                     :SampleCount (double count)}})

(defmethod metric-datum* :frequency-distribution
  [{frequency-distribution :frequency-distribution :as data}]
  {:Values (map double (keys frequency-distribution))
   :Counts (map double (vals frequency-distribution))})

(defmethod metric-datum* :value
  [{value :value :as data}]
  {:Value (double value)})

(defn metric-datum
  [n timestamp dimensions unit resolution data]
  (assoc (metric-datum* data)
         :MetricName (name n)
         :Unit (kw-units unit)
         :Dimensions (map (partial zipmap [:Name :Value]) dimensions)
         :Timestamp timestamp
         :StorageResolution (if (<= resolution 60000) 1 60)))

(s/fdef metric-datum
  :args (s/cat :name ::name :timestamp ::timestamp :dimensions ::dimensions :unit ::unit :resolution ::resolution :data ::data)
  :ret map?)

(defn put-metric-data-request
  "Prepare a metric data request for the given namespace and data collection.  Each
   datum in the collection must be a Metric Datum."
  [namespace data]
  {:Namespace (name namespace)
   :MetricData data})

(s/fdef put-metric-data-request
  :args (s/cat :namespace ::namespace :data (s/coll-of map? :kind sequential? :min-count 1))
  :ret map?)

(defn put-metric-data
  "Send the prepared metric data to the given AWS CloudWatch client."
  [client metric-data-request]
  (let [response (aws/invoke client
                             {:op :PutMetricData
                              :request metric-data-request})]
    (if (:ErrorResponse response)
      (throw (ex-info "Error posting metric data request!"
                      {:request metric-data-request
                       :response response}))
      response)))
