(ns ^:no-doc dk.simongray.like-minded.time
  "Instants on both platforms, java.util.Date on the JVM and js/Date in
  JavaScript, as the sync needs them.")

(defn ->inst
  "The instant `ms` milliseconds after the epoch."
  [ms]
  #?(:clj  (java.util.Date. (long ms))
     :cljs (js/Date. ms)))

(defn now
  "The instant now, by the clock of the platform."
  []
  #?(:clj  (java.util.Date.)
     :cljs (js/Date.)))

(defn date
  "The instant `inst`, of any kind that inst? takes, as the instant of the
  platform. A java.time.Instant, which EDN can't print, comes out as one
  that EDN writes as an inst."
  [inst]
  (->inst (inst-ms inst)))

(defn later
  "The instant `seconds` after the instant `at`."
  [at seconds]
  (->inst (+ (inst-ms at) (* 1000 seconds))))

(defn latest
  "The latest of the `instants`, with nils left out, or nil when none is
  left."
  [& instants]
  (when-let [present (seq (remove nil? instants))]
    (->inst (reduce max (map inst-ms present)))))
