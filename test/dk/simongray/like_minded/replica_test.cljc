(ns dk.simongray.like-minded.replica-test
  "The replicas of a device: the lowest free name for each, taken again
  once released, the channels on which each tells the others what it
  saved, and in ClojureScript two replicas of a state in one document,
  which merge their saves and catch up with each other."
  (:require [clojure.test :refer [deftest is #?(:cljs async) #?(:cljs testing)]]
            [dk.simongray.like-minded.replica :as replica]
            #?(:cljs [dk.simongray.like-minded.store :as store])
            #?(:cljs [dk.simongray.mind-meld :as crdt])))

#?(:clj
   (deftest leasing-the-names-of-replicas
     (let [a (replica/lease! "lease-test")
           b (replica/lease! "lease-test")]
       (is (= ["0" "1"] [(:name a) (:name b)]))
       ((:release! a))
       (let [c     (replica/lease! "lease-test")
             other (replica/lease! "other-device")]
         (is (= "0" (:name c)) "the name of a replica that ended is taken again")
         (is (= "0" (:name other)) "each device has names of its own")
         (doseq [l [b c other]]
           ((:release! l)))))))

#?(:cljs
   (defn- leases-the-lowest-free-names
     "A promise of the checks that `lease!` leases the lowest free names of
     a device, and takes the name of a replica that ended again."
     [lease!]
     (-> (js/Promise.all [(lease! "lease-test") (lease! "lease-test")])
         (.then (fn [[a b]]
                  (is (= #{"0" "1"} (set (map :name [a b]))))
                  (let [[zero one] (sort-by :name [a b])]
                    (-> ((:release! zero))
                        (.then #(lease! "lease-test"))
                        (.then (fn [c]
                                 (is (= "0" (:name c)) "the name of a replica that ended is taken again")
                                 (js/Promise.all [((:release! c)) ((:release! one))])))))))
         (.catch #(is false (str %))))))

#?(:cljs
   (deftest leasing-the-names-of-replicas
     (async done
       (.finally (leases-the-lowest-free-names replica/lease!) done))))

#?(:cljs
   (defn- granting-at-once
     "Web Locks that call the callback of a request before the request
     returns, as Node's do, where a browser calls it later."
     []
     (let [held (atom #{})]
       #js {:request (fn [name _opts f]
                       (if (contains? @held name)
                         (js/Promise.resolve (f nil))
                         (do (swap! held conj name)
                             (.finally (js/Promise.resolve (f #js {:name name}))
                                       #(swap! held disj name)))))})))

#?(:cljs
   (deftest leasing-from-locks-that-grant-at-once
     (async done
       (let [locks (granting-at-once)]
         (.finally (leases-the-lowest-free-names #(#'replica/held-by-lock! locks %))
                   done)))))

#?(:clj
   (deftest telling-the-other-replicas
     (let [a     (replica/channel "channel-test")
           b     (replica/channel "channel-test")
           heard (atom {:a [] :b []})
           stop! (replica/listen! b #(swap! heard update :b conj %))]
       (replica/listen! a #(swap! heard update :a conj %))
       (replica/announce! a {:saved "1"})
       (is (= {:a [] :b [{:saved "1"}]} @heard) "every other channel hears it, but not the one that tells it")
       (stop!)
       (replica/announce! a {:saved "2"})
       (is (= [{:saved "1"}] (:b @heard)) "until it stops listening")
       (replica/close! a)
       (replica/close! b))))

#?(:cljs
   (deftest telling-the-other-replicas
     (async done
       (let [a     (replica/channel "channel-test")
             b     (replica/channel "channel-test")
             heard (atom [])
             ;; a channel doesn't keep Node running, so a timer does
             check #(do (is (= [[:b {:saved "1"}]] @heard)
                            "every other channel hears it, but not the one that tells it")
                        (replica/close! a)
                        (replica/close! b)
                        (done))]
         (replica/listen! a #(swap! heard conj [:a %]))
         (replica/listen! b #(swap! heard conj [:b %]))
         (replica/announce! a {:saved "1"})
         (js/setTimeout check 200)))))

#?(:cljs
   (defn- later
     "A promise that settles after `ms` milliseconds, which keeps Node
     running meanwhile."
     [ms]
     (js/Promise. (fn [resolve] (js/setTimeout resolve ms)))))

#?(:cljs
   (deftest two-replicas-of-a-document
     (async done
       (let [s      (assoc (store/memory) :name "replica-document-test")
             schema {:notes {:depth 1} :plays {:depth 2 :count-key :n}}
             kind   (replica/document-kind "state.edn" {:schema schema})
             one    (atom {})
             two    (atom {})
             open   #(replica/open! {:state % :path [:state] :store s :device "phone" :kind kind})
             play   (fn [state]
                      (update state :state
                              #(crdt/write % [:plays "song" (crdt/author %)]
                                           (fn [e] (update e :n (fnil inc 0)))
                                           (js/Date.))))
             plays  #(crdt/sum schema (:state @%) [:plays "song"])]
         (-> (js/Promise.all [(open one) (open two)])
             (.then
              (fn [[r1 r2]]
                (is (not= (:replica (:state @one)) (:replica (:state @two)))
                    "each a replica of its own")
                (swap! one #(-> (update % :state crdt/write [:notes "n1"] (constantly {:text "Milk"})
                                        (js/Date.))
                                play))
                (swap! two play)
                (let [first-save (replica/save! r1)]
                  (is (identical? first-save (replica/save! r1))
                      "a save asked for while another waits its turn is that one")
                  (-> first-save
                      (.then #(replica/save! r2))
                      (.then #(later 100))
                      (.then (fn []
                               (testing "each catches up with what the other saved"
                                 (is (= "Milk" (get-in @two [:state :notes "n1" :text])))
                                 (is (= 2 (plays one) (plays two))
                                     "a count of each replica, so that neither play is lost"))
                               (is (= (crdt/with-replica (:state @one) nil)
                                      (crdt/with-replica (:state @two) nil)))
                               ((:close! r1))
                               ((:close! r2))))))))
             (.catch #(is false (str %)))
             (.finally done))))))
