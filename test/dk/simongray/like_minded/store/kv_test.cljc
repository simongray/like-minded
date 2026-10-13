(ns dk.simongray.like-minded.store.kv-test
  "A key-value store of the app as a document store: the store's contract,
  bodies of text and of bytes, the prefix that keeps the documents apart
  from the app's other keys, and adapters that answer at once or, in
  ClojureScript, with promises."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.like-minded.bytes :as bytes]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.like-minded.store.kv :as kv]
            [dk.simongray.like-minded.test-support :refer [run]]
            [dk.simongray.wary-fetch.async :as async]))

(defn- adapter
  "An adapter over the atom `m`, whose functions answer at once, or in
  ClojureScript give promises when `promised?`."
  [m promised?]
  (let [give (if promised?
               #?(:clj identity :cljs #(js/Promise.resolve %))
               identity)]
    {:get    #(give (get @m %))
     :set    #(give (swap! m assoc %1 %2))
     :delete #(give (swap! m dissoc %))
     :keys   #(give (keys @m))
     :name   "test"}))

(defn- documents
  "The steps of a test of the store over the atom `m` of an adapter that
  answers as `promised?` says."
  [m promised?]
  (let [s (kv/store (adapter m promised?))
        call (fn [k & args] (apply (get s k) args))]
    [#(async/then (store/check! s)
                  (fn [{:keys [ok? checks]}]
                    (is ok? (pr-str checks))
                    (is (every? :ok? checks))))
     #(async/then (call :put "a.edn" "one" {})
                  (fn [put]
                    (is (= {:version "1"} put))
                    (is (= "1 text\none" (get @m "like-minded/a.edn")))))
     #(async/then (call :fetch "a.edn" {})
                  (fn [doc] (is (= {:body "one" :version "1"} doc))))
     #(async/then (call :put "a.edn" "two" {:if-version "1"})
                  (fn [put] (is (= {:version "2"} put))))
     #(async/then (call :put "a.edn" "three" {:if-version "1"})
                  (fn [put] (is (= {:conflict? true} put) "a stale version")))
     #(async/then (call :put "a.edn" "three" {:if-version nil})
                  (fn [put] (is (= {:conflict? true} put) "a document that is there already")))
     #(async/then (call :put "c.ciff" (bytes/of [0 255 1]) {})
                  (fn [_] (call :fetch "c.ciff" {:as :bytes})))
     #(async/then (call :fetch "c.ciff" {:as :bytes})
                  (fn [{:keys [body]}]
                    (is (bytes/same? (bytes/of [0 255 1]) body) "bytes as they were put")))
     #(async/then (call :list)
                  (fn [listed]
                    (is (= [{:name "a.edn" :version "2"} {:name "c.ciff" :version "1"}] listed)
                        "the documents alone, without the app's own keys")))
     #(async/then (call :delete "c.ciff")
                  (fn [_] (call :fetch "c.ciff" {})))
     #(async/then (call :fetch "c.ciff" {})
                  (fn [doc] (is (nil? doc))))
     #(is (= "dark" (get @m "theme")) "the app's own key, which the store leaves alone")]))

(deftest a-key-value-store
  (apply run (documents (atom {"theme" "dark"}) false)))

#?(:cljs
   (deftest a-key-value-store-of-promises
     (apply run (documents (atom {"theme" "dark"}) true))))

(deftest a-key-value-store-without-keys
  (let [m (atom {})
        s (kv/store (dissoc (adapter m false) :keys) {:prefix "sync."})]
    (run #(async/then (store/check! s)
                      (fn [{:keys [ok?]}] (is ok? "it reaches a store without a listing too")))
         #(async/then ((:put s) "a.edn" "x" {})
                      (fn [_]
                        (is (nil? (:list s)))
                        (is (contains? @m "sync.a.edn") "under the prefix of its options"))))))

(deftest values-that-keep-no-document
  (is (every? nil? (map kv/decoded [nil "" "no line" "1 base64\n!!" "1 other\nx"])))
  (is (= {:version "3" :body ""} (kv/decoded (kv/encoded "3" "")))))
