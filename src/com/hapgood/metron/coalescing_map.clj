(ns com.hapgood.metron.coalescing-map
  "Support a data type similar to a hash-map but where keys are transformed by a fn before all operations.")

(declare ->CoalescingMap)

(deftype CoalescingMap [keyfn store m]
  clojure.lang.Associative
  (containsKey [this k] (.containsKey store (keyfn k)))
  (assoc [this k v] (->CoalescingMap keyfn (.assoc store (keyfn k) v) m))
  (entryAt [this k] (.entryAt store (keyfn k)))
  clojure.lang.ILookup
  (valAt [this k] (.valAt store (keyfn k)))
  (valAt [this k default] (.valAt store (keyfn k) default))
  clojure.lang.IFn
  (invoke [this k] (.entryAt this k))
  clojure.core.protocols/IKVReduce
  (kv-reduce [this f init] (reduce-kv f init store))
  clojure.lang.IPersistentCollection
  (count [this] (count store))
  (empty [this] (->CoalescingMap keyfn (empty store) m))
  (cons [this v] (->CoalescingMap keyfn (.cons store v) m))
  (equiv [this that] (.equiv that store))
  clojure.lang.Seqable
  (seq [this] (seq store))
  Object
  (toString [this] (str "〚" store "〛"))
  clojure.lang.IObj
  (withMeta [this m] (->CoalescingMap keyfn store m))
  clojure.lang.IMeta
  (meta [this] m))

(defn create [keyfn]
  (->CoalescingMap keyfn {} {}))

(defmethod clojure.core/print-method CoalescingMap
  [cm ^java.io.Writer writer]
  (let [pr-me (fn [[k v] w]
                (do (print-method k w) (.append w \space) (print-method v w)))]
    (#'clojure.core/print-sequential "〚" pr-me ", " "〛" (seq cm) writer)))
