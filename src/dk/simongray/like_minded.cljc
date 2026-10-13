(ns dk.simongray.like-minded
  "Syncing a state of mind-meld between a user's devices, through any
  number of backends.

  A backend is a map of a name and two functions, and a third for leave!:

      {:pull  (fn [state cursor at])  ; the states of the other devices
       :push  (fn [state cursor at])  ; writes the merged state
       :leave (fn [state cursor at])  ; writes it once retired, optional
       :name  \"Nextcloud\"}

  A pull returns a map of the :states to merge, the next :cursor and a
  :report. It has :partial? when the states are changes since the cursor
  rather than whole states. A push and a leave return the next :cursor
  and a :report, and a push a :state when it learned more while writing.
  They take the instant of the sync and never read a clock. The function
  backend makes a backend of any store of dk.simongray.like-minded.store.

  The options of the state say what the state is. Give the same ones to a
  backend, to sync!, to reconcile and to the functions of
  dk.simongray.like-minded.document:

  - :schema, the schema of a state of mind-meld, whose functions merge it
  - :crdt, the :merge, :compact and :problem functions of another CRDT,
    which go over those of a :schema
  - :version, the version of the state's format, document/version by
    default
  - :kind, what state the documents hold, e.g. \"my-app/notes\", so that a
    document of another kind is left out
  - :codec, how a document is encoded, document/edn-codec by default

  A sync needs a :schema, or all three functions of a :crdt."
  (:require [dk.simongray.like-minded.crdt :as crdt]
            [dk.simongray.like-minded.diagnostics :as diagnostics]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.like-minded.extras :as extras]
            [dk.simongray.like-minded.per-device :as per-device]
            [dk.simongray.like-minded.shared :as shared]
            [dk.simongray.like-minded.time :as time]
            [dk.simongray.mind-meld :as mind-meld]
            [dk.simongray.wary-fetch.async :as async]))

(defn reconcile
  "The `state` with the documents `fetched` merged in at `at` with `opts`,
  as a map of the :state and a :report, for an app that fetches and puts
  the documents itself.

  The documents are maps of :name and :body. What they tell is
  acknowledged in the device registry, and the tombstones that every
  device has seen are compacted away. The `opts` are the options of the
  state, and :compact, false to keep the tombstones, or else the options
  of the compact function of the CRDT. The document of this device to put
  afterwards is what document/for-device gives."
  [state fetched at opts]
  {:pre [(map? opts)]}
  (let [{:keys [states report]} (document/states fetched at opts)]
    {:state  (crdt/settled (crdt/merged state states opts) states at opts)
     :report report}))

(defn- with-extras
  "The result `pushed` of a push of `state` to `store`, once the extra
  documents of `opts` are put beside it, unless the push ended in a
  conflict. They're those of the state that the push learned, if any."
  [store opts state {:keys [report] :as pushed}]
  (if (some #(= :sync/conflict (:id %)) report)
    pushed
    (async/then (extras/put! store opts (:state pushed state) (:cursor pushed))
                #(assoc pushed :cursor %))))

(defn backend
  "A backend over `store`, a document store of dk.simongray.like-minded.store,
  with the options of the state and the other `opts` below:

  - :strategy, :per-device for a document per device, or :shared for one
    document for all
  - :direction, :pull to read only, :push to write only, or :both
  - :app, the name of the app in the documents written
  - :name, the store's name by default, and distinct for each backend of
    one sync
  - :document, the name of the shared document, state and the extension
    of the codec by default
  - :extra-documents-fn, a function of the state that gives documents to
    write beside those of the devices, as a map of name to body, e.g. for
    other apps to read

  The :strategy is :per-device when the store has :list, and else
  :shared. With a document per device, every device writes its own,
  named after its device id, and reads those of the others. So no two
  devices write the same document, and a folder that any sync client
  mirrors is a complete backend. One shared document is put on condition
  of the version that was fetched, and merged again on a conflict. The
  :direction is :both when the store has :put, and else :pull.

  An extra document is written when it changed, and can't have the
  extension of the codec, since it would be read as a device's."
  [store {:keys [strategy direction] :as opts}]
  {:pre [(map? opts)]}
  ;; throws now for options without a CRDT, rather than at every pull
  (crdt/functions opts)
  (let [strategy      (or strategy (if (:list store) :per-device :shared))
        direction     (or direction (if (:put store) :both :pull))
        opts          (update opts :document
                              #(or % (str "state" (document/extension opts))))
        [pull! push!] (case strategy
                        :per-device [per-device/pull! per-device/push!]
                        :shared     [shared/pull! shared/push!])
        retired       (fn [state]
                        (diagnostics/describe {:id     :sync/retired-device
                                               :device (:device state)}))]
    (when (and (= :per-device strategy) (not (:list store)))
      (throw (ex-info "A store without :list cannot hold a document per device"
                      {:name (:name store)})))
    (when (and (not= :pull direction) (not (:put store)))
      (throw (ex-info "A store without :put can only be pulled from"
                      {:name (:name store)})))
    {:name  (or (:name opts) (:name store))
     :pull  (fn [state cursor at]
              (if (= :push direction)
                {:states [] :cursor cursor :report []}
                (pull! store opts state cursor at)))
     :push  (fn [state cursor at]
              (cond
                (= :pull direction)
                {:cursor cursor}

                (mind-meld/retired? state (:device state))
                {:cursor cursor :report [(retired state)]}

                :else
                (async/then (push! store opts state cursor at)
                            #(with-extras store opts state %))))
     :leave (fn [state cursor at]
              (if (= :pull direction)
                {:cursor cursor}
                (push! store opts state cursor at)))}))

(def default-cadence
  "How many seconds to wait before syncing again:

  - :after-change, after a change to the local state
  - :idle, when nothing changed
  - :after-failure, after each failure in a row, repeating the last wait"
  {:after-change  10
   :idle          900
   :after-failure [30 60 300 900]})

(defn next-sync
  "When to sync again at `at`, after the last sync `step`, with the waits
  of `cadence`.

  The wait depends on how many :failures in a row the step ended with,
  and on whether the local state has :changed? since, which the step of
  sync! doesn't know, so add it. The result is never before `at`, and
  without a step, it's `at`. The `cadence` goes over the defaults. The
  library schedules nothing, so run the sync from a timer of your own."
  ([step at]
   (next-sync step at {}))
  ([{:keys [changed? failures] synced-at :at :as step} at cadence]
   {:pre [(map? cadence)]}
   (let [{:keys [after-change idle after-failure]}
         (merge default-cadence cadence)

         failures (or failures 0)
         wait     (cond
                    (pos? failures)
                    (nth after-failure
                         (min (dec failures) (dec (count after-failure))))

                    changed?
                    after-change

                    :else
                    idle)
         due      (when synced-at
                    (time/later synced-at wait))]
     (time/latest due at))))

(defn- failure
  "The diagnostic of the `backend` whose pull or push failed with `error`."
  [backend error]
  (diagnostics/describe {:id      :sync/backend-failed
                         :backend (:name backend)
                         :reason  (async/explain error)}))

(defn- pulled
  "What the pull `results` of the `backends` add up to, merged into `state`
  at `at` and compacted, by the CRDT of `opts`.

  The results are one per backend, in order, each with a :value or an
  :error. The sum is a map of these:

  - :state, with every pulled state merged in, the whole states
    acknowledged, and the tombstones compacted
  - :ok, the backends that didn't fail, each paired with its result
  - :report, the reports of the pulls, and a diagnostic for each backend
    that failed

  A backend whose states are partial, the changes of a service since a
  cursor, says so with :partial?, and none of them are acknowledged."
  [state backends results at opts]
  (let [answers (map vector backends results)
        ok      (for [[backend {:keys [value error]}] answers
                      :when (not error)]
                  [backend value])
        failed  (for [[backend {:keys [error]}] answers
                      :when error]
                  (failure backend error))
        states  (mapcat (comp :states second) ok)
        whole   (mapcat (fn [[_ result]]
                          (when-not (:partial? result)
                            (:states result)))
                        ok)]
    {:state  (crdt/settled (crdt/merged state states opts) whole at opts)
     :ok     (vec ok)
     :report (-> []
                 (into (mapcat (comp :report second)) ok)
                 (into failed))}))

(defn- finished
  "The result of a step from the pulls `so-far`, the push `results` and the
  last `cursors`, settled at `at` by the CRDT of `opts`.

  There is one push result per backend that didn't fail. The state has
  what a push learned merged in, acknowledged and compacted, and the new
  cursors are by backend name. The report is that of both the pulls and
  the pushes, with a diagnostic for each backend whose push failed."
  [{:keys [state ok report] :as so-far} cursors results at opts]
  (let [pushes  (map (fn [[backend pull] {:keys [value error]}]
                       [backend pull value error])
                     ok
                     results)
        learned (keep (fn [[_ _ push]] (:state push)) pushes)
        cursor  (fn [m [backend pull push error]]
                  (assoc m (:name backend) (:cursor (if error pull push))))
        failed  (for [[backend _ _ error] pushes
                      :when error]
                  (failure backend error))]
    {:state   (if (seq learned)
                (crdt/settled (crdt/merged state learned opts)
                               learned at opts)
                state)
     :cursors (reduce cursor (or cursors {}) pushes)
     :report  (-> report
                  (into (mapcat (fn [[_ _ push]] (:report push))) pushes)
                  (into failed))}))

(defonce ^{:no-doc true
            :doc    "The devices whose step is running in this process."}
  running
  (atom #{}))

(defn- in-process
  "What `step-fn` gives, unless a step of `device` is running in this
  process, when it's what `busy-fn` gives."
  [device busy-fn step-fn]
  (let [[before _] (swap-vals! running conj device)
        release    #(swap! running disj device)]
    (if (contains? before device)
      #?(:clj (busy-fn) :cljs (js/Promise.resolve (busy-fn)))
      #?(:clj  (try
                 (step-fn)
                 (finally
                   (release)))
         :cljs (-> (js/Promise.resolve nil)
                   (.then (fn [_] (step-fn)))
                   (.finally release))))))

;; Web Locks API, W3C Working Draft of 24 September 2025, 2.4 and 3.2.1: a
;; lock is held until the promise of its callback settles, and with
;; ifAvailable, the callback gets null when another holds it
(defn- guarded
  "What `step-fn` gives for a step of the device of `state`, unless one of
  its steps is running, when it's what `busy-fn` gives. In a browser, the
  steps of the other tabs of the page count too."
  [state busy-fn step-fn]
  (let [device (:device state)]
    #?(:clj  (in-process device busy-fn step-fn)
       :cljs (if-let [^js locks (some-> js/globalThis .-navigator .-locks)]
               (.request locks
                         (str "like-minded/" device)
                         #js {:ifAvailable true}
                         (fn [lock]
                           (if lock (step-fn) (busy-fn))))
               (in-process device busy-fn step-fn)))))

(defn- step!
  "A step of sync! of `state` through `backends` at `at`, with the `opts`
  of sync!, while no other step of its device runs."
  [state backends at {:keys [cursors failures] :as opts}]
  (let [problem-of (:problem (crdt/functions opts))
        pull!      (fn [{:keys [pull name]}]
                     (async/attempt #(pull state (get cursors name) at)))]
    (async/then
     (async/all (map pull! backends))
     (fn [results]
       (let [so-far    (pulled state backends results at opts)
             merged    (:state so-far)
             problem   (problem-of merged)
             push!     (fn [[{:keys [push]} pull]]
                         (if problem
                           {:cursor (:cursor pull)}
                           (push merged (:cursor pull) at)))
             attempt!  (fn [ok]
                         (async/attempt #(push! ok)))
             malformed {:id :sync/malformed-state :error problem}
             failed?   #(some (comp #{:sync/backend-failed} :id) %)
             done      (fn [pushes]
                         (let [step (cond-> (finished so-far cursors pushes
                                                      at opts)
                                      problem
                                      (update :report conj
                                              (diagnostics/describe
                                               malformed)))]
                           (assoc step
                                  :at       at
                                  :failures (if (failed? (:report step))
                                              (inc (or failures 0))
                                              0))))]
         (async/then (async/all (map attempt! (:ok so-far)))
                     done))))))

(defn- skipped
  "The step of `state` at `at` that overlapped another, which did nothing,
  with the :cursors and :failures of `opts`."
  [state at {:keys [cursors failures]}]
  {:state    state
   :cursors  (or cursors {})
   :at       at
   :failures (or failures 0)
   :report   [(diagnostics/describe {:id :sync/overlapping-step})]})

(defn sync!
  "Sync `state` through `backends` in one step with the `opts` below: pull
  from every backend at once, merge, and push the merged state to each.

  It returns this, in ClojureScript as a promise:

      {:state    merged
       :cursors  {\"Nextcloud\" {…} …}  ; by backend name
       :at       #inst \"…\"           ; the instant of the step
       :failures 0                    ; the steps in a row in which a
                                      ; backend failed
       :report   [...]}               ; what reading and writing found

  In ClojureScript, each function of a backend can return a value or a
  promise. The `opts` are the options of the state, whose CRDT merges,
  and these:

  - :at, the instant of the step, the time of the clock by default. A
    document written more than a minute after it reports
    :sync/clock-skew, so a fixed :at in the past, e.g. in a test or a
    replay, reports it for the document of every other device.
  - :cursors and :failures, those of the last step
  - :compact, false to keep the tombstones that every device has seen, or
    else the options of the compact function of the CRDT

  A backend whose pull fails isn't pushed to and keeps its cursor, and one
  whose push fails keeps the cursor of its pull. The report has a
  :sync/backend-failed diagnostic for each, with its :backend and the
  :reason. The state is only ever merged into, so a failed step loses
  nothing. A state in which the :problem of the CRDT finds a problem isn't
  pushed, since the other devices would leave it out, and the report has
  :sync/malformed-state.

  One step of a device runs at a time, since a step that overlaps another
  can write an older state after a newer one. A step that begins while
  another is running does nothing, and its report has
  :sync/overlapping-step. What the user did during a step isn't in its
  :state, so don't put the result in place of the state, but give it to
  finish with the state as it is by then.

  The state needs a :device that is a plain file name, and the backends
  need distinct names, since the cursors are kept by name. Either
  mistake throws."
  [state backends {:keys [at] :as opts}]
  {:pre [(map? opts)]}
  (document/checked-device state)
  (when-let [twice (seq (for [[name n] (frequencies (map :name backends))
                              :when (< 1 n)]
                          name))]
    (throw (ex-info (str "The backends of one sync need distinct names, "
                         "and these are not: " (pr-str twice))
                    {:names twice})))
  (let [at (or at (time/now))]
    (guarded state
             #(skipped state at opts)
             #(step! state backends at opts))))

(defn finish
  "The `state` as it is by now, with the :state of the `step` of sync!
  merged in by the CRDT of `opts`, the options of the step, and compacted
  at the step's :at as the step was.

  What the user did during the step is in `state` alone, so the result
  keeps both. The merge brings back the tombstones that the step dropped,
  which is why it's compacted again."
  [state step {:keys [compact] :as opts}]
  {:pre [(map? opts)]}
  (let [{merge-fn :merge compact-fn :compact} (crdt/functions opts)
        merged (merge-fn state (:state step))]
    (if (false? compact)
      merged
      (compact-fn merged (:at step) (or compact {})))))

(defn- left
  "The result of leave! of the `retired` state at `at` through `backends`,
  whose leave `results` are in order, with the :cursors and :failures of
  `opts`."
  [retired backends results at {:keys [cursors failures]}]
  (let [answers (map vector backends results)
        failed  (for [[backend {:keys [error]}] answers
                      :when error]
                  (failure backend error))]
    {:state    retired
     :cursors  (reduce (fn [m [{:keys [name]} {:keys [value error]}]]
                         (assoc m name (if error
                                         (get cursors name)
                                         (:cursor value))))
                       (or cursors {})
                       answers)
     :at       at
     :failures (if (seq failed) (inc (or failures 0)) 0)
     :report   (-> []
                   (into (mapcat (comp :report :value)) results)
                   (into failed))}))

(defn leave!
  "Retire the device of `state` and write that through each of `backends`
  once more, with the `opts` of sync!, e.g. when the user turns sync off.

  The other devices read it at their next step, after which they no longer
  wait for this one to compact, and the first of them deletes its
  document. It returns what sync! does, with the state retired, and a
  backend without a :leave writes nothing. The device can't sync again,
  since a retired device can't come back, so give the state a new device
  id for that."
  [state backends {:keys [at] :as opts}]
  {:pre [(map? opts)]}
  (document/checked-device state)
  (let [at      (or at (time/now))
        retired (mind-meld/retire state (:device state) at)
        write!  (fn [{:keys [leave name]}]
                  (async/attempt
                   #(if leave
                      (leave retired (get (:cursors opts) name) at)
                      {:cursor (get (:cursors opts) name)})))]
    (guarded state
             #(skipped state at opts)
             #(async/then (async/all (map write! backends))
                          (fn [results]
                            (left retired backends results at opts))))))
