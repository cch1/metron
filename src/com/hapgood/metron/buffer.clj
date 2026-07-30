(ns com.hapgood.metron.buffer
  "Accumulate metrics in a buffer for eventual flushing to Cloudwatch"
  (:require [clojure.datafy :refer [datafy]]
            [com.hapgood.metron.accumulator.protocol :as accumulator]
            [com.hapgood.metron.accumulator.frequency-distribution :as frequency-distribution]
            [com.hapgood.metron.accumulator.statistic-set :as statistic-set]
            [com.hapgood.metron.coalescing-map :as cm]
            [com.hapgood.metron.branchable :as branchable])
  (:import (java.time Instant)
           (com.hapgood.metron.accumulator.statistic_set StatisticSet)
           (com.hapgood.metron.accumulator.frequency_distribution FrequencyDistribution)))

(defn- isochrone
  [resolution]
  {:pre [(nat-int? resolution)]}
  (let [keyfn (case resolution
                0 (constantly nil) ; "Monochrone"
                1 identity ; avoid the computation
                (comp (fn isochronus [t] (Instant/ofEpochMilli (* resolution (quot (inst-ms t) resolution))))))]
    (cm/create keyfn)))

(defn- sub-accumulator
  [{:keys [resolution accumulator auto-zero?]}]
  (let [resolution (or resolution 0)
        a ({:frequency-distribution frequency-distribution/EMPTY :statistic-set statistic-set/EMPTY} accumulator ())
        i (with-meta (isochrone resolution) {:resolution resolution :auto-zero? auto-zero?})]
    (branchable/engrain [i {} {} a])))

(defn accumulator [options]
  (branchable/engrain [{} (sub-accumulator options)]))

(defn set-template-at
  [accumulator ks options]
  (branchable/assoc-in accumulator ks (sub-accumulator options)))

(defn accumulate-at
  [accumulator ks v]
  (branchable/update-in accumulator ks accumulator/accumulate v))

(defn- accumulator-type
  [accumulator]
  ({StatisticSet ::statistic-set
    FrequencyDistribution ::frequency-distribution
    clojure.lang.PersistentList ::list}
   (type accumulator)))

(defn report
  [store]
  (reduce-kv (fn [acc nym isochrone]
               (let [ns (keyword (namespace nym))
                     nym (keyword (name nym))
                     m (meta isochrone)]
                 (update acc ns (fn [acc]
                                  (reduce-kv (fn [acc t dmaps]
                                               (let [m (merge m (meta dmaps))]
                                                 (reduce-kv (fn [acc dmap units]
                                                              (let [m (merge m (meta units))]
                                                                (reduce-kv (fn [acc unit accumulator]
                                                                             (assoc acc [nym t dmap unit]
                                                                                    (vary-meta (datafy accumulator)
                                                                                               (fn [m*] (-> (merge m m*)
                                                                                                            (assoc :type (accumulator-type accumulator)))))))
                                                                           acc
                                                                           units)))
                                                            acc
                                                            dmaps)))
                                             acc
                                             isochrone)))))
             {}
             store))

(defn zero [this] (accumulator/accumulate (accumulator/reset this) 0))

(defn flush!
  "Flush the given accumulator store"
  [accumulator]
  (reduce-kv (fn [acc nym isochrone]
               (assoc acc nym (reduce-kv (fn [acc t dmaps]
                                           (assoc acc t (reduce-kv (fn [acc dmap units]
                                                                     (assoc acc dmap (reduce-kv (fn [acc unit accumulator]
                                                                                                  (assoc acc unit (zero accumulator)))
                                                                                                (empty units)
                                                                                                units)))
                                                                   (empty dmaps)
                                                                   dmaps)))
                                         (empty isochrone)
                                         (if (-> isochrone meta :auto-zero?) isochrone {}))))
             (empty accumulator)
             accumulator))
