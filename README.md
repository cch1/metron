# Metron
From the Greek métron, meaning “measure”.

A library for recording custom [AWS Cloudwatch Metrics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/publishingMetrics.html).  To improve performance and lower cost, recorded metrics are buffered in a local accumulator before being periodically flushed and published to AWS.  Facilities are provided for controlling the aggregation of metrics in the local accumulator to allow balancing cost/performance against detail reporting.

## Recording Phase
There is one primary function, `record`, in the API for recording custom metrics.  From the Clojure docs:

``` text
metron/record
([nym value unit & {:keys [dimensions timestamp]}])
Record a metric of value `value` in the given `unit` identified by `nym` by accumulating it in the system accumulator.
Spec
args: (cat :nym :metron/nym :value number? :unit :cw/unit :options (keys* :opt-un [:metron/dimensions :cw/timestamp]))
ret: associative?
```

There are convenience functions in the API that build on `record`:

* `increment-counter`
* `decrement-counter`
* `record-duration`

And a convenience macro `record-delta-t` that builds on `record-duration`.

### Naming
AWS Cloudwatch metrics must have a name within a namespace.  Because the Clojure namespace at the point of recording is rarely appropriate for the metric namespace, there are multiple ways of naming a metric:

1. With a qualified identifier (symbol or keyword).  The metric namespace and name are taken from the corresponding elements of the identifier.
2. With a simple identifier (symbol or keyword).  The metric namespace is taken from the dynamic scope set by the `metron/with-namespace` and `metron/with-root-namespace` macros.
The metric name is the name of the simple identifier.
3. With a vector tuple.  The metric namespace is the string representation of the first element of the tuple.  The metric name is the string representation of the second element of the tuple.
4. With an arbitrary object.  The metric namespace is taken from the dynamic scope set by the `metron/with-namespace` and `metron/with-root-namespace` macros.
The metric name is the string representation of the object.

Naming is hard, but metric names don't have to be bound to the (current) code structure.

### Dimensions
AWS Cloudwatch metrics can optionally be "faceted" with up to ten [dimensions](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/publishingMetrics.html#usingDimensions).  Dimensions can either be specified at the moment of recording a metric using any of the above functions, or default dimensions will be included from the dynamic scope managed by the `metron/with-dimension` and `metron/with-dimensions` macros.  At the moment of recording, dimension keys and values can either be strings or simple identifiers (keywords and symbols) that will be converted to strings at the moment of publishing.  It is possible to record the same metric with multiple sets of dimensions -this is sometimes desireable to allow drill-down capability in the Cloudwatch console while preserving an overall picture since, unfortunately, the Cloudwatch console does not support dynamic summation of dimension values.

### Units
Every metric must have a unit.  Units are Clojure keyword corresponding to the [AWS-supported units](https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_MetricDatum.html) with ratio-slashes (`/`) replaced with the word `Per`.  For example, `"Bytes/Second"` becomes `:BytesPerSecond`.  Note that the counter functions `increment-counter` and `decrement-counter` imply the `:Count` unit and the timing function `record-duration` implies the use of `:Milliseonds`.

### Values
Metric values can be any number.  At the time of publishing, all values are converted to Doubles.

### Timestamps
All metrics are automatically stamped with the current time with millisecond resolution at the moment of recording.  The actual time applied when publishing can change due to local aggregation as descibed below.

### Aggregation
Metrics are accumulated in such a way as to allow aggregation of multiple measurements.  This can drastically reduce the cost and improve the efficiency of your metrics system.  There are two complementary mechanisms for aggregating metrics: coalescing of the timestamps and statistical aggregation of values.  Both are managed using the `metron/configure-metric` function.

#### Time Coalescing
Coalescing, or bucketing, multiple measurements taken in a narrow time window into a single aggregate can reduce the amount of data published by an order of magnitude or more with little loss of information content.  The millisecond precision of metric timestamps is misleading: AWS coalesces metrics into buckets with either one minute (default) or one second ([high-resolution](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/publishingMetrics.html#high-resolution-metrics)) resolution.  You can configure the resolution (in milliseconds) for each metric.  Setting a resolution of less than 60,000ms will produce high-resolution metrics.

The most efficient coalescing is achieved by bucketing all measurements into one bucket that is timestamped when the buffer is flushed.  Set a resolution of zero for any metric you want to coalesce with this "bucket-by-flush" granularity.  Your publishing frequency thus determines the effective resolution of your metrics down to a lower limit of one minute.

The default resolution is `60000` ms (one minute).

#### Statistical Aggregation
Cloudwatch's statistical aggregation, called a [`StatisticSet`](https://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_StatisticSet.html) measures the minimum, maximum, sum and count of many measurents of a metric that coalesce into the same time window.  At its introduction approximately a decade ago Cloudwatch _only_ supported publishing statistical aggregations or individual values.  Now it is possible to publish individual values, statistical aggregations or a frequency distribution.

* `:statistic-set` - Statistic sets are extremely efficient: four numbers can represent an unlimited number of measurements and still accurately measure their central tendency.  Statistic sets cannot measure the variance in measurements.  Practically speaking this means that the Cloudwatch console will not give you the option of displaying percentiles.  Use statistic sets when variance is likely to be constant or not interesting.

* `:frequency-distribution` - Frequency distributions are more space-efficient than individual measurements, especially when many values are repeated (_low-cardinality_) within a measuring period.  For extremely low-cardinality values (such as boolean values of 0 and 1, or up/down values of -1 and 1) they can be even more efficient than a statistic set and no less meaningful.

* `:values` - Individual values can be buffered in metron, but at a publishing "volume" and buffering space cost relative to a frequency distribution.  There is no console reporting advantage to publishing individual values versus publishing a frequency distribution.

The default aggregation is `:statistic-set`.

### Example usage
The following example generates six distinct metrics.

``` clojure
(require '[metron])

(metron/configure-metric :service-name/ResponseTime {:accumulator :frequency-distribution})
(metron/configure-metric :service-name/Pending {:accumulator :frequency-distribution :resolution 1000})
(metron/configure-metric :service-name/PayloadSize {:resolution 0})

(defn process-deposit
  [context]
  (let [user (identify-user context)
        [amount currency] (identify-amount context)
        body (build-request-body context)]
	(metron/record :Account.IRA/Deposit amount :None :dimensions {:User user :Currency (name currency)}) 
    (metron/with-dimension :User (str user)
      (metron/record ::PayloadSize (count body) :Bytes)
	  (metron/record ::service-name/Pending 1 :Count)
      (metron/record-delta-t :service-name/ResponseTime
			     (http/post server-url body
				        {:on-success (bound-fn [response]
							   (metron/record ::service-name/Pending -1 :Count)
						       (metron/increment-counter :service-name/Success)
						       (do-something response))
				         :on-failure (bound-fn []
							   (metron/record ::service-name/Pending -1 :Count)
						       (metron/increment-counter :service-name/Failure)
						       (handle-failure))})))))
```

## Flushing and Publishing Phase
You should schedule a periodic job to flush the metrics buffer and publish to Cloudwatch.  The function `metron/flush!` both flushes the buffer and publishes the flushed values to Cloudwatch using a Cognitect AWS client.  You can create a client with the `metron/create-client` function.

Don't forget to flush and publish metrics on the shutdown of your application.

### Example usage
There are many libraries to manage scheduled jobs with varying degrees of sophistication.  But clojure.core.async does a pretty good job as well.  In this example we schedule the flushing of metrics every five minutes and at shutdown of the JVM.

``` clojure
(def flush-control
  (let [stop (async/chan)
        client (metron/create-client)
        interval (* 1000 60 5)]
    (async/go-loop []
      (when (async/alt! (async/timeout interval) (async/thread (tap> (metron/flush! client)))
                        stop nil)
        (recur)))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (async/close! stop)
                                           (metron/flush! client))))
    stop))
```

This is only a toy example and you should take strong measures to ensure your metrics flushing job is resilient in the face of failures.

## Auto Zero
Metrics configured for "bucket-by-flush" (`:resolution` => `0`) can be further configured to zero out existing accumulated measurements instead of deleting them after every flush.  The accumulated [zero value](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/publishingMetrics.html#publishingZero) ensures that at least one datum is published on every flush.

Note that automatic zero values are accumulated for metrics only after they have been explicitly accumulated and published one time.  Only explicitly recorded dimensions and units are automatically re-published.

Here's an example of configuring a "heartbeat" metric for `autozero?` behavior:
``` clojure
(metron/configure-metric ::MyApp/RequestsReceived {:zuto-zero? true :resolution 0})
```
