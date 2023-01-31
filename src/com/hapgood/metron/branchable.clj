(ns com.hapgood.metron.branchable
  "Support an abstract associative tree type well-suited for sparse, heterogenous trees"
  (:refer-clojure :exclude [get-in assoc-in update-in ]))

(defprotocol Branchable
  "A protocol for spawning branches from a node in a tree"
  :extend-via-metadata true
  (branch [this path] "Spawn a branch at the given path."))

(extend-protocol Branchable
  clojure.lang.IPersistentMap
  (branch [this path] (empty this))
  nil
  (branch [this path] {}))

(defn get-in
  "Retrieve the element in the given associative tree `a-tree` at the path defined by the sequence
  of keys `ks`.  Note that while this function spawns missing branches, the resulting tree is not
  persisted -consequently this function is mostly useful for speculative spawning.  In a realized
  associative tree this function is equivalent to `clojure.core/get-in`."
  [a-tree ks]
  (let [get* (fn get-in* [a-tree [k & ks] path]
               (if k
                 (let [path (conj path k)
                       ;; Use or to short-circuit a potentially expensive template constructor
                       child (or (get a-tree k) (branch a-tree path))]
                   (get-in* child ks path))
                 a-tree))]
    (get* a-tree (seq ks) [])))

(defn update-in
  [a-tree ks f & args]
  (let [update* (fn update-in* [a-tree [k & ks] path]
                  (if k
                    (let [path (conj path k)
                          ;; Use or to short-circuit a potentially expensive template constructor
                          child (or (get a-tree k) (branch a-tree path))]
                      (assoc a-tree k (update-in* child ks path)))
                    (apply f a-tree args)))]
    (update* a-tree (seq ks) [])))

(defn assoc-in
  [a-tree ks value]
  (update-in a-tree ks (constantly value)))

(defn engrain
  "Using the given sequential `template`, recursively add instance-based Branchable support that will
  yield a tree whose (missing) nodes at depth n are spawned from the nth element of `template`. Each
  template element should be a persistent data structure since it may spawn multiple branches of the
  nth generation.  The first element of the template is the root of the associative tree."
  [template]
  ((fn descend [[t & ts]]
     (if ts
       (vary-meta t merge {`branch (fn branch [this path] (descend ts))})
       t)) template))
