(ns dk.simongray.like-minded.device-test
  "The id of a device: a random one, and in a browser, the one that
  localStorage keeps. Node has no localStorage, so there each call makes a
  new one."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.like-minded.device :as device]))

(deftest a-random-id
  (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                  (device/random-id)))
  (is (not= (device/random-id) (device/random-id))))

#?(:cljs
   (deftest the-id-of-a-browser
     (let [key (str "like-minded-test-" (random-uuid))
           id  (device/id! key)]
       (if (exists? js/localStorage)
         (do (is (= id (device/id! key)) "the same id at every call")
             (.removeItem js/localStorage key))
         (is (not= id (device/id! key)) "a new id at every call, where nothing is kept")))))
