(ns dk.simongray.like-minded.transit
  "The codec of documents in Transit, which reads about 30 times faster
  than EDN in ClojureScript, e.g. 40 ms against 1.2 s for a parsed podcast
  feed of 20 MB in Chrome.

  Give it as the :codec of a state, or of anything else that takes a
  codec. like-minded doesn't depend on Transit, so this namespace needs
  com.cognitect/transit-cljs in ClojureScript, or transit-clj on the JVM,
  in the deps of your app. It writes Transit's JSON, with the extension
  .transit.json, https://github.com/cognitect/transit-format"
  (:require [cognitect.transit :as transit])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream])))

(defn write-str
  "The value `x` as a string of Transit's JSON."
  [x]
  #?(:clj  (let [out (ByteArrayOutputStream.)]
             (transit/write (transit/writer out :json) x)
             (.toString out "UTF-8"))
     :cljs (transit/write (transit/writer :json) x)))

(defn read-str
  "The value of the string `s` of Transit's JSON."
  [s]
  #?(:clj  (transit/read (transit/reader (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
                                         :json))
     :cljs (transit/read (transit/reader :json) s)))

(def codec
  "The codec of documents in Transit's JSON: a map of the :extension of
  the names of the documents, and the functions that :encode a value as
  the body of a document and :decode it."
  {:extension ".transit.json"
   :encode    write-str
   :decode    (fn [s]
                (when (string? s)
                  (read-str s)))})
