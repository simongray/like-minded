(ns dk.simongray.like-minded.document
  "The documents of a sync: their names, and the envelope around the state
  of a device, encoded and decoded with the options of the state.

  An envelope is a map like this:

      {:version    1                     ; of the state's format
       :device     \"8f1c9e2a-…\"          ; the device that wrote it
       :written-at #inst \"2026-09-26T…\"  ; by the clock of that device
       :app        \"my-app\"              ; the app that wrote it, optional
       :kind       \"my-app/notes\"        ; the kind of state, optional
       :state      {…}}                  ; the whole state of that device

  The options of the state are those of dk.simongray.like-minded."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [dk.simongray.like-minded.crdt :as crdt]
            [dk.simongray.like-minded.diagnostics :as diagnostics]
            [dk.simongray.like-minded.time :as time]))

(def version
  "The version of the state's format that a document records, unless the
  options give another. It changes only when the meaning of a key of the
  state changes, since a reader merges the keys it doesn't know by their
  shape."
  1)

(def edn-codec
  "The codec of documents in EDN, the default. A codec is a map of the
  :extension of the names of the documents, and the functions that
  :encode an envelope as the body of a document and :decode it.

  An app that keeps values of its own in its state, e.g. with tagged
  literals, gives a codec of its own."
  {:extension ".edn"
   :encode    (fn [envelope]
                (binding [*print-length* nil
                          *print-level*  nil]
                  (pr-str envelope)))
   :decode    (fn [s]
                (when (string? s)
                  (edn/read-string s)))})

(def skew-tolerance
  "How far ahead of now a document can be written before the report says
  that its device's clock is ahead: a minute, in milliseconds."
  60000)

(defn extension
  "The extension of the names of the documents, by the codec of the options
  of the state `opts`."
  [opts]
  (:extension (:codec opts edn-codec)))

(defn name-of
  "The name of the document that the device `device` writes, with the
  options of the state `opts`."
  [device opts]
  (str device (extension opts)))

(defn checked-device
  "The `state`, or a throw when its :device can't name its document: it
  needs one that is a plain file name, e.g. what (str (random-uuid))
  gives, made once for each installation."
  [state]
  (let [device (str (:device state))]
    (when (or (str/blank? device)
              (re-find #"[/\\]" device)
              (str/starts-with? device "."))
      (throw (ex-info (str "The state needs a :device that is a plain file "
                           "name, e.g. what (str (random-uuid)) gives, made "
                           "once for each installation")
                      {:device (:device state)})))
    state))

(defn device-document?
  "Whether the listed document `doc` is one that a device writes, by the
  extension that the options of the state `opts` give."
  [doc opts]
  (str/ends-with? (:name doc) (extension opts)))

(defn encode
  "The document of `state` written at `at` with the options of the state
  `opts`, encoded by their codec.

  It's an envelope with the version of the state's format, the device,
  the state, and the :app and :kind of `opts`, if any."
  ([state at]
   (encode state at {}))
  ([state at {:keys [app kind codec] :or {codec edn-codec} :as opts}]
   {:pre [(map? opts)]}
   (let [envelope (cond-> {:version    (:version opts version)
                           :device     (:device state)
                           :written-at (time/date at)
                           :state      state}
                    app  (assoc :app app)
                    kind (assoc :kind kind))]
     ((:encode codec) envelope))))

(defn for-device
  "The document that the device of `state` writes at `at`, with the options
  of the state `opts` and their :app, as a map of its :name and :body."
  [state at opts]
  (checked-device state)
  {:name (name-of (:device state) opts)
   :body (encode state at opts)})

(defn same-kind?
  "Whether the `envelope` holds the kind of state of the options `opts`,
  which it does unless both name a :kind and the kinds differ."
  [envelope opts]
  (or (nil? (:kind envelope))
      (nil? (:kind opts))
      (= (:kind envelope) (:kind opts))))

(defn decode
  "The envelope in the document `s`, decoded with the options of the state
  `opts`.

  It throws ::malformed when `s` isn't an envelope. It throws so too for an
  envelope of the kind of state of `opts`, in their version of the format
  or an older one, when the :problem of their CRDT finds a problem in its
  state."
  [s {:keys [codec] :or {codec edn-codec} :as opts}]
  (let [envelope (try
                   ((:decode codec) s)
                   ;; Throwable on the JVM for the StackOverflowError that
                   ;; deeply nested EDN brings about
                   (catch #?(:clj Throwable :cljs :default) e
                     (throw (ex-info "The document does not decode"
                                     {:type ::malformed}
                                     e))))]
    (when-not (and (map? envelope)
                   (integer? (:version envelope))
                   (map? (:state envelope)))
      (throw (ex-info "The document is not a sync document"
                      {:type ::malformed})))
    (when-let [problem (and (same-kind? envelope opts)
                            (<= (:version envelope) (:version opts version))
                            ((:problem (crdt/functions opts))
                             (:state envelope)))]
      (throw (ex-info (str "The document holds a state that can't be "
                           "merged: " problem)
                      {:type ::malformed})))
    envelope))

(defn- read-one
  "The `acc` of a reading of documents with the document `doc` read at
  `at` with `opts`: its state added, or a diagnostic of what kept it out."
  [acc {:keys [name body] :as doc} at opts]
  (let [envelope (try
                   (decode body opts)
                   (catch #?(:clj Exception :cljs :default) e
                     {:error (or (ex-message e) (str e))}))
        written  (:written-at envelope)
        ahead    (if (inst? written)
                   (- (inst-ms written) (inst-ms at))
                   0)
        report   (fn [acc diagnostic]
                   (update acc :report conj
                           (diagnostics/describe diagnostic)))]
    (cond
      (:error envelope)
      (report acc {:id    :sync/unreadable-document
                   :level :warning
                   :name  name
                   :error (:error envelope)})

      (not (same-kind? envelope opts))
      (report acc {:id    :sync/other-kind
                   :level :warning
                   :name  name
                   :kind  (:kind envelope)})

      (> (:version envelope) (:version opts version))
      (report acc {:id      :sync/newer-format
                   :level   :warning
                   :name    name
                   :version (:version envelope)})

      :else
      (cond-> (update acc :states conj (:state envelope))
        (> ahead skew-tolerance)
        (report {:id      :sync/clock-skew
                 :level   :warning
                 :name    name
                 :device  (:device envelope)
                 :seconds (quot ahead 1000)})))))

(defn states
  "The states in the documents `fetched`, read at `at` with the options of
  the state `opts`, as a map of :states and a :report.

  Each document is a map of :name and :body. The report has a diagnostic
  for each of these:

  - :sync/unreadable-document, a body that doesn't read
  - :sync/other-kind, a document of another kind of state, which is left
    out
  - :sync/newer-format, a document in a newer version of the state's
    format, which is left out
  - :sync/clock-skew, a device whose clock is ahead of this one

  It throws when `opts` give no CRDT, rather than report every document
  as one that doesn't read."
  [fetched at opts]
  (crdt/functions opts)
  (reduce #(read-one %1 %2 at opts) {:states [] :report []} fetched))
