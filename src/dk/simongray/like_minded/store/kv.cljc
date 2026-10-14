(ns dk.simongray.like-minded.store.kv
  "A document store over a key-value store that the app gives, for a
  platform without IndexedDB or a folder, e.g. React Native with MMKV,
  the key-value store of expo-sqlite or AsyncStorage.

  The app gives an adapter, a map of functions over keys and text values,
  each of which can give its value or a promise:

      {:get    (fn [key])        ; the text under the key, or nil
       :set    (fn [key text])
       :delete (fn [key])
       :keys   (fn [])           ; every key, for a listing, optional
       :name   \"MMKV\"}           ; for the UI and the report

  E.g. over MMKV 4, from createMMKV, whose functions answer at once:

      (kv/store {:get    #(.getString mmkv %)
                 :set    #(.set mmkv %1 %2)
                 :delete #(.remove mmkv %)
                 :keys   #(.getAllKeys mmkv)
                 :name   \"MMKV\"})

  A document is kept under the key of its name after a prefix, with its
  version and whether its body is text or bytes, which are kept in base64.
  The version counts the writes of the document. A put with :if-version
  checks the version and writes while no other put of this process writes
  that document, but two processes of the app, e.g. a background task,
  can still race. A listing reads each document whole, since a key-value
  store keeps no versions apart from the values."
  (:require [clojure.string :as str]
            [dk.simongray.like-minded.bytes :as bytes]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.wary-fetch.async :as async]))

(def default-prefix
  "The prefix of the keys of the documents, unless the options of a store
  give another, so that they stay apart from the app's other keys."
  "like-minded/")

(defn ^:no-doc encoded
  "The text that keeps the `body` of a document at `version`: the version
  and whether the body is text or bytes on the first line, then the text
  or the base64 of the bytes."
  [version body]
  (if (string? body)
    (str version " text\n" body)
    (str version " base64\n" (bytes/base64 body))))

(defn ^:no-doc decoded
  "The :version and the :body that the text `s` of a document keeps, or nil
  for a text that keeps none."
  [s]
  (when (string? s)
    (when-let [i (str/index-of s "\n")]
      (let [[version kind] (str/split (subs s 0 i) #" ")
            payload        (subs s (inc i))]
        (case kind
          "text"   {:version version :body payload}
          "base64" (some->> (bytes/from-base64 payload)
                            (hash-map :version version :body))
          nil)))))

(defn store
  "The store over the key-value store of the app's `adapter`, with the
  :prefix of `opts` before the names of its documents, default-prefix by
  default. Without the :keys of the adapter, the store has no :list."
  ([adapter]
   (store adapter {}))
  ([{get-text :get set-text :set delete-text :delete all-keys :keys
     :as      adapter}
    {:keys [prefix] :or {prefix default-prefix} :as opts}]
   (let [k       #(str prefix %)
         lock-id (str "dk.simongray.like-minded.store.kv/" (random-uuid) "/")
         read    #(async/then (get-text (k %)) decoded)
         stale?  (fn [opts current]
                   (and (contains? opts :if-version)
                        (not= (:if-version opts) current)))
         put!    (fn [name body opts]
                   (async/then
                    (read name)
                    (fn [{:keys [version]}]
                      (if (stale? opts version)
                        {:conflict? true}
                        (let [bumped (str (inc (or (some-> version parse-long)
                                                   0)))]
                          (async/then (set-text (k name) (encoded bumped body))
                                      (fn [_] {:version bumped})))))))]
     (cond-> {:name   (or (:name adapter) "key-value store")
              :fetch  (fn [name opts]
                        (async/then (read name)
                                    #(some-> % (update :body bytes/as
                                                       (:as opts)))))
              :put    (fn [name body opts]
                        (store/exclusively (str lock-id name)
                                           #(put! name body opts)))
              :delete (fn [name]
                        (async/then (delete-text (k name)) (constantly nil)))}
       all-keys
       (assoc :list
              (fn []
                (async/then
                 (all-keys)
                 (fn [found]
                   (let [names (->> found
                                    (filter #(str/starts-with? % prefix))
                                    (map #(subs % (count prefix))))]
                     (async/then
                      (async/all (fn [name]
                                   (async/then (read name)
                                               #(when %
                                                  {:name    name
                                                   :version (:version %)})))
                                 names)
                      #(vec (sort-by :name (filter some? %)))))))))))))
