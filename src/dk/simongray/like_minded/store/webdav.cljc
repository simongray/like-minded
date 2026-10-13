(ns dk.simongray.like-minded.store.webdav
  "A WebDAV collection (RFC 4918) as a document store, e.g. on Nextcloud,
  ownCloud, Seafile, Synology, Box, Fastmail or a Hetzner storage box.

  It uses GET, PUT, DELETE and a PROPFIND at depth one, with the ETag as
  the version. If-Match makes a put conditional, and If-None-Match: * asks
  for a document that isn't there yet. HEAD asks for the ETag when a
  server answers a put without one, and MKCOL makes the collection when it
  isn't there. webdav-clj makes the requests and reads the answers.

  In a browser, the server must let the page through CORS and expose the
  ETag."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webdav :as dav]))

(def calls
  "The calls that a store makes to a collection:

  - :list, for the documents in it
  - :fetch, :put and :delete, for one document
  - :head, for the version of one document
  - :make-collection, for the collection itself"
  #{:list :fetch :put :delete :head :make-collection})

(def default-content-types
  "The Content-Type of a document by the extension of its name, unless the
  options of a store or a server give others. A type with a charset is
  that of text, and a type without one, that of bytes."
  {".edn"  "application/edn; charset=utf-8"
   ".json" "application/json; charset=utf-8"
   ".opml" "text/x-opml; charset=utf-8"
   ".txt"  "text/plain; charset=utf-8"
   ".xml"  "application/xml; charset=utf-8"})

(defn content-type
  "The Content-Type that the document `name` is put and served with, by
  the extension of its name in `content-types` or default-content-types,
  and application/octet-stream for any other name."
  ([name]
   (content-type name {}))
  ([name content-types]
   (let [extension (re-find #"\.[^.]*$" name)
         types     (merge default-content-types content-types)]
     (get types extension "application/octet-stream"))))

(defn ^:no-doc binary?
  "Whether the document `name` holds bytes rather than text, by the
  Content-Type that `content-types` give it, which has no charset."
  ([name]
   (binary? name {}))
  ([name content-types]
   (not (str/includes? (content-type name content-types) "charset="))))

;; RFC 9110, sections 13.1.1 (If-Match) and 13.1.2 (If-None-Match: *)
(defn request
  "The request map for `call` on the collection at `root`, for the
  document `name` when the call takes one, with the `opts` below.

  The `call` is one of calls, and the `root` is a URL that ends in a
  slash. The `opts` give the :credentials, the :body to put, text or
  bytes, and :as :bytes for a fetch of bytes. For a put, they can give
  :if-version, the version that the document must still have, where nil
  means that it must not exist yet. Without :if-version, the put has no
  condition. Its Content-Type is by the :content-types of `opts`, as
  content-type has them."
  ([call root]
   (request call root nil {}))
  ([call root name]
   (request call root name {}))
  ([call root name {:keys [credentials body content-types] :as opts}]
   {:pre [(map? opts)]}
   (let [url     (str root (url/percent-encode name))
         version (:if-version opts)
         common  {:credentials credentials}]
     (case call
       :list            (dav/request :propfind root (assoc common
                                                           :depth 1
                                                           :props [:dav/getetag :dav/resourcetype]))
       :fetch           (dav/request :get url (cond-> common
                                                (= :bytes (:as opts)) (assoc :as :bytes)))
       :head            (dav/request :head url common)
       :put             (dav/request :put url (cond-> (assoc common
                                                             :body         body
                                                             :content-type (content-type name content-types))
                                                (contains? opts :if-version)
                                                (assoc (if version :if-match :if-none-match)
                                                       (or version "*"))))
       :delete          (dav/request :delete url common)
       :make-collection (dav/request :mkcol root common)
       (throw (ex-info (str "A call a store does not make: " call)
                       {:call call}))))))

(def ^:no-doc call-methods
  "The method of WebDAV that each call makes."
  {:list :propfind :fetch :get :head :head :put :put :delete :delete
   :make-collection :mkcol})

;; RFC 4918, sections 9.1.1 (207), 9.3.1 (405 for MKCOL) and 9.7.1 (409
;; for a put without its collection)
(defn response
  "What the `answer` to `call`, a map of :status, :headers and :body, says:

  - :list, the documents as maps of :name and :version, or none for a
    collection that isn't there yet
  - :fetch, the :body and :version, or nil when the document isn't there
  - :head, the :version
  - :put, the :version when it was written, :conflict? when the
    precondition failed, or :missing-collection? when the collection isn't
    there
  - :delete and :make-collection, nil

  Any other status throws ::failed with the :status, and so does a
  listing that can't be read."
  [call {:keys [status] :as answer}]
  (let [{:keys [etag body resources malformed?]} (dav/parse-response {:method (call-methods call)} answer)
        fail (fn []
               (throw (ex-info (str "The server answered " status " to "
                                    (name call))
                               {:type ::failed :call call :status status})))]
    (case call
      :list            (case status
                         207 (if malformed?
                               (fail)
                               (->> resources
                                    (remove :collection?)
                                    (filter :name)
                                    (map (fn [{:keys [name etag]}] {:name name :version etag}))
                                    (sort-by :name)
                                    vec))
                         404 []
                         (fail))
      :fetch           (case status
                         200 {:body body :version etag}
                         404 nil
                         (fail))
      :head            (case status
                         200 {:version etag}
                         (fail))
      :put             (case status
                         (200 201 204) {:version etag}
                         412           {:conflict? true}
                         409           {:missing-collection? true}
                         (fail))
      :delete          (case status
                         (200 204 404) nil
                         (fail))
      :make-collection (case status
                         (201 405) nil
                         (fail)))))

(defn- host
  "The host of the URL `root`, without credentials. It has its port when
  that isn't the default port of the scheme."
  [root]
  (when-let [[scheme host port] (url/origin root)]
    (cond-> host
      (not= port (get {"http" 80 "https" 443} scheme)) (str ":" port))))

(defn store
  "The store over the WebDAV collection at `root`, a URL such as
  https://dav.example.org/notes/, with the `opts` below:

  - :credentials, a :username and a :password, or a :token, as a map or a
    function of no arguments that returns one
  - :name, for the UI, the host by default
  - :send, a function that sends a request map, in place of http/send!
  - :timeout-seconds, how long a request can take, 30 by default
  - :user-agent, as http/send! takes it
  - :content-types, the Content-Type of a document by the extension of its
    name, over default-content-types

  The collection is made on the first put when it isn't there, and a
  server that answers a put without an ETag is asked for one."
  ([root]
   (store root {}))
  ([root {:keys [credentials name send content-types] :as opts}]
   {:pre [(map? opts)]}
   (let [root  (if (str/ends-with? root "/") root (str root "/"))
         call! (fn [call name params]
                 (let [params (assoc params
                                     :credentials   credentials
                                     :content-types content-types)
                       asked  (merge (request call root name params)
                                     (select-keys opts [:timeout-seconds
                                                        :user-agent]))]
                   (async/then ((or send http/send!) asked)
                               #(response call %))))
         put!  (fn put! [name body params made?]
                 (async/then
                  (call! :put name (assoc params :body body))
                  (fn [result]
                    (cond
                      (and (:missing-collection? result) (not made?))
                      (async/then (call! :make-collection nil {})
                                  (fn [_] (put! name body params true)))

                      (:missing-collection? result)
                      (throw (ex-info (str "The collection is not there, and "
                                           "making it did not help: " root)
                                      {:type   ::failed
                                       :call   :put
                                       :status 409}))

                      (and (not (:conflict? result))
                           (nil? (:version result)))
                      (call! :head name {})

                      :else
                      result))))]
     {:name   (or name (host root) "WebDAV")
      :fetch  (fn [name opts] (call! :fetch name (select-keys opts [:as])))
      :put    (fn [name body params] (put! name body params false))
      :list   (fn [] (call! :list nil {}))
      :delete (fn [name] (call! :delete name {}))})))

#?(:clj
   (comment
     (require '[dk.simongray.like-minded :as like-minded]
              '[dk.simongray.like-minded.store :as store])
     (def s (store "http://localhost:8300/dav/"))
     (store/check! s)
     (def b (like-minded/backend s {:schema {:notes {:depth 1}}}))
     #_.))
