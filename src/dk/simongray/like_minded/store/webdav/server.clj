(ns dk.simongray.like-minded.store.webdav.server
  "Any document store served over WebDAV (RFC 4918), for a Clojure backend
  whose browser clients sync through their own origin.

  The documents of the store are the members of one collection, which
  webdav-clj's handler serves with ETags and preconditions: GET, HEAD,
  PUT, DELETE, PROPFIND, OPTIONS, and COPY and MOVE from one document to
  another. A store without :put, :list or :delete takes no PUT, gives no
  listing or takes no DELETE, and a client of it leaves the same keys out
  of its WebDAV store."
  (:require [dk.simongray.like-minded.bytes :as bytes]
            [dk.simongray.like-minded.store.webdav :as webdav]
            [dk.simongray.webdav.server :as server]))

;; a store refuses a name that it can't keep with an ex-info of no :type,
;; e.g. the folder store a name that starts with a dot, and a file system
;; with an IllegalArgumentException
(defn- naming
  "What `f`, a function of no arguments that calls a store, returns, or
  else a refusal of 400 for a name that the store refuses."
  [f]
  (let [refused #(ex-info (ex-message %) {:dk.simongray.webdav/status 400} %)]
    (try
      (f)
      (catch clojure.lang.ExceptionInfo e
        (if (some (ex-data e) [:type :dk.simongray.webdav/status])
          (throw e)
          (throw (refused e))))
      (catch IllegalArgumentException e
        (throw (refused e))))))

(defn- version-of
  "The version of the document `name` in `store`, or nil when it isn't
  there.

  It comes from the listing when the store has one, so that no body is
  read to learn that a document exists."
  [{:keys [list fetch] :as store} name]
  (naming #(if list
             (some (fn [doc] (when (= name (:name doc)) (:version doc))) (list))
             (:version (fetch name {:as :bytes})))))

(defn backend
  "The backend of webdav-clj's handler over `store`, whose documents are
  the members of the root collection, by the `content-types` that
  webdav/content-type takes. A document is text or bytes as
  webdav/binary? says."
  [{:keys [list fetch put delete] :as store} content-types]
  (let [resource (fn [name version]
                   {:etag version :content-type (webdav/content-type name content-types)})
        binary?  #(webdav/binary? % content-types)]
    (cond-> {:resource (fn [path]
                         (case (count path)
                           0 {:collection? true}
                           1 (some->> (version-of store (first path)) (resource (first path)))
                           nil))
             :content  (fn [[name]]
                         (naming #(:body (fetch name (if (binary? name) {:as :bytes} {})))))}
      list   (assoc :children (fn [_]
                                (for [{:keys [name version]} (list)]
                                  (assoc (resource name version) :name name))))
      ;; the handler gives the version that it checked as :if-etag, nil
      ;; when the document wasn't there
      put    (assoc :put! (fn [[name] body opts]
                            (let [b      (server/body-bytes body)
                                  result (naming #(put name
                                                       (if (binary? name) b (bytes/decode b))
                                                       (if (contains? opts :if-etag)
                                                         {:if-version (:if-etag opts)}
                                                         {})))]
                              (when (:conflict? result)
                                (throw (ex-info "The document changed"
                                                {:dk.simongray.webdav/status 412})))
                              (resource name (:version result)))))
      delete (assoc :delete! (fn [[name]]
                               (naming #(delete name))
                               nil)))))

(defn handler
  "A Ring handler that serves `store` over WebDAV under `base`, the path of
  the collection with its trailing slash, e.g. \"/dav/\", with `opts`.

  Mount it under Pedestal, ring-jetty or http-kit for the route `base` and
  everything below it. Whether the router passes a request for the
  collection with its trailing slash, and the body of a put unread,
  depends on the router. The `opts` are:

  - :max-bytes, the most that a put can send, 32 MB by default
  - :content-types, the Content-Type of a document by the extension of
    its name, over webdav/default-content-types, which also tells text
    from bytes

  The handler checks no credentials, so mount it behind your login. For a
  site with many users, give each the store of their own documents,
  e.g. by making the handler for each request, which costs little, with
  a function store-of of your own from a user to their store:

      (fn [request]
        ((handler (store-of (:user request)) \"/dav/\") request))"
  ([store base]
   (handler store base {}))
  ([store base {:keys [max-bytes content-types]
                :or   {max-bytes (* 32 1024 1024)}
                :as   opts}]
   {:pre [(map? opts)]}
   ;; the collection holds no collection, so depth infinity is depth 1,
   ;; which a PROPFIND without a Depth asks for
   (server/handler (backend store content-types)
                   {:root               base
                    :max-bytes          max-bytes
                    :propfind-infinity? true})))
