(ns dk.simongray.like-minded.diagnostics
  "Every diagnostic that a sync can put in a report, by id, with its level
  and what it means.

  A diagnostic is a map with an :id, a :level and a :message, and other
  keys give details, such as the :name of a document. The ids are API,
  since apps match on them. An app that has a catalogue of its own can add
  this one to it."
  (:require [clojure.string :as str]))

(defn- unwrap
  "The text `s` on one line, without the line breaks and the indentation
  that wrap it in the source."
  [s]
  (str/replace s #"\s*\n\s*" " "))

(def catalogue
  "The diagnostics of a sync, in the order they tend to arise."
  (mapv
   #(update % :doc unwrap)
   [{:id :sync/backend-failed :level :warning
     :doc "A backend failed in a pull or a push, so the step went on without it,
           and it keeps its cursor. The :backend names it, and the :reason says
           why."}
    {:id :sync/unreadable-document :level :warning
     :doc "A document in the store didn't read as a sync document, and was left
           out until its device writes one that reads."}
    {:id :sync/other-kind :level :warning
     :doc "A document holds another kind of state than the one this device
           syncs, e.g. that of another app pointed at the same folder, and was
           left out."}
    {:id :sync/newer-format :level :warning
     :doc "A document was written in a newer version of the state's format than
           this device reads, and was left out."}
    {:id :sync/malformed-state :level :error
     :doc "The state of this device holds a value that the other devices can't
           merge, e.g. from an assoc, so the step wrote nothing. The :error says
           where."}
    {:id :sync/clock-skew :level :warning
     :doc "A device wrote its document more than a minute ahead of the clock of
           this device. When every other device is named, the clock of this
           device is probably behind."}
    {:id :sync/shared-device :level :warning
     :doc "The document of this device changed without this device writing it,
           so two installations, or two tabs of one page, use the same device
           id."}
    {:id :sync/retired-device :level :warning
     :doc "This device was retired from another device, so nothing was written
           for it. Its changes no longer reach the other devices, so give the
           state a new device id to sync again."}
    {:id :sync/conflict :level :warning
     :doc "The shared document changed during every attempt to write it, so the
           step ended without writing."}
    {:id :sync/overlapping-step :level :info
     :doc "A step began while another step of this device was still running,
           in this process or another tab of the page, so it did nothing, since
           two steps at once can write an older state after a newer one."}]))

(def by-id
  "The catalogue by id."
  (into {} (map (juxt :id identity)) catalogue))

(defn describe
  "The `diagnostic` with a :message and a :level: its own, or else those
  that the catalogue gives for its id."
  [diagnostic]
  (let [{:keys [doc level]} (by-id (:id diagnostic))]
    (cond-> diagnostic
      (and doc (not (:message diagnostic)))  (assoc :message doc)
      (and level (not (:level diagnostic))) (assoc :level level))))
