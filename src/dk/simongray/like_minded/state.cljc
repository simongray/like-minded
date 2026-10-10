(ns ^:no-doc dk.simongray.like-minded.state
  "The CRDT of the states that a sync merges, from the options of the
  state: its functions, the merge of many states, and the settling of a
  step."
  (:require [dk.simongray.mind-meld :as crdt]))

(defn- schema-crdt
  "The functions of the CRDT of mind-meld for the states of `schema`."
  [schema]
  {:merge   #(crdt/merge schema %1 %2)
   :compact #(crdt/compact schema %1 %2 %3)
   :problem #(crdt/problem schema %)})

(defn crdt-of
  "The :merge, :compact and :problem functions of the CRDT of the options
  of the state `opts`: those of their :crdt, over those of mind-meld for
  their :schema. It throws when `opts` don't give all three, so that a
  sync without a CRDT fails before it starts."
  [{:keys [schema crdt] :as opts}]
  (let [fns (merge (some-> schema schema-crdt) crdt)]
    (when-not (every? (comp fn? fns) [:merge :compact :problem])
      (throw (ex-info (str "A sync needs the :schema of a state of mind-meld, "
                           "or the :merge, :compact and :problem functions of "
                           "its :crdt")
                      {:given (vec (keys fns))})))
    fns))

(defn merged
  "The `state` with the `states` merged in by the CRDT of `opts`."
  [state states opts]
  (reduce (:merge (crdt-of opts)) state states))

(defn settled
  "The `state` with the whole `states` acknowledged at `at`, and compacted
  by the CRDT of `opts` with their :compact as options, unless that's
  false."
  [state states at {:keys [compact] :as opts}]
  (let [acknowledged (crdt/acknowledge state states at)]
    (if (false? compact)
      acknowledged
      ((:compact (crdt-of opts)) acknowledged at (or compact {})))))
