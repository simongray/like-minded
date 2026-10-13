like-minded
===========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/like-minded.svg)](https://clojars.org/dk.simongray/like-minded)

> _Great minds think alike._

This is a Clojure and ClojureScript library for local-first sync. It keeps
an app's data the same on all of a user's devices through storage they
already have, e.g. a folder that iCloud, Dropbox or Syncthing syncs, a
WebDAV server such as Nextcloud, or the browser's own storage. There's no
sync server to run.

**NOTE:** It's the companion of
[mind-meld](https://github.com/simongray/mind-meld): mind-meld merges the
states of two devices without conflict, and like-minded gets the states
from each device to the others.

- **Storage the user has.** There are stores for a folder, on the JVM, in
  Node and through the browser's folder picker, for a WebDAV collection,
  for IndexedDB and for memory, and any store can be encrypted at rest. A
  store is a map of a few functions, so one of your own is a few lines.
- **No conflicts to resolve.** Each device writes only its own document
  and reads those of the others, so a folder that any sync client mirrors
  is a complete backend. A store that can't list holds one shared
  document instead, written on condition of its version.
- **Several backends at once.** A step pulls from every backend, merges,
  and pushes to each, e.g. to keep a folder and a server in step, or to
  move from one store to another.
- **A report, not an exception.** A document that doesn't read, a device
  whose clock is ahead or a backend that's down is a diagnostic in the
  report of the step, and the state is only ever merged into.
- **Nothing in the background.** The library schedules and logs nothing.
  You call `sync!`, and `next-sync` says when to call it again.

The same code runs on the JVM, in Node and in the browser.

> This library was spun out of [podcast-clj](https://github.com/simongray/podcast-clj),
> a library for making podcast software. There, it syncs a listener's
> subscriptions, queue and play progress between their devices. Like
> podcast-clj, it was developed with assistance from frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. For the latest release, add it
from [Clojars](https://clojars.org/dk.simongray/like-minded) to the
`:deps` in your `deps.edn`:

```clojure
dk.simongray/like-minded {:mvn/version "0.3.0"}
```

For changes that aren't released yet, use the SHA of the latest commit on
`master` instead:

```clojure
dk.simongray/like-minded
{:git/url "https://github.com/simongray/like-minded"
 :git/sha "…"}
```

For ClojureScript, shadow-cljs only reads Git dependencies from
`deps.edn`, so also set `:deps true` in your `shadow-cljs.edn`.

The state of each device is a state of mind-meld, and the options of the
state say what it is. Give them to the backend and to each sync:

```clojure
(require '[dk.simongray.like-minded :as like-minded]
         '[dk.simongray.like-minded.store.folder :as folder]
         '[dk.simongray.mind-meld :as crdt])

(def opts
  {:schema {:notes {:depth 1 :compact? true}}  ; the mind-meld schema
   :kind   "my-notes"})                         ; what the documents hold

(def dropbox
  (like-minded/backend (folder/store "/Users/me/Dropbox/my-notes") opts))

(def phone
  (crdt/write {:device "phone"}
              [:notes "milk"]
              (constantly {:text "Buy milk"})
              #inst "2026-10-10T09:00:00Z"))

(def step
  (like-minded/sync! phone [dropbox] opts))
```

The `:device` of a state names its document, so make it once for each
installation and keep it, e.g. with `(str (random-uuid))`. It has to be
a plain file name, and `sync!` throws without one.

The step writes `phone.edn` into the folder. A laptop that syncs through
the same folder writes `laptop.edn`, and merges in the phone's notes:

```clojure
(def laptop
  (crdt/write {:device "laptop"}
              [:notes "bread"]
              (constantly {:text "Buy bread"})
              #inst "2026-10-10T09:05:00Z"))

(-> (like-minded/sync! laptop [dropbox] opts) :state :notes keys)
;; => ("bread" "milk")
```

A step returns the merged `:state`, a `:report` of what it found, its
`:at`, and the `:cursors` of the backends and the `:failures` in a row,
which the next step of the device takes:

```clojure
(like-minded/sync! (:state step) [dropbox]
                   (merge opts (select-keys step [:cursors :failures])))
```

In ClojureScript, `sync!` returns a promise. The user may have changed
the state during the step, so give the step to `finish` with the state as
it is by then, which merges the two and compacts them as the step did:

```clojure
(-> (like-minded/finish phone step opts) :notes keys)
;; => ("milk")
```

Then call `next-sync` for when to sync again.

When to sync
------------

Call `sync!` when the app starts, when it goes to the background, after a
series of changes, and when a file watcher or a service says that
something changed. A step that overlaps another can write an older state
after a newer one, so a step that begins while another step of the
device is running does nothing, and its report has
`:sync/overlapping-step`. That holds within a process, and in a browser
across the tabs of a page too. A file watcher also sees the writes of
the step itself, so let such a call wait until the step ends.

For when to sync again, give `next-sync` the last step, with its `:at`
and `:failures`, and whether the state has `:changed?` since:

```clojure
(like-minded/next-sync {:at #inst "2026-10-10T09:00:00Z" :changed? true}
                       #inst "2026-10-10T09:00:03Z")
;; => #inst "2026-10-10T09:00:10.000-00:00"
```

The waits of `default-cadence` are short after a change, long when
nothing changed, and grow with each failure. Give waits of your own as
the third argument.

For control over the I/O, e.g. in a background task with a time budget,
`reconcile` merges the documents that you fetch yourself, as the
[reference](doc/reference.md#a-step) describes.

To turn sync off on a device, call `leave!`, which takes what `sync!`
takes. It retires the device and writes that through each backend once
more. The other devices read it at their next step, stop waiting for the
device before they compact, and delete its document. A retired device
can't come back, so to sync it again later, give its state a new
`:device`.

Stores
------

A store is a map of functions over named documents with versions, and
`dk.simongray.like-minded.store` describes the contract:

| Store | Namespace | Platform |
|---|---|---|
| A map in an atom | `store/memory` | all |
| A folder | `store.folder` | the JVM, Node, and a browser through `showDirectoryPicker` or the files of an input |
| A folder of the app's own adapter, e.g. over expo-file-system | `store.folder` | all |
| A WebDAV collection | `store.webdav` | all |
| An IndexedDB database | `store.indexeddb` | a browser |
| A key-value store of the app's own adapter, e.g. MMKV or expo-sqlite | `store.kv` | all |
| Any store, encrypted | `store.encrypted` | all |

To try a store through its contract, e.g. from a button in the settings,
call `store/check!`, which reports what works. It's also the test for a
store of your own. On the JVM, `store.webdav.server/handler` serves any
store over WebDAV as a Ring handler, for a server whose browser clients
sync through their own origin.

The encrypting store seals each document with AES-256-GCM under a key
from a passphrase, and keeps the salt and the IV in the document, so
there's no key file to lose:

```clojure
(require '[dk.simongray.like-minded.store.encrypted :as encrypted])

(encrypted/store (folder/store "/Users/me/Dropbox/my-notes")
                 "correct horse battery staple")
```

The passphrase can also be a function that asks the user for it.

The [reference](doc/reference.md#the-stores) has the version token of
each store and what it takes, and [Providers](doc/providers.md) lists
where the common WebDAV servers and sync clients keep their folders.

The options of a state
----------------------

- `:schema`, the schema of the state in mind-meld, whose functions merge
  it
- `:crdt`, the `:merge`, `:compact` and `:problem` functions of a state
  with rules of its own, which go over those of the schema
- `:version`, the version of the state's format, which changes only when
  the meaning of a key does. A device leaves out a document of a newer
  version.
- `:kind`, what state the documents hold, so that two apps that sync
  through the same folder don't merge each other's states
- `:codec`, how a document is encoded, EDN by default

A backend also takes the `:app` to write into each document, an
`:extra-documents-fn` that gives documents to write beside the state,
e.g. for other apps to read, and the `:strategy` and `:direction` that
it otherwise takes from the store. The
[reference](doc/reference.md#the-options) has the defaults of each.

A backend of your own
---------------------

A service that merges on its own server is a backend directly, a map of
a `:pull` and a `:push`:

```clojure
{:pull (fn [state cursor at])  ; the states of the other devices
 :push (fn [state cursor at])  ; writes the merged state
 :name "my-service"}
```

A pull that gives only the changes since its cursor says so with
`:partial?`, and they merge like any other state. A `:leave` that writes
the state of a device that leaves is optional. The
[reference](doc/reference.md#the-backend-contract) has the whole
contract.

Principles
----------

- **Merging is mind-meld's.** A step reads, merges and writes whole
  states, and never has a conflict to resolve, so any place that stores a
  document will do.
- **Only ever merge into the local state.** A failed pull changes
  nothing, and a failed push is simply repeated.
- **Each device writes its own.** With a document per device, no two
  devices write the same file, so a sync client never has a conflict
  either.
- **Leave alone what you can't read.** A document of a newer version or
  of another kind isn't merged, and a shared one isn't written over.
- **Maps of functions.** Stores and backends are maps rather than
  protocols, so wrapping one is updating a key, and the credentials stay
  in the closure of the constructor.
- **One codebase.** The library is written in `.cljc`, and depends on
  mind-meld, [webdav-clj](https://github.com/simongray/webdav-clj) for
  WebDAV, and [wary-fetch](https://github.com/simongray/wary-fetch) to
  send its requests.

Development
-----------

```bash
clojure -X:test                       # the tests on the JVM
npm install                           # once, for the Node tests
clojure -M:cljs compile test          # the tests in Node
clojure -M:cljs compile browser-test  # for a browser, from target/browser-test
```

Besides the tests of each store, generative tests have devices change a
state at random and sync in random orders through a store, with either
strategy. They check that the devices end in the same state, and that
compaction then leaves no tombstone and changes nothing that a user sees.

License
-------

The like-minded project is licensed under the [MIT licence](LICENSE).
