(ns dk.simongray.like-minded.replica-test
  "The replicas of a device: the lowest free name for each, taken again
  once released, and the channels on which each tells the others what it
  saved."
  (:require [clojure.test :refer [deftest is #?(:cljs async)]]
            [dk.simongray.like-minded.replica :as replica]))

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
   (deftest leasing-the-names-of-replicas
     (async done
       (-> (js/Promise.all [(replica/lease! "lease-test") (replica/lease! "lease-test")])
           (.then (fn [[a b]]
                    (is (= #{"0" "1"} (set (map :name [a b]))))
                    (let [[zero one] (sort-by :name [a b])]
                      (-> ((:release! zero))
                          (.then #(replica/lease! "lease-test"))
                          (.then (fn [c]
                                   (is (= "0" (:name c)) "the name of a replica that ended is taken again")
                                   (js/Promise.all [((:release! c)) ((:release! one))])))))))
           (.catch #(is false (str %)))
           (.finally done)))))

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
