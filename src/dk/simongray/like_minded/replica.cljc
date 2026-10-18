(ns dk.simongray.like-minded.replica
  "The replicas of a device that write its state at the same time and
  share its storage, e.g. the tabs and workers of a web app in one
  browser: a name for each, held while it runs, the notices of what each
  one saves, and in ClojureScript, the state of one in an atom, which
  saves and catches up with what the others save.

  open! leases a replica its name, puts its state in an atom, and gives
  the replica to save! and catch-up! with. A save catches up with what the
  others saved first, and tells them on a channel of the store, and each
  catches up as it hears that, and in a page also when the page is
  visible again. The saves and catch-ups of a store take turns, and a
  save asked for while another waits its turn is that one. How the state
  is read, written and caught up is up to its kind, e.g. document-kind
  for a state of mind-meld in one document.

  In a browser, a name is a Web Lock, and a channel is a BroadcastChannel,
  which reaches the tabs and workers of the site. Elsewhere, the names and
  the channels reach the replicas in the same process. In ClojureScript,
  lease! gives a promise."
  #?(:cljs (:require [cljs.reader :as reader]
                     [dk.simongray.like-minded.document :as document]
                     [dk.simongray.like-minded.store :as store]
                     [dk.simongray.mind-meld :as mind-meld])))

(defonce ^{:doc "The names that the replicas in this process hold, as a
  set of names by the name of their device, where the platform has no Web
  Locks."}
  held
  (atom {}))

#?(:cljs
   (defn- lock-name
     "The name of the Web Lock of the replica `n` of `name`."
     [name n]
     (str "dk.simongray.like-minded.replica/" name "/" n)))

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
                        request (atom nil)
                        ;; the request settles once the lock has gone
                        granted {:name     (str n)
                                 :release! #(do (@release nil) @request)}]
                    ;; Node calls back before the request returns, and a
                    ;; browser later
                    (reset! request
                            (.request locks (lock-name name n)
                                      #js {:ifAvailable true}
                                      (fn [lock]
                                        (if lock
                                          (do (resolve granted)
                                              held)
                                          (do (attempt (inc n))
                                              nil)))))
                    (.catch @request reject)))]
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
  [{:keys [name] :as ch} message]
  #?(:clj  (doseq [other (get @channels name)
                   :when (not= ch other)
                   f     @(:listeners other)]
             (f message))
     :cljs (if-let [broadcast (:broadcast ch)]
             (.postMessage ^js broadcast (pr-str message))
             (doseq [other (get @channels name)
                     :when (not (identical? ch other))
                     f     @(:listeners other)]
               (js/setTimeout #(f message) 0)))))

(defn listen!
  "Call `f` with each message that another channel tells the channel `ch`,
  and give a function that stops it."
  [{:keys [listeners] :as ch} f]
  #?(:clj  (do (swap! listeners conj f)
               #(swap! listeners disj f))
     :cljs (if-let [broadcast (:broadcast ch)]
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
  [{:keys [name] :as ch}]
  #?(:clj  (swap! channels update name disj ch)
     :cljs (if-let [broadcast (:broadcast ch)]
             (.close ^js broadcast)
             (swap! channels update name disj ch))))

(defn channel-name
  "The name of the channels on which the replicas that share `store` tell
  each other that they saved, by the store's name."
  [store]
  (str "dk.simongray.like-minded.replica/" (:name store)))

#?(:cljs
   (defonce ^{:doc "The promise of the last save or catch-up of each store,
     by its name, which the next one waits for."}
     turns
     (atom {})))

#?(:cljs
   (defn- in-turn!
     "A promise of what `f` gives, called once the saves and catch-ups of
     `store` before it are done."
     [store f]
     (let [k (:name store)
           p (.then (get @turns k (js/Promise.resolve nil))
                    (fn [_] (f))
                    (fn [_] (f)))]
       (swap! turns assoc k (.catch p (constantly nil)))
       p)))

(defn state-of
  "The state of the `replica` as it is now."
  [{:keys [state path]}]
  (get-in @state path))

#?(:cljs
   (defn save!
     "Save the state of the `replica` to its store, once the saves and
     catch-ups before it are done, as a promise of what the :save of its
     kind gives, and tell the others on the channel.

     The state as it is by then takes what the save caught up with from
     the others. A save asked for while another waits its turn is that one,
     since it writes the state as it is when it starts."
     [{:keys [state path store kind base waiting channel] :as replica}]
     (or @waiting
         (reset! waiting
                 (in-turn! store
                           (fn []
                             (reset! waiting nil)
                             (when-let [given (state-of replica)]
                               (.then ((:save kind) store given @base)
                                      (fn [{written :state :keys [version] :as result}]
                                        (when written
                                          (reset! base {:state written :version version})
                                          (swap! state update-in path
                                                 #((:rebase kind) % given written))
                                          (announce! channel {:saved   (:name store)
                                                              :version version}))
                                        result)))))))))

#?(:cljs
   (defn catch-up!
     "Catch the state of the `replica` up with what another saved in its
     store since this one last read or wrote it, once the saves and
     catch-ups before it are done, as a promise of the state that the other
     saved, or nil."
     [{:keys [state path store kind base] :as replica}]
     (in-turn! store
               (fn []
                 (when-let [current (state-of replica)]
                   (.then ((:read-saved kind) store current @base)
                          (fn [{other :state :keys [version]}]
                            (when other
                              (let [old (:state @base)]
                                (reset! base {:state other :version version})
                                (swap! state update-in path
                                       #((:rebase kind) % old other))))
                            other)))))))

;; Page Lifecycle: a page that is hidden or frozen, or in the back/forward
;; cache, gets no message from a BroadcastChannel, so it catches up when
;; it's visible again, or shown from the cache.
;; https://developer.chrome.com/docs/web-platform/page-lifecycle-api
#?(:cljs
   (defn- follow-page!
     "Catch the `replica` up whenever the page is visible again or shown
     from the back/forward cache, where there's a page, and give a function
     that stops it."
     [replica]
     (if (exists? js/document)
       (let [visible (fn [_]
                       (when (= "visible" (.-visibilityState js/document))
                         (catch-up! replica)))
             shown   (fn [^js e]
                       (when (.-persisted e)
                         (catch-up! replica)))]
         (.addEventListener js/document "visibilitychange" visible)
         (.addEventListener js/window "pageshow" shown)
         (fn []
           (.removeEventListener js/document "visibilitychange" visible)
           (.removeEventListener js/window "pageshow" shown)))
       (constantly nil))))

#?(:cljs
   (defn open!
     "A promise of a replica of `device`: its state in `store`, read by the
     `kind` and put at `path` in the atom `state`, as the replica that this
     context leases, with the `opts` below.

     - :state, :path and :store, as above
     - :device, the id of the device
     - :kind, a map of the functions that read, write and catch up the
       state, as document-kind gives them:

           {:open       (fn [store {:keys [device replica]}])
            :save       (fn [store state base])
            :read-saved (fn [store state base])
            :rebase     (fn [state base other])}

       Each but :rebase gives a promise. :open gives a map of the :state as
       written by the replica of that name, and its :version in the store,
       and :save a map of the :state that it wrote, after catching up with
       what another wrote since `base`, and the :version. :read-saved gives
       the same of what another wrote since `base`, or nil, and :rebase the
       `state` with `other` caught up with, as mind-meld's rebase does,
       where `base` is the state that this one last read or wrote.

     The replica is a map to give save! and catch-up!, with a :close! that
     stops its catching up and gives back the lease. It catches up whenever
     another tells that it saved, and in a page also when the page is
     visible again."
     [{:keys [state path store device kind] :as opts}]
     (.then
      (lease! device)
      (fn [{:keys [name release!]}]
        (let [ch (channel (channel-name store))]
          (.then
           ((:open kind) store {:device device :replica name})
           (fn [{opened :state :keys [version]}]
             (swap! state assoc-in path opened)
             (let [replica  {:state   state
                             :path    path
                             :store   store
                             :kind    kind
                             :channel ch
                             :base    (atom {:state opened :version version})
                             :waiting (atom nil)}
                   stop!    (listen! ch (fn [_] (catch-up! replica)))
                   unfollow (follow-page! replica)]
               (assoc replica :close! (fn []
                                        (stop!)
                                        (unfollow)
                                        (close! ch)
                                        (release!)))))))))))

#?(:cljs
   (defn document-kind
     "The kind of a replica whose state is a state of mind-meld in the
     document `name` of its store, with the `opts` below, for open!.

     - :schema, the schema of the state in mind-meld, which catches it up
     - :codec, how the document is encoded, EDN by default, e.g. the
       faster one of dk.simongray.like-minded.transit

     A save writes the state while it holds store/exclusively, after
     catching up with what another wrote since this replica last read or
     wrote it, and the document holds the state without its replica."
     [name {:keys [schema codec] :or {codec document/edn-codec}}]
     (let [decoded (fn [{:keys [body version]}]
                     (let [s ((:decode codec) body)]
                       (when-let [problem (mind-meld/problem schema s)]
                         (throw (ex-info (str "The saved state can't be merged: "
                                              problem)
                                         {:name name})))
                       {:state s :version version}))
           read    (fn [store]
                     (.then (js/Promise.resolve ((:fetch store) name {}))
                            #(some-> % decoded)))]
       {:open       (fn [store {:keys [device replica]}]
                      (.then (read store)
                             (fn [saved]
                               {:state   (-> (or (:state saved) {})
                                             (assoc :device device)
                                             (mind-meld/with-replica replica))
                                :version (:version saved)})))
        :save       (fn [store state base]
                      (store/exclusively
                       (str (channel-name store) "/" name)
                       #(.then (read store)
                               (fn [saved]
                                 (let [state (if (and saved (not= (:version saved)
                                                                  (:version base)))
                                               (mind-meld/rebase schema state
                                                                 (:state base)
                                                                 (:state saved))
                                               state)
                                       body  ((:encode codec)
                                              (mind-meld/with-replica state nil))]
                                   (.then (js/Promise.resolve
                                           ((:put store) name body
                                            {:if-version (:version saved)}))
                                          (fn [{:keys [version conflict?]}]
                                            (when conflict?
                                              (throw (ex-info "Another wrote the document without the lock"
                                                              {:name name})))
                                            {:state state :version version})))))))
        :read-saved (fn [store _state base]
                      (.then (read store)
                             (fn [saved]
                               (when (and saved (not= (:version saved) (:version base)))
                                 saved))))
        :rebase     (fn [state base other]
                      (mind-meld/rebase schema state base other))})))

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
