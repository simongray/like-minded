(ns ^:no-doc dk.simongray.like-minded.shared
  "The strategy of one shared document: every device fetches it, merges,
  and puts it on condition of the version it fetched."
  (:require [dk.simongray.like-minded.diagnostics :as diagnostics]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.like-minded.state :as state]
            [dk.simongray.wary-fetch.async :as async]))

(defn- hands-off
  "The marks for the cursor of a shared document whose reading gave
  `report`, when this device must leave the document alone: :newer? for
  one of a newer format, and :other-kind? for one of another kind of
  state. Writing over either would lose what this device can't read."
  [report]
  (let [ids (set (map :id report))]
    (cond-> {}
      (ids :sync/newer-format) (assoc :newer? true)
      (ids :sync/other-kind)   (assoc :other-kind? true))))

(defn pull!
  "Pull the shared document from `store` at `at`, with the options of the
  state `opts` and their :document, and give its state when its version
  isn't the one in `cursor`.

  When the document is gone from the store, the push writes it again. A
  document that this device must leave alone is marked in the cursor, so
  that the push doesn't write it."
  [{:keys [fetch] :as store} {name :document :as opts} _state
   {:keys [version] :as cursor} at]
  (async/then (fetch name {})
              (fn [doc]
                (cond
                  (nil? doc)
                  {:states []
                   :cursor (cond-> cursor version (dissoc :version :hash))
                   :report []}

                  (= version (:version doc))
                  {:states [] :cursor cursor :report []}

                  :else
                  (let [named [(assoc doc :name name)]
                        {:keys [states report]} (document/states named at opts)]
                    {:states states
                     :cursor (-> cursor
                                 (assoc :version (:version doc))
                                 (dissoc :newer? :other-kind?)
                                 (merge (hands-off report)))
                     :report report})))))

(defn push!
  "Push `state` to the shared document in `store` at `at`, with the options
  of the state `opts` and their :document and :app, on condition of the
  version in `cursor`.

  On a conflict, the document is fetched, merged and put again, up to
  three more times. The merged state comes back for the caller. A
  document of a newer format or of another kind is left alone."
  [{:keys [fetch put] :as store} {name :document :as opts} state cursor at]
  (letfn [(put-conditionally [state {:keys [version] :as cursor} attempts]
            (let [written (document/encode state at opts)]
              (async/then
               (put name written {:if-version version})
               (fn [{:keys [conflict?] :as result}]
                 (cond
                   (not conflict?)
                   {:cursor (assoc cursor
                                   :version (:version result)
                                   :hash    (hash state))}

                   (zero? attempts)
                   {:cursor cursor
                    :report [(diagnostics/describe {:id   :sync/conflict
                                                    :name name})]}

                   :else
                   (merge-and-retry state cursor attempts))))))
          (merge-and-retry [state cursor attempts]
            (async/then
             (fetch name {})
             (fn [doc]
               (let [named  (when doc [(assoc doc :name name)])
                     {:keys [states report]} (document/states named at opts)
                     merged (state/merged state states opts)
                     marks  (hands-off report)
                     cursor (assoc cursor :version (:version doc))]
                 (if (seq marks)
                   {:cursor (merge cursor marks) :report report}
                   (async/then
                    (put-conditionally merged cursor (dec attempts))
                    (fn [retried]
                      (-> retried
                          (assoc :state merged)
                          (update :report #(into (vec report) %))))))))))]
    (if (or (:newer? cursor)
            (:other-kind? cursor)
            (= (hash state) (:hash cursor)))
      {:cursor cursor}
      (put-conditionally state cursor 3))))
