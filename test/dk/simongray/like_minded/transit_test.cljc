(ns dk.simongray.like-minded.transit-test
  "The codec of documents in Transit: a state of mind-meld and an envelope
  of a sync read back as they were written."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.like-minded.transit :as transit]
            [dk.simongray.mind-meld :as crdt]))

(def state
  (-> {:device "phone"}
      (crdt/write [:notes "n1"] (constantly {:text "Milk" :tags #{"shop"}}) #inst "2026-10-09T09:00:00Z")
      (crdt/write [:plays ["feed" "item"] "phone"] (constantly {:n 2.5}) #inst "2026-10-09T09:01:00Z")))

(deftest a-state-in-transit
  (let [{:keys [encode decode extension]} transit/codec]
    (is (= ".transit.json" extension))
    (is (= state (decode (encode state))) "keywords, sets, instants, vector keys and floats read back")
    (is (nil? (decode nil)))
    (let [opts {:codec  transit/codec
                :schema {:notes {:depth 1} :plays {:depth 2 :count-key :n}}}]
      (is (= state (:state (document/decode (document/encode state #inst "2026-10-09T09:02:00Z" opts)
                                            opts)))
          "as the codec of the documents of a sync"))))
