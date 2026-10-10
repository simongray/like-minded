(ns dk.simongray.like-minded.diagnostics-test
  "The catalogue of diagnostics and the source agree: every id that the
  code raises is catalogued, at the level that the code gives it, and
  nothing is catalogued that the code never raises."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.like-minded.diagnostics :as diagnostics]))

(def source
  "The library's source, apart from the catalogue, as one string."
  (->> (file-seq (io/file "src"))
       (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))
       (remove #(= "diagnostics.cljc" (.getName ^java.io.File %)))
       (map slurp)
       (str/join "\n")))

(deftest the-catalogue-matches-the-source
  (testing "the same ids"
    (is (= (set (map #(keyword (subs % 1)) (re-seq #":sync/[a-z-]+" source)))
           (set (keys diagnostics/by-id)))))
  (testing "the same levels"
    (doseq [[_ id level] (re-seq #"\{:id\s+:(sync/[a-z-]+)\s+:level\s+:([a-z]+)" source)]
      (is (= (keyword level) (:level (diagnostics/by-id (keyword id)))) id)))
  (testing "every entry documented"
    (is (every? (comp seq :doc) diagnostics/catalogue))))
