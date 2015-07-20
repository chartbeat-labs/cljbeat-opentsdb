(ns com.chartbeat.opentsdb.example-usage
  "These are a few examples of how to use the API. We will continue to add to it.
  Essentially there are two main ways to send your metrics, either by using the with-opentsdb macro or 
  by calling open-connection! and send-metric!. The difference is that with-opentsdb opens and closes a connection
  each time it's called. In practice both are useful for different use-cases. You can hold onto a connection called with
  open-connection! and reuse it in subsequent calls. If you are sending batch metrics at a slower rate (once every few seconds) 
  the macro is a nice tool. Currently the macro does not support setting default tags.
  "
  (:require [com.chartbeat.opentsdb.core :refer :all])
  (:gen-class)
)

(defn run-examples []
  "This will push some metrics to our actual OpenTSDB cluster. Sweet!"
  (let [now #(System/currentTimeMillis)]
    (with-opentsdb ["hbasemaster01:4242"]
      (send! "test.clj-library" (now) 1337 [["some" "tags"]])
      (send! "test.clj-library" (now) 3.14 [["multiple" "tags"]
                                            ["cool" "right"]])
      (send! "test.clj-library" (now) 4242 [{:name "so-many" :value "ways-to"}
                                            {:name "write-tags" :value "nice"}]))

    (with-opentsdb ["hbasemaster01:4242"]
      (send! {:metric "test.clj-library"
              :timestamp (now)
              :value 1337
              :tags [["the-possibilities" "are-endless"]]}))

    ; Using base api. All the metrics will have the values foo and bar
    ; NOTE: opentsdb can only handle 1 data point per millisecond. 
    ; in fact, if you really want to see each and every event you need to add ms=true to the opentsdb query.
    ; The sleep in this example makes it more realistic, although in practice it's questionable if this is actually useful.
    ; You can verify that each of these events are recorded using a query like this: 
    ; http://hbasemaster01.chartbeat.net:4242/api/query?m=sum:test.clj-library-dnd{event=summoned}&ms=true&start=2015/07/15-05:20:00

    (let [client (open-connection! "hbasemaster01" 4242 {:tags [{:name "foo" :value "bar"}]})]
      (dotimes [_ 10]
        (send-metric! client "test.clj-library-dnd" (now) 1337 
                      [{:name "type" :value "ogre"} {:name "event" :value "ready"}])
        (Thread/sleep 10))
      (dotimes [_ 10]
        (send-metric! client "test.clj-library-dnd" (now) 3434 
                      [{:name "type" :value "wizard"} {:name "event" :value "summoned"}])
        (Thread/sleep 10))
      (close-connection! client))))

(defn -main [] 
  (println "single connection 1000 requests") ; this is for timing...
  (let [client (open-connection! "hbasemaster01" 4242 {:tags [{:name "foo" :value "bar"}]})]
    (time (dotimes [_ 1000]
            (send-metric! client "test.clj-library" (System/currentTimeMillis) 1337 [["some" "tags"]])))
    (close-connection! client))
  
  (run-examples)

)

