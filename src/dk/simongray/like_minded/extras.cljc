(ns ^:no-doc dk.simongray.like-minded.extras
  "The extra documents that a backend writes beside those of the devices,
  e.g. for other apps to read."
  (:require [clojure.string :as str]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.wary-fetch.async :as async]))

(defn documents
  "The documents that the :extra-documents-fn of the options `opts` of a
  backend gives for `state`, as a map of name to body.

  It throws for a name with the extension of the codec, since it would be
  read as a device's document."
  [state {:keys [extra-documents-fn codec] :or {codec document/edn-codec}
          :as   opts}]
  (let [documents (when extra-documents-fn (extra-documents-fn state))]
    (when-let [name (some #(when (str/ends-with? % (:extension codec)) %)
                          (keys documents))]
      (throw (ex-info (str "An extra document can't have the extension of "
                           "the documents of the devices: " name)
                      {:name name})))
    documents))

(defn put!
  "Put the extra documents that `opts` ask for beside the documents of
  `store`, for `state`, each when it changed since `cursor`. Returns the
  cursor, which remembers a hash of each document last put."
  [{:keys [put] :as store} opts state cursor]
  (let [changed (for [[name body] (documents state opts)
                      :when (not= (hash body) (get-in cursor [:extras name]))]
                  [name body])
        put-one (fn [cursor [name body]]
                  (async/then cursor
                              (fn [c]
                                (async/then (put name body {})
                                            (fn [_]
                                              (assoc-in c [:extras name]
                                                        (hash body)))))))]
    (reduce put-one cursor changed)))
