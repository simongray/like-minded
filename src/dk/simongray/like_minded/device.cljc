(ns dk.simongray.like-minded.device
  "The id of a device, which names the device's document, made once for
  each installation and kept, so that the device goes on writing the same
  document.

  In a browser, keep the id in localStorage, apart from the data that
  syncs, so that a save of the data that fails doesn't make a new device
  at the next visit.")

(defn random-id
  "A new device id: a random UUID, and so a plain file name, which the
  :device of a state has to be."
  []
  (str (random-uuid)))

#?(:cljs
   (defn id!
     "The id of this browser, kept in localStorage under `key`, which the
     first call makes. Where the browser keeps nothing, e.g. in a private
     window that blocks storage, each call makes a new one."
     [key]
     (or (try (.getItem js/localStorage key) (catch :default _ nil))
         (let [id (random-id)]
           (try (.setItem js/localStorage key id) (catch :default _ nil))
           id))))
