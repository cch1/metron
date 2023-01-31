(ns com.hapgood.metron.buffer
  "Accumulate metrics in a buffer for eventual flushing to Cloudwatch"
  (:require [clojure.datafy :refer [datafy]]
            [com.hapgood.metron.frequency-distribution :as frequency-distribution]
            [com.hapgood.metron.statistic-set :as statistic-set]
            [com.hapgood.metron.coalescing-map :as cm]
            [com.hapgood.metron.branchable :as branchable])
  (:import (com.hapgood.metron.statistic_set StatisticSet)
           (com.hapgood.metron.frequency_distribution FrequencyDistribution)))

(defn- isochrone
  [resolution]
  {:pre [(nat-int? resolution)]}
  (let [keyfn (case resolution
                0 (constantly nil) ; "Monochrone"
                1 identity ; avoid the computation
                (comp (fn isochronus [t] (* resolution (quot t resolution)))))]
    (cm/create keyfn)))

(defn- sub-accumulator
  [{:keys [resolution accumulator auto-zero?]}]
  (let [resolution (or resolution 0)
        a ({:frequency-distribution frequency-distribution/EMPTY :statistic-set statistic-set/EMPTY} accumulator ())
        i (with-meta (isochrone resolution) {:resolution resolution :auto-zero? auto-zero?})]
    (branchable/engrain [i {} {} a])))

(defn accumulator [options]
  (branchable/engrain [{} {} (sub-accumulator options)]))

(defn set-template-at
  [accumulator ks options]
  (branchable/assoc-in accumulator ks (sub-accumulator options)))

(defn accumulate-at
  [accumulator ks v]
  (branchable/update-in accumulator ks conj v))

(defn- accumulator-type
  [accumulator]
  ({StatisticSet ::statistic-set
    FrequencyDistribution ::frequency-distribution
    clojure.lang.PersistentList ::list}
   (type accumulator)))

(defn report
  [store]
  (reduce-kv (fn [acc ns nyms]
               (assoc acc ns (reduce-kv (fn [acc nym isochrone]
                                          (let [m (meta isochrone)]
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
                                                       isochrone)))
                                        {}
                                        nyms)))
             {}
             store))

(defn zero [this] (conj (empty this) 0))

(defn flush!
  "Flush the given accumulator store"
  [accumulator]
  (reduce-kv (fn [acc ns nyms]
               (assoc acc ns (reduce-kv (fn [acc nym isochrone]
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
                                        (empty nyms)
                                        nyms)))
             (empty accumulator)
             accumulator))
