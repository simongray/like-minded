(ns dk.simongray.like-minded.test-support
  "What the tests share: fixtures inlined from the classpath, a test of
  steps that give promises in ClojureScript, the instants and the two
  devices that the tests put through a store, a state of notes, and on the
  JVM a temporary directory."
  #?(:cljs (:require-macros [dk.simongray.like-minded.test-support]))
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [clojure.test :refer [is async]])
            [dk.simongray.mind-meld :as crdt])
  #?(:clj (:import [java.nio.file Files]
                   [java.nio.file.attribute FileAttribute])))

#?(:clj
   (defmacro fixture
     "The classpath resource at `path`, a literal, inlined as a string when
     the test compiles, on both platforms."
     [path]
     (slurp (io/resource path))))

(defn failed
  "The reason of each backend that failed in the sync `step`, by name, or
  nil when none did."
  [step]
  (not-empty (into {} (for [{:keys [id backend reason]} (:report step)
                            :when (= :sync/backend-failed id)]
                        [backend reason]))))

(defn run
  "Run the steps `fs` one after another as a test on either platform. Each
  step is a function of no arguments that gives a value or a promise.

  In ClojureScript, a deftest runs only the async test that it returns. So
  a deftest calls `run` once, as its last form, with all its steps."
  [& fs]
  #?(:clj  (doseq [f fs] (f))
     :cljs (async done
             (-> (reduce (fn [p f] (.then p (fn [_] (f)))) (js/Promise.resolve nil) fs)
                 (.catch (fn [e] (is false (str "rejected: " e))))
                 (.finally done)))))

(def t0
  "The instant the tests start from; `t1` to `t4` follow an hour apart."
  #inst "2026-01-01T00:00:00Z")

(def t1
  "An hour after `t0`."
  #inst "2026-01-01T01:00:00Z")

(def t2
  "Two hours after `t0`."
  #inst "2026-01-01T02:00:00Z")

(def t3
  "Three hours after `t0`."
  #inst "2026-01-01T03:00:00Z")

(def t4
  "Four hours after `t0`."
  #inst "2026-01-01T04:00:00Z")

(def notes
  "The schema of a state of mind-meld that holds notes by id."
  {:notes {:depth 1 :compact? true}})

(def with-notes
  "The options of a sync of the states of notes."
  {:schema notes})

(defn note
  "The `state` with the note `id` of `text` written at `at`."
  [state id text at]
  (crdt/write state [:notes id] (constantly {:text text}) at))

(defn text
  "The text of the note `id` in `state`."
  [state id]
  (get-in state [:notes id :text]))

(defn phone-notes
  "The notes of a phone that named itself, noted milk at `t0`, and noted
  eggs at `t1`."
  []
  (-> {:device "phone"}
      (crdt/identify {:name "Phone"} t0)
      (note "milk" "Buy milk" t0)
      (note "eggs" "Buy eggs" t1)))

(defn laptop-notes
  "The notes of a laptop that noted bread at `t1`, and changed the note on
  eggs at `t2`, later than the phone."
  []
  (-> {:device "laptop"}
      (note "bread" "Buy bread" t1)
      (note "eggs" "Buy six eggs" t2)))

(defn same?
  "Whether two states agree on all the user sees: everything but whose
  they are, the registry, whose entries reach the other devices a round
  later, and the clock that follows it."
  [x y]
  (= (dissoc x :device :devices :clock) (dissoc y :device :devices :clock)))

#?(:clj
   (defn with-temp-dir
     "Call `f` with a fresh temporary directory, a `java.io.File`, and delete
     it with everything in it afterwards."
     [f]
     (let [dir (.toFile (Files/createTempDirectory "like-minded-test" (make-array FileAttribute 0)))]
       (try
         (f dir)
         (finally
           (doseq [file (reverse (file-seq dir))]
             (io/delete-file file true)))))))
