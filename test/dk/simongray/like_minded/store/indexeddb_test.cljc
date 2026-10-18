(ns dk.simongray.like-minded.store.indexeddb-test
  "IndexedDB as a document store, in a browser: the store's contract,
  bodies of bytes and a cache over it. Node has no IndexedDB, so there the
  tests check nothing."
  (:require [clojure.test :refer [deftest is]]
            #?@(:cljs [[dk.simongray.like-minded.bytes :as bytes]
                       [dk.simongray.like-minded.cache :as cache]
                       [dk.simongray.like-minded.store.indexeddb :as indexeddb]
                       [dk.simongray.like-minded.test-support :refer [run t0 t1]]
                       [dk.simongray.wary-fetch.async :as async]])))

#?(:cljs
   (defn- fresh-name
     "The name of a database that no test used before."
     []
     (str "like-minded-test-" (random-uuid))))

#?(:cljs
   (defn- forget!
     "Delete the database named `name`."
     [name]
     (js/Promise. (fn [resolve _]
                    (set! (.-onsuccess (.deleteDatabase js/indexedDB name))
                          resolve)))))

(deftest an-indexeddb-store
  #?(:clj  (is true)
     :cljs (if-not (exists? js/indexedDB)
             (is true "no IndexedDB here")
             (let [name    (fresh-name)
                   s       (indexeddb/store name)
                   version (atom nil)]
               (run #(async/then ((:list s)) (fn [docs] (is (= [] docs))))
                    #(async/then ((:fetch s) "a.edn" {}) (fn [doc] (is (nil? doc))))
                    #(async/then ((:put s) "a.edn" "one" {})
                                 (fn [{v :version}] (reset! version v)))
                    #(async/then ((:fetch s) "a.edn" {})
                                 (fn [doc] (is (= {:body "one" :version @version} doc))))
                    #(async/then ((:put s) "a.edn" "two" {:if-version "stale"})
                                 (fn [answer] (is (= {:conflict? true} answer))))
                    #(async/then ((:put s) "b.edn" "x" {:if-version nil})
                                 (fn [answer] (is (:version answer) "nil for a new document")))
                    #(async/then ((:put s) "c.ciff" (bytes/of [0 255 1]) {})
                                 (fn [_] ((:fetch s) "c.ciff" {:as :bytes})))
                    #(async/then ((:fetch s) "c.ciff" {:as :bytes})
                                 (fn [doc] (is (bytes/same? (bytes/of [0 255 1]) (:body doc)))))
                    #(async/then ((:list s))
                                 (fn [docs] (is (= ["a.edn" "b.edn" "c.ciff"] (map :name docs)))))
                    #(async/then ((:delete s) "b.edn") (fn [_] ((:list s))))
                    #(async/then ((:list s))
                                 (fn [docs] (is (= ["a.edn" "c.ciff"] (map :name docs)))))
                    #(forget! name)
                    #(async/then ((:list s))
                                 (fn [docs]
                                   (is (= [] docs) "open again, after a deletion")))
                    #(forget! name))))))

(deftest a-cache-in-indexeddb
  #?(:clj  (is true)
     :cljs (if-not (exists? js/indexedDB)
             (is true "no IndexedDB here")
             (let [name (fresh-name)
                   c    {:store     (indexeddb/store name)
                         :prefix    "search."
                         :max-count 1}]
               (run #(cache/put! c [:apple "history"] ["one"] t0)
                    #(async/then (cache/fetch! c [:apple "history"] t0)
                                 (fn [kept] (is (= ["one"] (:value kept)))))
                    #(cache/put! c [:apple "music"] ["two"] t1)
                    #(async/then (cache/fetch! c [:apple "history"] t1)
                                 (fn [kept]
                                   (is (nil? kept) "dropped past the count")))
                    #(async/then (cache/clear! c)
                                 (fn [cleared] (is (= 1 (count cleared)))))
                    #(forget! name))))))
