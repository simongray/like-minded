# Reference

This page has the contracts and the formats of like-minded. The
[README](../README.md) shows how to sync, and the docstrings have the
details of each function. [Providers](providers.md) lists where the
common WebDAV servers and sync clients keep their folders.


## The store contract

A store is a map of functions over named documents, each with a version
token:

```clojure
{:fetch  (fn [name opts])       ; {:body "…" :version "…"}, or nil
 :put    (fn [name body opts])  ; {:version "…"}, or {:conflict? true}, optional
 :list   (fn [])                ; [{:name "…" :version "…"} …], optional
 :delete (fn [name])            ; optional
 :name   "Nextcloud"}           ; for the UI and the report
```

- A put refuses the write when `opts` has an `:if-version` that isn't the
  current version of the document, and an `:if-version` of nil means that
  the document mustn't exist yet. The check and the write happen as one
  step, so of two puts with the same `:if-version`, only one succeeds. The
  folder store guarantees this within one process, and in a browser
  across the tabs of a site.
- A refused write returns `{:conflict? true}` rather than throwing, since
  it's a normal outcome when the devices share one document. A failure of
  the network or of a service throws, and the step reports it.
- A body is text or bytes. A fetch gives it as text, or as bytes with
  `{:as :bytes}` in its `opts`, whichever of the two was put, so a store
  converts it when needed.
- On the JVM, each function returns its value and throws on failure. In
  ClojureScript, it can return a value or a promise.
- The credentials stay in the closure of the constructor, never in the
  map, and the `:name` mustn't contain them.
- A store without `:list` can't hold a document per device, so it holds
  one shared document.
- A store without `:put` is read only, and a backend over it only pulls.

To test a store against this contract, use `store/check!`. It writes a
probe document, and for a store without `:list`, it checks that a put
with a stale version and a put with an `:if-version` of nil are both
refused, as one shared document needs. A store without `:delete` keeps
the probe document.


## The backend contract

A backend is a map of two functions and a name, which `sync!` calls:

```clojure
{:pull (fn [state cursor at]) ; {:states […] :cursor cursor' :report […] :partial? false}
 :push (fn [state cursor at]) ; {:cursor cursor' :state state' :report […]}
 :name "my-service"}
```

- The pull returns the states to merge and the cursor for the next step.
  With `:partial? true`, the states are only the changes since the
  cursor. They merge like whole states, but they aren't acknowledged in
  mind-meld's registry of devices, since they don't show all that a
  device has merged.
- The push writes the merged state and returns the cursor. Its `:state`
  and `:report` are optional, and it returns a `:state` when it learned
  more while it wrote.
- The `at` is the instant of the step, so the functions never read a
  clock.
- The backends of one `sync!` need different names, since the cursors are
  kept by name, and `sync!` throws on a duplicate.

Any store becomes a backend through `backend`. A service that merges on
its own server is a backend directly, which translates in both
directions: a push sends the changes since the last push as the service's
own actions, and a pull turns what the service reports into a partial
state. The backend of podcast-clj for the gpodder API,
[sync.gpodder](https://github.com/simongray/podcast-clj/blob/master/src/dk/simongray/podcast/sync/gpodder.cljc),
is one.


## The options

The options of `backend`:

| Option | Meaning | Default |
|---|---|---|
| `:strategy` | `:per-device` or `:shared` | `:per-device` if the store has `:list`, else `:shared` |
| `:direction` | `:both`, `:pull` (read only) or `:push` (write only) | `:both` if the store has `:put`, else `:pull` |
| `:app` | The name of the app, written into each document | none |
| `:name` | The name of the backend | the name of the store |
| `:document` | The name of the shared document | `state` and the extension of the codec, e.g. `state.edn` |
| `:extra-documents-fn` | A function of the state that gives documents to write beside those of the devices, as a map of name to body, e.g. a list for other apps to read | none |

A backend, `sync!`, `reconcile` and the functions of `document` also take
the options of the state:

| Option | Meaning | Default |
|---|---|---|
| `:schema` | The schema of a state of mind-meld | none |
| `:crdt` | The `:merge`, `:compact` and `:problem` functions of the state, which go over those of the `:schema` | none |
| `:version` | The version of the state's format | `document/version`, which is 1 |
| `:kind` | What state the documents hold, e.g. `"my-notes"` | none |
| `:codec` | How a document is encoded: a map of the `:extension` of its name, and the functions that `:encode` its map as the body and `:decode` it | `document/edn-codec` |

A sync needs a `:schema`, or all three functions of a `:crdt`. With
either strategy, a retired device writes nothing, and the report has
`:sync/retired-device`. An extra document is written when it changes, and
its name can't end in the extension of the codec, since it would be read
as a device's document.


## The per-device strategy

Each device writes only its own document, named after its device id,
e.g. `phone.edn`. A step of `sync!` with this strategy does the
following:

1. List the store, and fetch every document whose version differs from the
   one in the cursor, all at once. Only names that end in the extension of
   the codec are the documents of devices, and a document without a
   version in the listing is always fetched.
2. Read each document, and report any that doesn't read.
3. Merge the states into the local state.
4. If the merged state differs from what this device wrote last, put the
   document of this device. It needs no precondition, since no other device
   writes it.
5. Put each extra document that changed.
6. Delete the documents of retired devices.

If the document of this device changed since the last step, another
installation, or another tab of the same page, is using the same device
id, and the report has `:sync/shared-device`. If the document is gone from
the store, the next push writes it again.


## The shared strategy

All devices write one document, `state.edn` by default. A step of `sync!`
with this strategy does the following:

1. Fetch the document. If its version is the one in the cursor, there's
   nothing new to merge.
2. Merge it into the local state.
3. If the merged state differs from what this device put last, put it with
   `:if-version` set to the version in the cursor.
4. If the store refuses the put, fetch, merge and put again, which is
   safe, since a second merge changes nothing. After three retries, stop
   and report `:sync/conflict`.
5. Unless step 4 stopped, put each extra document that changed.

If the document is gone from the store, the next push writes it again. A
document in a newer format, or of another kind, is left alone, since this
device can't read all of it and a put would lose data.


## A step

A step of `sync!` returns this, in ClojureScript as a promise:

```clojure
{:state   merged                ; the local state with the pulled states merged
 :cursors {"Nextcloud" {…} …}   ; by backend name
 :report  [...]}                ; the diagnostics of the step
```

Besides the options of the state, `sync!` takes `:at`, the instant of the
step, which is the current time by default, the `:cursors` of the last
step, and `:compact`, which is false to keep every tombstone, or the
options of the compact function of the CRDT.

- A backend that failed is in the report as `:sync/backend-failed`, with
  its `:backend` and the `:reason`.
- If the pull of a backend fails, `sync!` doesn't push to it, and its
  cursor stays the same. If a push fails, the backend keeps the cursor of
  its pull.
- If the `:problem` of the CRDT finds a problem in the merged state, e.g.
  a value that came in through an `assoc`, the step writes nothing and
  reports `:sync/malformed-state`, since the other devices would leave
  out the whole document.
- The `:state` doesn't include what the user did during the step. Merge it
  into the current state with the merge of the CRDT, then compact, since
  the merge brings back the tombstones that the step dropped.

Each diagnostic in the report is a map with an `:id`, a `:level` and a
`:message`, and `diagnostics/catalogue` describes every id.

To do the I/O yourself, e.g. in a background task with a time budget,
fetch the documents whose names end in the extension of the codec, merge
them with `reconcile`, and put the document that `document/for-device`
gives.


## The document

A document is an EDN map, unless the options give another codec:

```clojure
{:version    1                     ; the version of the state's format
 :device     "phone"               ; the id of the device that wrote it
 :written-at #inst "2026-10-10T…"  ; when, by the clock of that device
 :app        "my-app"              ; the app that wrote it, optional
 :kind       "my-notes"            ; the kind of state, optional
 :state      {…}}                  ; the whole state of that device
```

- EDN holds a state of mind-meld as it is, with its instants, sets and
  keys of any type. An app that keeps values of its own in the state,
  e.g. with tagged literals, gives a codec of its own, and so does one
  that writes JSON, which has to map the keys that JSON can't hold, such
  as vectors.
- The version only changes when the meaning of an existing key changes.
  The merge of mind-meld handles keys it doesn't know by the shape of
  their values, so a state can grow without a new version.
- A reader leaves out a document with a newer version, and reports
  `:sync/newer-format`.
- A reader with a `:kind` leaves out a document of another kind, and
  reports `:sync/other-kind`. A document without a kind is read by any
  reader, and a reader without one reads any document. So apps that sync
  the same kind of state can share a folder, and apps with kinds of their
  own can share one without merging each other's states.
- A document that doesn't read, or whose state the `:problem` of the CRDT
  finds a problem in, is left out with `:sync/unreadable-document`, since
  the merge would spread the problem to every device.
- A document written more than a minute ahead of this device's clock is
  merged all the same, and `:sync/clock-skew` names its device. The merge
  spreads the later time to every device, so the stamps no longer say
  when things happened, but nothing is lost, and the states still agree.
  Leaving the document out wouldn't help, since its device couldn't sync,
  and its own state would keep the later stamps once its clock was fixed.


## The encrypted envelope

The store of `store.encrypted` wraps each body in this EDN map:

```clojure
{:encrypted 1 :cipher "AES-256-GCM" :kdf "PBKDF2-HMAC-SHA256"
 :iterations 600000 :salt "…" :iv "…" :data "…"}
```

- The `:salt`, `:iv` and `:data` are base64.
- Each store instance seals with one random salt, and each document gets a
  new IV.
- The key is derived from the passphrase with PBKDF2 and HMAC-SHA256,
  through `javax.crypto` on the JVM and WebCrypto in ClojureScript, with
  the iterations and the salt of the envelope.
- An envelope can ask for at most `max-iterations`, ten times the default.
  A body that asks for more, or whose salt, IV or data isn't base64, isn't
  an envelope, and is reported as a document that doesn't read.
- A body that isn't an envelope passes through unchanged, so you can start
  encrypting a folder at any time. The documents named in the `:except`
  of the store are written in the clear, e.g. an extra document for other
  apps to read.
- If the passphrase doesn't open a document, the call fails with the
  exception type `:dk.simongray.like-minded.store.encrypted/locked`.
- If the passphrase is a function, the store asks it again when a cached
  key doesn't open a document, and tries once more, so a corrected
  passphrase is used from the next document on.


## The stores

| Store | Namespace | Platform | Version token |
|---|---|---|---|
| A map in an atom | `store/memory` | all | a count of the writes |
| A folder | `store.folder` | all | the modification time, as exact as the file system keeps it, and the size |
| A WebDAV collection | `store.webdav` | all | the ETag |
| An IndexedDB database | `store.indexeddb` | a browser | a count of the writes |
| Any store, encrypted | `store.encrypted` | all | the version of the wrapped store |

### A folder

The folder store takes a path on the JVM and in Node. In a browser, it
takes a `FileSystemDirectoryHandle`, or the files of a file input or a
drop:

- A handle can come from `showDirectoryPicker` or from the origin private
  file system. A write goes through `createWritable`, whose changes reach
  the file only when the stream closes (File System, section 2.3.2).
- The files of an input are read only. Their store has no `:put` or
  `:delete`, so a backend over it only pulls. Of a folder loaded with
  `<input webkitdirectory>`, only the files at its top count, and the
  folder's name is the store's name.
- In ClojureScript, `folder/query-permission!` and
  `folder/request-permission!` give `:granted`, `:prompt` or `:denied`. A
  path, a folder of the origin private file system and the files of an
  input give `:granted`, since there's nothing to ask for. The browser
  asks the user only during a click.
- The failures that the user can fix have a `:type` in their ex-data:
  `::folder/not-allowed` for a permission to renew, `::folder/gone` for a
  folder that was moved, deleted or unplugged, and `::folder/read-only`
  for a folder that the browser can't write to.
- The name of a document is a plain file name, with no slash, and a
  hidden file isn't a document, so the store doesn't list one.

### A WebDAV collection

The WebDAV store takes its `:credentials` as a map of a `:username` and a
`:password`, or of a `:token`, or as a function that returns the current
map, so that you can refresh a token that expires. OAuth flows are up to
you, and nothing that a sync returns or reports contains the credentials.

- The store doesn't send a user name or password from the URL, such as
  `https://user:pass@host/`, and its name is the host alone.
- On the JVM, the credentials only go to the origin of the collection,
  never to another host after a redirect.
- In a browser, the server must let the page through CORS, and expose
  the ETag.

### A WebDAV server

On the JVM, `store.webdav.server/handler` serves any store over WebDAV as
a Ring handler:

- A PROPFIND on the collection at depth 0 gives the collection alone, and
  at depth 1 it also lists the documents.
- If the store has no `:list`, a PROPFIND that lists the collection gets
  403. If it has no `:put` or no `:delete`, a PUT or a DELETE gets 405,
  and OPTIONS doesn't offer it. A 405 names the allowed methods in
  `Allow`.
- MKCOL gets 405, since the collection already exists.
- A client of such a server removes the same keys from its WebDAV store,
  e.g. `(dissoc (webdav/store url) :list)`, and the backend then uses one
  shared document.
- A PUT carries at most 32 MB, or the `:max-bytes` of the handler, and a
  larger body gets 413.
- A PUT with `If-Match: *` matches the document if it exists, and gets 412
  if it doesn't.
- A name with a slash or a control character, and the names `.` and `..`,
  get 400.

The handler checks no credentials, so mount it behind your login. On a
site with many users, give each a handler over the store of their own
documents.
