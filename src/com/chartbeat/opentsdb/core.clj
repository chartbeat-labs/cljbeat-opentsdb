(ns com.chartbeat.opentsdb.core
  "Main api for opentsdb. See example_usage.clj for more information.
  @TODO - check for connections and reconnect if long held sockets becomes stale
  @TODO - allow for setting default tags when using macro
  @TODO - allow for async option
  "
  (:import (java.net Socket)
           (java.io OutputStreamWriter)
           (java.io IOException))
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clj-http.client :as http]))

(def
  ^{:doc "Atom holding timing-since! timers."
    :private true}
  timing-since-timers
  (atom {}))

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
      (conj connection {:socket socket
                        :out (io/writer socket)
                        :in (io/reader socket)}))
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
    (.close (:out connection))
    (.close ^Socket (:socket connection))
    (catch IOException e
      (.printStackTrace e))))

(defn send-line
  "Sends a single line of text to a connection
  Returns false if the connection throw an exception.
  If you care about reliablity you can check the return and try to reconnect."
  [connection line]
  (let [out (:out connection)
        in (:in connection)]
    (try
      (.write out ^String (str line "\n"))
      (.flush out)
      (while (.ready in)
        (.readLine in))
      true
      (catch Exception e
        (.printStackTrace e)
        false))))

(defn send-lines
  "Send multiple lines of text in a single batch to a connection."
  [connection lines]
  (send-line connection (string/join "\n" lines)))

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

(defn send-metrics
  "Sends a collection of metrics to a connection."
  [connection metrics]
  (send-lines connection
              (map (fn [metric]
                     (let [[metric-name timestamp value tags]
                           (if (map? metric)
                             (map metric [:metric :timestamp :value :tags])
                             metric)]
                       (serialize-metric metric-name timestamp value
                                         (merge-tags tags (:default-tags connection)))))
                   metrics)))

(defn send-metrics-http
  "Sends a batch of metrics over http. The metrics argument should be
   an iterable of maps the following keys: metric, timestamp, value, tags."
  ([server port metrics]
   (let [url (format "%s%s:%s/api/put"
                     (if (re-find #"://" server)
                       ""
                       "http://")  ;; prepend http:// if it's not already there
                     server port)]
     (http/post url {:form-params metrics
                     :content-type :json}))))

(defmacro with-opentsdb
 "Abstracts opening and closing an OpenTSDB connection.
  If connection is passed as first param it is used, otherwise assumes connect
  string in format host:port
  @TODO allow for passing option map with connection string."
  [config & body]
  `(let [opts# (first ~config)
         conn# (if (and (not (instance? String opts#))
                        (contains? opts# :socket)
                        (contains? opts# :out))
                   opts#
                   (open-connection! opts#))
         ~'send (partial send-metric conn#)
         ~'send-batch (partial send-metrics conn#)]
     (do ~@body (close-connection! conn#))))

(defn milli-time [] (System/currentTimeMillis))

(defmacro with-timing
  "Report the time to compete the body of expressions to OpenTSDB.

  Example:
  (with-timing open-tsdb-client \"time-to-do-something\"
    ...)"
  [client metric-name tags & body]
  `(let [start-time# (milli-time)
         result# (do ~@body)]
     (send-metric ~client
                  ~metric-name
                  (milli-time)
                  (- (milli-time) start-time#)
                  ~tags)
     result#))

(defn timing-since!
  "Every time this function is called with a key k, it reports time since the
  last time it was called with that key to OpenTSDB.

  Example:
  (timing-since! client \"foo\" []) ;; nothing happens, first time foo was reported
  (timing-since! client \"foo\" []) ;; time since first foo call is reported
  (timing-since! client \"bar\" []) ;; nothing happens, first time bar was reported
  (timing-since! client \"foo\" []) ;; time since second foo call is reported"
  [client k tags]
  (let [now (milli-time)]
    (swap!
      timing-since-timers
      (fn [timers]
         (if-let [last-time (get timers k)]
           (send-metric client
                        k
                        (milli-time)
                        (- (milli-time) last-time)
                        tags))
         (assoc timers k now)))))
