(ns dk.simongray.like-minded.replica
  "The replicas of a device that write its state at the same time and
  share its storage, e.g. the tabs and workers of a web app in one
  browser: a name for each, held while it runs, and the notices of what
  each one saves.

  A replica takes its name with lease! and gives it to mind-meld's
  with-replica. Before it writes the shared storage, it holds
  store/exclusively and catches up with what the others saved there with
  mind-meld's rebase. After, it tells the others on a channel, where they
  listen.

  In a browser, a name is a Web Lock, and a channel is a BroadcastChannel,
  which reaches the tabs and workers of the site. Elsewhere, the names and
  the channels reach the replicas in the same process. In ClojureScript,
  lease! gives a promise."
  #?(:cljs (:require [cljs.reader :as reader])))

(defonce ^{:doc "The names that the replicas in this process hold, as a
  set of names by the name of their device, where the platform has no Web
  Locks."}
  held
  (atom {}))

(defn- lock-name
  "The name of the Web Lock of the replica `n` of `name`."
  [name n]
  (str "dk.simongray.like-minded.replica/" name "/" n))

(defn- held-here!
  "The lowest name of a replica of `name` that no replica in this process
  holds, now held, as a map of :name and :release!."
  [name]
  (let [free?   #(not (contains? (get @held name) %))
        n       (str (first (filter free? (map str (range)))))
        [old _] (swap-vals! held update name (fnil conj #{}) n)]
    (if (contains? (get old name) n)
      (recur name)
      {:name     n
       :release! #(swap! held update name disj n)})))

;; Web Locks API, W3C Working Draft of 24 September 2025, sections 2.6 and
;; 3.2.1: with ifAvailable, the callback gets null when another holds the
;; lock, and a lock goes when the agent that holds it ends, e.g. when its
;; tab closes or crashes.
#?(:cljs
   (defn- held-by-lock!
     "A promise of the lowest name of a replica of `name` whose Web Lock no
     other context holds, now held through the Web Locks `locks`, as a map
     of :name and :release!."
     [^js locks name]
     (js/Promise.
      (fn [resolve reject]
        (letfn [(attempt [n]
                  (let [release (atom nil)
                        held    (js/Promise. #(reset! release %))
                        granted (atom nil)
                        request (.request locks (lock-name name n)
                                          #js {:ifAvailable true}
                                          (fn [lock]
                                            (if lock
                                              (do (resolve @granted)
                                                  held)
                                              (do (attempt (inc n))
                                                  nil))))]
                    ;; the request settles once the lock has gone
                    (reset! granted {:name     (str n)
                                     :release! #(do (@release nil) request)})
                    (.catch request reject)))]
          (attempt 0))))))

(defn lease!
  "The lowest name of a replica of the device `name` that no other replica
  holds, from \"0\" up, held until its context ends, or until :release! is
  called, as a map of :name and :release!, in ClojureScript as a promise.
  In ClojureScript, :release! gives a promise that settles once the name
  is free.

  The names of the replicas that ended are taken again, so that the
  device has no more of them than it ever ran at once. Hold the lease for
  as long as the context writes as the replica, and take another before
  it writes again once it's released. A held lease keeps a page out of
  the back/forward cache of Chrome."
  [name]
  #?(:clj  (held-here! name)
     :cljs (if-let [locks (some-> js/globalThis .-navigator .-locks)]
             (held-by-lock! locks name)
             (js/Promise.resolve
              (update (held-here! name) :release!
                      (fn [release!] #(js/Promise.resolve (release!))))))))

(defonce ^{:doc "The channels in this process by name, each a set of
  atoms of its listeners, where the platform has no BroadcastChannel."}
  channels
  (atom {}))

(defn channel
  "A channel of the replicas of the device `name`, on which each tells the
  others what it saved. A message reaches every other channel of the name,
  but not the one that sends it, so make one channel in each context."
  [name]
  #?(:clj  (let [ch {:name name :listeners (atom #{})}]
             (swap! channels update name (fnil conj #{}) ch)
             ch)
     :cljs (if (exists? js/BroadcastChannel)
             (let [broadcast (js/BroadcastChannel. name)]
               ;; in Node, a channel would keep the process running
               (some-> ^js broadcast .-unref (.call broadcast))
               {:name name :broadcast broadcast})
             (let [ch {:name name :listeners (atom #{})}]
               (swap! channels update name (fnil conj #{}) ch)
               ch))))

;; HTML Living Standard, 9.5 Broadcasting to other browsing contexts: the
;; message reaches the other BroadcastChannel objects of the name, in the
;; documents that are fully active and the workers, but no page in the
;; back/forward cache, so a replica that comes back from it reads what it
;; missed from the storage.
(defn announce!
  "Tell the other channels of the name of the channel `ch` the `message`,
  data that EDN can print. In ClojureScript, they hear it later."
  [{:keys [name broadcast] :as ch} message]
  #?(:clj  (doseq [other (get @channels name)
                   :when (not= ch other)
                   f     @(:listeners other)]
             (f message))
     :cljs (if broadcast
             (.postMessage ^js broadcast (pr-str message))
             (doseq [other (get @channels name)
                     :when (not (identical? ch other))
                     f     @(:listeners other)]
               (js/setTimeout #(f message) 0)))))

(defn listen!
  "Call `f` with each message that another channel tells the channel `ch`,
  and give a function that stops it."
  [{:keys [listeners broadcast]} f]
  #?(:clj  (do (swap! listeners conj f)
               #(swap! listeners disj f))
     :cljs (if broadcast
             (let [on-message (fn [^js e]
                                (when-let [message (try
                                                     (reader/read-string (.-data e))
                                                     (catch :default _ nil))]
                                  (f message)))]
               (.addEventListener ^js broadcast "message" on-message)
               #(.removeEventListener ^js broadcast "message" on-message))
             (do (swap! listeners conj f)
                 #(swap! listeners disj f)))))

(defn close!
  "Close the channel `ch`, which then neither tells nor hears."
  [{:keys [name broadcast] :as ch}]
  #?(:clj  (swap! channels update name disj ch)
     :cljs (if broadcast
             (.close ^js broadcast)
             (swap! channels update name disj ch))))

(comment
  (def tab-1 (lease! "phone"))
  (def tab-2 (lease! "phone"))
  [(:name tab-1) (:name tab-2)]
  ;; => ["0" "1"]
  ((:release! tab-1))
  (:name (lease! "phone"))
  ;; => "0"

  (def a (channel "phone"))
  (def b (channel "phone"))
  (def stop! (listen! b prn))
  (announce! a {:saved "1"})
  (stop!)
  #_.)
