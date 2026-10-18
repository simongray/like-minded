(ns dk.simongray.like-minded.cache
  "Values kept in a document store for a while, e.g. the answers of a
  service that an app shows again without asking for them, until they're
  too old or too many.

  A cache is a map of these:

  - :store, a store with :list and :delete
  - :prefix, the start of the names of its documents, so that several
    caches can share a store, e.g. \"search.\"
  - :max-count, the most values that it keeps, the oldest dropped first
  - :max-seconds, the age past which a value is dropped
  - :codec, of the values, document/edn-codec by default

  A key is a string, or any value that prints the same each time, e.g. a
  vector of keywords and strings. Each value is a document whose name
  holds the hash of its key and the time that it was kept, so dropping the
  old ones reads no bodies. Every function gives its result directly on
  the JVM and as a promise in ClojureScript."
  (:require [clojure.string :as str]
            [dk.simongray.like-minded.bytes :as bytes]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.like-minded.time :as time]
            [dk.simongray.wary-fetch.async :as async])
  #?(:clj (:import [java.security MessageDigest])))

(defn hashed
  "The SHA-256 of the key `k` as printed, in hexadecimal, in ClojureScript
  as a promise."
  [k]
  (let [b (bytes/encode (pr-str k))]
    #?(:clj  (bytes/hex (.digest (MessageDigest/getInstance "SHA-256") b))
       :cljs (.then (.digest (.-subtle js/crypto) "SHA-256" b)
                    #(bytes/hex (js/Uint8Array. %))))))

(defn document-name
  "The name of the document of the `cache` that keeps the value of the key
  with the hash `h`, kept at the instant `at`."
  [{:keys [prefix]} h at]
  (str prefix h "-" (inst-ms at)))

(defn entries
  "The values that the `cache` keeps, by the names of the `listed`
  documents of its store, the newest first: maps of the :name of each
  document, the :hash of its key and the :at that it was kept."
  [{:keys [prefix] :or {prefix ""}} listed]
  (->> listed
       (keep (fn [{:keys [name]}]
               (when (str/starts-with? name prefix)
                 (when-let [[_ h ms] (re-matches #"([0-9a-f]{64})-(\d{1,15})"
                                                 (subs name (count prefix)))]
                   {:name name :hash h :at (time/->inst (parse-long ms))}))))
       (sort-by #(- (inst-ms (:at %))))))

(defn age
  "The age in seconds of the `entry` at the instant `at`, by the :at that
  it was kept."
  [entry at]
  (/ (- (inst-ms at) (inst-ms (:at entry))) 1000))

(defn expired?
  "Whether the `entry` is older than the :max-seconds of the `cache` at the
  instant `at`."
  [{:keys [max-seconds]} entry at]
  (boolean (and max-seconds (> (age entry at) max-seconds))))

(defn dropped
  "The names of the `entries` of the `cache` that go at the instant `at`:
  those older than its :max-seconds, those of a key with a newer one, and
  the oldest past its :max-count."
  [{:keys [max-count] :as cache} entries at]
  (let [newest  (sort-by #(- (inst-ms (:at %))) entries)
        current (->> (vals (group-by :hash newest))
                     (map first)
                     (remove #(expired? cache % at))
                     (sort-by #(- (inst-ms (:at %)))))
        kept    (into #{}
                      (map :name)
                      (cond->> current max-count (take max-count)))]
    (into [] (comp (map :name) (remove kept)) newest)))

(defn prune!
  "Drop the values of the `cache` that its limits leave out at the instant
  `at`, now by default, and give the names of their documents."
  ([cache]
   (prune! cache (time/now)))
  ([{:keys [store] :as cache} at]
   (async/then ((:list store))
               (fn [listed]
                 (let [names (dropped cache (entries cache listed) at)]
                   (async/then (async/all (:delete store) names)
                               (constantly names)))))))

(defn clear!
  "Drop every value of the `cache`, and give the names of their documents."
  [{:keys [store] :as cache}]
  (async/then ((:list store))
              (fn [listed]
                (let [names (mapv :name (entries cache listed))]
                  (async/then (async/all (:delete store) names)
                              (constantly names))))))

(defn fetch!
  "The value that the `cache` keeps under the key `k` at the instant `at`,
  now by default, as a map of the :value and the :at that it was kept.

  It's nil when the cache keeps none, or none younger than its
  :max-seconds. A document that doesn't decode counts as none."
  ([cache k]
   (fetch! cache k (time/now)))
  ([{:keys [store codec] :or {codec document/edn-codec} :as cache} k at]
   (async/then
    (async/all [(hashed k) ((:list store))])
    (fn [[h listed]]
      (when-let [entry (->> (entries cache listed)
                            (filter #(= h (:hash %)))
                            (remove #(expired? cache % at))
                            first)]
        (async/then ((:fetch store) (:name entry) {})
                    (fn [doc]
                      (when doc
                        (try
                          {:value ((:decode codec) (:body doc))
                           :at    (:at entry)}
                          (catch #?(:clj Exception :cljs :default) _
                            nil))))))))))

(defn put!
  "Keep the value `v` under the key `k` in the `cache` at the instant `at`,
  now by default, in place of the one before, and drop what its limits
  leave out. It gives a map of the :value and the :at."
  ([cache k v]
   (put! cache k v (time/now)))
  ([{:keys [store codec] :or {codec document/edn-codec} :as cache} k v at]
   (async/then
    (hashed k)
    (fn [h]
      (async/then
       ((:put store) (document-name cache h at) ((:encode codec) v) {})
       (fn [_]
         (async/then (prune! cache at)
                     (fn [_] {:value v :at at}))))))))

(comment
  (require '[dk.simongray.like-minded.store :as store])

  (def searches
    {:store       (store/memory)
     :prefix      "search."
     :max-count   2
     :max-seconds 86400})

  (put! searches [:apple "history"] ["a show"])
  (fetch! searches [:apple "history"])
  ;; => {:value ["a show"] :at #inst "…"}
  ((:list (:store searches)))
  (clear! searches)
  #_.)
