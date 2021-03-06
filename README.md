# opentsdb

Clojure library for OpenTSDB

Howdy clojure peeps! So we do a lot of statistics gathering in our codebase and we recently started using OpenTSDB as a repository for some of our data. We didn't see a clojure client library out there, so we wrote our own :) This is still fairly new and there are a lot of things we want to add to it, but we decided it was worth sharing with the world.

This project is a part of [cljbeat](http://chartbeat-labs.github.io/cljbeat/).

## Usage

Leiningen (Clojars - https://clojars.org/com.chartbeat.opentsdb)

`[com.chartbeat.opentsdb "0.2.1"]`

There are two ways to use the library, using a macro and using the api directly.
The best source of inspiration is in `cb.cljbeat.opentsdb.example_usage.clj`.

## Using the Macro

The macro allows you to wrap your metric calls in a typical `with-xxx` style. The connection is opened and closed in the macro. `send` all of the metrics you want in this wrapper.

It's pretty simple. Here's some example code that explains pretty much everything.
### Include it
```clojure
(ns com.chartbeat.opentsdb.example-usage
  (:require [com.chartbeat.opentsdb.core :refer [with-opentsdb open-connection! send-metric! close-connection!]]))
```
### Use it
```clojure
(defn run-examples []
  "This will push some metrics to our actual OpenTSDB cluster. Sweet!"
  (let [now #(System/currentTimeMillis)]
    (with-opentsdb [{:host "metrics.chartbeat.net" :port 4242}]
      (send "test.clj-library" (now) 3.141 {"without" "hash"})
      (send {:metric "test.clj-library"
              :timestamp (now)
              :value 1337
              :tags {"with" "hash"}})
      (send-batch [["test.clj-library" (now) 42 {"in" "batch"}]
                   ["test.clj-batching" (now) 3 {"in" "batch"}]]))))
```
### Party
Really, the only two things you need to know are:

`with-opentsdb`: This is a macro that takes a config hash, opens a connection, exposes the `send` function, and cleans up the connection when you're done.

`send`: Spawns a `go` block that sends a metric over the opened telnet connection. If you're feeling terse, just pass the `metric` `timestamp` `value` `tags` in order. If you're feeling explicit, pass a hash with those keys. Up to you!

`send-batch`: Same as send, but for sending multiple metrics in a single request.

## Using the API

Sometimes you might want to hold onto a connection at re-use it, I find this style useful in some cases. We also support more options this way.

### Include it
```clojure
(ns com.chartbeat.opentsdb.example-usage
  (:require [cb.cljbeat.opentsdb.core :as tsdb]))
```
### Use it
Use a tcp client
```
    (let [client (tsdb/open-connection! "metrics.chartbeat.net" 4242)]
      (dotimes [_ 10]
        (tsdb/send-metric client "test.clj-library-dnd" (System/currentTimeMillis) 1337
                      [{:name "type" :value "ogre"} {:name "event" :value "ready"}]))
      (tsdb/send-metrics client [["test.clj-library-dnd" (System/currentTimeMillis) 10003 {"type" "goblin"}]
                                 ["test.clj-library-dnd" (System/currentTimeMillis) 9001 {"type" "demon"}]
                                 ["test.clj-library-dnd" (System/currentTimeMillis) 1 {"type" "necromancer"}]])
      (tsdb/close-connection! client))
```
Or send batch metrics over http
```
    (tsdb/send-metrics-http "metrics.chartbeat.net" 4080 [{:metric "foo"
                                                           :timestamp (System/currentTimeMillis)
                                                           :value 1
                                                           :tags {:host "localhost"
                                                                  :group "test-group"}}
                                                          {:metric "bar"
                                                           :timestamp (System/currentTimeMillis)
                                                           :value 2}])
```

### Set default tags on your connection
```
 (let [client (tsdb/open-connection! "metrics.chartbeat.net" 4242 {:tags [{:name "foo" :value "bar"}]})]
      (dotimes [_ 10]
        (tsdb/send-metric client "test.clj-library-dnd" (now) 1337
                      [{:name "type" :value "ogre"} {:name "event" :value "ready"}]))
      (tsdb/close-connection! client))
```
All of the recorded data will have foo=bar along with whatever other tags you add to individual metric calls.

## Reliablity and Error handling
Currently exceptions throw in connecting and sending data to opentsdb are printed to stderr. The send-metric! call returns true if it succeeds and false otherwise. It is up to you to decide to try to reconnect or not. This is a compromise between metrics being mission critical vs. "nice to have". In the future we hope to build up more comprehensive capabilities around connection handling.

## Time Metrics

There are two ways of measuring elapsed time:

`with-timing` is a macro that will report the time to execute all the
expressions in its body:
```clojure
(let [client (tsdb/open-connection! "metrics.chartbeat.net" 4242)]
  (with-timing! client "some_timing_metric" [["tag" "value"]]
    (do-some-stuff)))
```

`timing-since!` is a function that will report the time between calls to it.
The last time executed is stored in a top level atom.
```clojure
(let [client (tsdb/open-connection! "metrics.chartbeat.net" 4242)]
  (timing-since! client \"foo\" []) ;; nothing happens, first time foo was reported
  (timing-since! client \"foo\" []) ;; time since first foo call is reported
  (timing-since! client \"bar\" []) ;; nothing happens, first time bar was reported
  (timing-since! client \"foo\" []) ;; time since second foo call is reported
```

## License

BSD 3-clause

