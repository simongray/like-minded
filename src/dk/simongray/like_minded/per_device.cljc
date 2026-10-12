(ns ^:no-doc dk.simongray.like-minded.per-device
  "The strategy of a document per device: each device writes its own
  document, named after its device id, and reads those of the others."
  (:require [dk.simongray.like-minded.diagnostics :as diagnostics]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.wary-fetch.async :as async]))

(defn pull!
  "Pull the documents from `store` that changed since `cursor`, for `state`
  at `at`, and give their states, read with the options of the state
  `opts`.

  The documents are those of the listing with the extension of the codec,
  fetched at once, and one without a version there is always fetched. The
  report notes when this device's own document changed under it, and
  when the document is gone from the store, the push writes it again."
  [{:keys [list fetch] :as store} opts state {:keys [versions] :as cursor} at]
  (async/then
   (list)
   (fn [listing]
     (let [own       (document/name-of (:device state) opts)
           own?      #(= own (:name %))
           seen?     #(and (some? (:version %))
                           (= (:version %) (get versions (:name %))))
           documents (filter #(document/device-document? % opts) listing)
           stale     (remove seen? documents)
           known?    (contains? versions own)
           gone?     (and known?
                          (not-any? own? documents))
           named     (fn [name doc]
                       (some-> doc (assoc :name name)))
           fetch!    (fn [{:keys [name]}]
                       (async/attempt
                        #(async/then (fetch name {}) (partial named name))))]
       (async/then
        (async/all (map fetch! stale))
        (fn [results]
          (when-let [error (some :error results)]
            (throw error))
          (let [fetched  (keep :value results)
                {:keys [states report]} (document/states fetched at opts)
                version  (juxt :name :version)
                listed   (-> (into {} (map version) documents)
                             (into (map version) fetched))
                changed? (and known?
                              (some #(and (own? %) (some? (:version %)))
                                    stale))
                shared   {:id     :sync/shared-device
                          :name   own
                          :device (:device state)}]
            {:states states
             :cursor (cond-> (assoc cursor :versions listed)
                       gone? (dissoc :hash))
             :report (cond-> report
                       changed? (conj (diagnostics/describe shared)))})))))))

(defn push!
  "Push `state` to `store` at `at`, with the options of the state `opts`
  and their :app, and give a map of the cursor that follows `cursor`.

  It writes this device's document when the state changed since the
  cursor, and deletes the documents of the other retired devices. That of
  a device that retires itself stays, for the others to read."
  [{:keys [put delete] :as store} opts state {:keys [versions] :as cursor} at]
  (let [own     (document/for-device state at opts)
        written (hash state)
        named   #(document/name-of % opts)
        listed? #(contains? versions (named %))
        retired (->> (keys (:retired state))
                     (remove #{(:device state)})
                     (filter listed?))
        delete! #(delete (named %))]
    (async/then
     (when (not= written (:hash cursor))
       (put (:name own) (:body own) {}))
     (fn [result]
       (async/then
        (when delete
          (async/all delete! retired))
        (fn [_]
          (let [left (reduce dissoc versions (map named retired))]
            {:cursor (cond-> (assoc cursor :hash written :versions left)
                       result
                       (assoc-in [:versions (:name own)]
                                 (:version result)))})))))))
