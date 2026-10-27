(ns dk.simongray.like-minded.cache-test
  "A cache over a store: a value kept and read back, a newer value in place
  of the older, the limits of age and count, caches that share a store,
  and a document that doesn't decode."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.like-minded.cache :as cache]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.like-minded.test-support :refer [run t0 t1 t2 t3]]
            [dk.simongray.wary-fetch.async :as async]))

(defn- names
  "The names of the documents of the `store`, in ClojureScript as a
  promise."
  [store]
  (async/then ((:list store)) #(mapv :name %)))

(deftest the-hash-of-a-key
  (run #(async/then (cache/hashed "a")
                    (fn [h]
                      (is (= "ac8d8342bbb2362d13f0a559a3621bb407011368895164b628a54f7fc33fc43c"
                             h)
                          "the SHA-256 of the key as printed, with its quotes")))
       #(async/then (cache/hashed [:apple "history"])
                    (fn [h]
                      (is (= "0f7219e7de6451fa7c99bcb07f22a9f7ff3dab51925f5e46b6ec1d8ec61d128e"
                             h))))))

(deftest the-values-to-drop
  (let [c       {:max-count 2 :max-seconds 7200}
        entry   (fn [name h at] {:name name :hash h :at at})
        entries [(entry "a1" "a" t0) (entry "a2" "a" t2)
                 (entry "b" "b" t1) (entry "c" "c" t3)]]
    (is (= ["b" "a1"] (cache/dropped c entries t3))
        "the oldest past the count, and the older value of a key")
    (is (= ["a2" "b" "a1"]
           (cache/dropped (dissoc c :max-count) entries
                          #inst "2026-01-01T04:00:01Z"))
        "those past the age")
    (is (= ["a1"] (cache/dropped {} entries t3))
        "without limits, only the older value of a key")
    (is (= ["c" "a2"] (cache/dropped {} entries t1))
        "and those kept later, as by a clock that was ahead, but for the value kept before")))

(deftest keeping-a-value
  (let [s (store/memory)
        c {:store s :prefix "search." :max-count 2 :max-seconds 7200}]
    (run #(cache/put! c [:apple "history"] ["one"] t0)
         #(async/then (cache/fetch! c [:apple "history"] t1)
                      (fn [kept]
                        (is (= {:value ["one"] :at t0} kept))))
         #(async/then (cache/fetch! c [:apple "nothing"] t1)
                      (fn [kept] (is (nil? kept) "a key it never kept")))
         #(async/then (cache/put! c [:apple "history"] ["two"] t1)
                      (fn [put] (is (= {:value ["two"] :at t1} put))))
         #(async/then (cache/fetch! c [:apple "history"] t1)
                      (fn [kept]
                        (is (= ["two"] (:value kept)) "the newer value")))
         #(async/then (names s)
                      (fn [listed]
                        (is (= 1 (count listed)) "the older one is dropped")
                        (is (re-matches #"search\.[0-9a-f]{64}-\d+" (first listed)))))
         #(async/then (cache/fetch! c [:apple "history"]
                                    #inst "2026-01-01T03:00:01Z")
                      (fn [kept]
                        (is (nil? kept) "a value past its age, before it's dropped")))
         #(cache/put! c [:apple "history"] ["ahead"] t3)
         #(async/then (cache/fetch! c [:apple "history"] t2)
                      (fn [kept]
                        (is (nil? kept) "a value kept later, as by a clock that was ahead")))
         #(cache/put! c [:apple "history"] ["three"] t2)
         #(async/then (cache/fetch! c [:apple "history"] t2)
                      (fn [kept]
                        (is (= ["three"] (:value kept)) "which a value kept now replaces"))))))

(deftest the-values-of-several-keys
  (let [s     (store/memory)
        lists (atom 0)
        c     {:store       (update s :list (fn [f] (fn [& args] (swap! lists inc) (apply f args))))
               :prefix      "x."
               :max-seconds 7200}]
    (run #(cache/put! c "a" 1 t0)
         #(cache/put! c "b" 2 t1)
         #(async/then (cache/hashed "odd")
                      (fn [h]
                        ((:put s) (cache/document-name c h t1) "{:a" {})))
         #(reset! lists 0)
         #(async/then (cache/fetch-keys! c ["a" "b" "c" "odd"] t1)
                      (fn [kept]
                        (is (= {"a" {:value 1 :at t0} "b" {:value 2 :at t1}} kept)
                            "but for a key it never kept and a document that doesn't decode")
                        (is (= 1 @lists) "from one listing of the store")))
         #(async/then (cache/fetch-keys! c ["a" "b"] #inst "2026-01-01T02:30:00Z")
                      (fn [kept]
                        (is (= {"b" {:value 2 :at t1}} kept) "nor a value past its age"))))))

(deftest the-limits
  (let [s (store/memory)
        c {:store s :prefix "x." :max-count 2 :max-seconds 7200}]
    (run #(cache/put! c "a" 1 t0)
         #(cache/put! c "b" 2 t1)
         #(cache/put! c "c" 3 t2)
         #(async/then (async/all (fn [k] (cache/fetch! c k t2)) ["a" "b" "c"])
                      (fn [kept]
                        (is (= [nil 2 3] (map :value kept))
                            "the oldest goes past the count")))
         #(async/then (cache/prune! c #inst "2026-01-01T03:00:01Z")
                      (fn [dropped]
                        (is (= 1 (count dropped)) "the one past the age")))
         #(async/then (async/all (fn [k] (cache/fetch! c k t3)) ["b" "c"])
                      (fn [kept]
                        (is (= [nil 3] (map :value kept))))))))

(deftest caches-that-share-a-store
  (let [s        (store/memory)
        searches {:store s :prefix "search."}
        shows    {:store s :prefix "show."}]
    (run #(cache/put! searches "history" [1] t0)
         #(cache/put! shows "history" {:title "History"} t0)
         #((:put s) "library.edn" "{}" {})
         #(async/then (cache/clear! searches)
                      (fn [cleared]
                        (is (= 1 (count cleared)) "its own documents alone")))
         #(async/then (cache/fetch! shows "history" t1)
                      (fn [kept]
                        (is (= {:title "History"} (:value kept))
                            "the other cache keeps its values")))
         #(async/then (names s)
                      (fn [listed]
                        (testing "the store's other documents stay"
                          (is (some #{"library.edn"} listed))))))))

(deftest a-document-that-does-not-decode
  (let [s (store/memory)
        c {:store s}]
    (run #(async/then (cache/hashed "k")
                      (fn [h]
                        ((:put s) (cache/document-name c h t0) "{:a" {})))
         #(async/then (cache/fetch! c "k" t1)
                      (fn [kept] (is (nil? kept)))))))
