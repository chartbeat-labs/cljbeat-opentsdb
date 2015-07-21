(ns com.chartbeat.opentsdb.core
  "Main api for opentsdb. See example_usage.clj for more information.
  @TODO - check for connections and reconnect if long held sockets becomes stale
  @TODO - allow for setting default tags when using macro
  @TODO - allow for async option
  "
  (:import (java.net Socket)
           (java.io OutputStreamWriter)
           (java.io IOException))
  (:require [clojure.string :as string]))

(defn- vectorify-tags
  "If the tags are in map format, tranform to vector form"
  [tags]
  (if (map? (first tags))
    (reduce #(conj %1 [(:name %2) (:value %2)]) [] tags)
    tags))

(defn- parse-default-tags
  "If we got default tags they get flattened to a vector here"
  [maybe-tags]
  (if (contains? maybe-tags :tags)
    (vectorify-tags (:tags maybe-tags))
    nil))

(defn- connect-internal
  "abstracting the internals of connect away so we can improve them later.
  currently if this fails we print the error but allow the program to continue."
  [connection]
  (try
    (let [socket (Socket. (:host connection) (:port connection))]
      (.setSoTimeout socket 1000)
      (.setKeepAlive socket true)
      (conj connection {:socket socket :out (OutputStreamWriter. (.getOutputStream socket))}))
    (catch Exception e
      (.printStackTrace e)
      connection)))

(defn open-connection!
  "Parses and creates the connection map. Currently takes host:port or host port as arguments.
  Default tags can be added (only if using 2nd form) by passing {:tags [{:name :value}...] }"
  ([host-port]
   (let [[host port] (string/split host-port #":")]
     (open-connection! host (Integer. port))))
  ([host port & rest]
   (let [connection (connect-internal {:host host :port port})]
     (conj connection {:default-tags (parse-default-tags (first rest))}))))

(defn close-connection!
  "Closes and cleans up a connection."
  [connection]
  (try
    (.close ^OutputStreamWriter (:out connection))
    (.close ^Socket (:socket connection))
    (catch IOException e
      (.printStackTrace e))))

(defn send-line
  "Sends a single line of text to a connection
  Returns false if the connection throw an exception.
  If you care about reliablity you can check the return and try to reconnect."
  [connection line]
  (let [out (:out connection)]
    (try
      (.write ^OutputStreamWriter out ^String (str line "\n"))
      (.flush ^OutputStreamWriter out)
      true
      (catch Exception e
        (.printStackTrace e)
        false))))

;; TODO The map should already be flattened to a vector of tags by this point,
;; but keeping this around for now there should be no need to flatten it here
(defn serialize-tag
  "Serializes a tag into a string, with [name, value] style or {:name name :value value} form"
  [tag]
  (let [serialize (fn [name value] (format "%s=%s", name, value))]
    (if (map? tag) ;; TODO MORE VALIDATION
      (serialize (:name tag) (:value tag))
      (serialize (first tag) (second tag)))))

(defn serialize-metric
  "Serializes a single metric put into a string"
  [metric timestamp value tags]
  (let [tag-strs       (map serialize-tag tags)
        long-timestamp (long timestamp)
        float-value    (float value)]
   (string/join " " (concat ["put" metric long-timestamp float-value]
                            tag-strs))))

(defn- merge-tags
  "Merge with the default tags"
  [tags default-tags]
  (let [vec-tags (vectorify-tags tags)]
    (reduce conj default-tags vec-tags)))

(defn send-metric
  "Sends an OpenTSDB metric to a connection."
  ([connection {:keys [metric timestamp value tags]}]
   (send-metric connection metric timestamp value tags))
  ([connection metric timestamp value tags]
   (let [full-str (serialize-metric metric timestamp value
                                    (merge-tags tags (:default-tags connection)))]
     (send-line connection full-str))))

(defmacro with-opentsdb
 "Abstracts opening and closing an OpenTSDB connection.
  If connection is passed as first param it is used, otherwise assumes connect string in format host:port
  @TODO allow for passing option map with connection string."
  [config & body]
  `(let [opts# (first ~config)
         conn# (if (and (not (instance? String opts#))
                        (contains? opts# :socket)
                        (contains? opts# :out))
                   opts#
                   (open-connection! opts#))
        ~'send (partial send-metric conn#)]
     (do ~@body (close-connection! conn#))))
