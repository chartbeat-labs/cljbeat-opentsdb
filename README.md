# opentsdb

Clojure library for OpenTSDB

Howdy clojure peeps! So we do a lot of statistics gathering in our codebase and we recently started using OpenTSDB as a repository for some of our data. We didn't see a clojure client library out there, so we wrote our own :) This is still fairly new and there are a lot of things we want to add to it, but we decided it was worth sharing with the world.

This project is a part of [cljbeat](http://chartbeat-labs.github.io/cljbeat/).

## Usage

Leiningen (Clojars - https://clojars.org/com.chartbeat.opentsdb)

`[com.chartbeat.opentsdb "0.1.3"]`

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
              :tags {"with" "hash"}}))))
```
### Party
Really, the only two things you need to know are:

`with-opentsdb`: This is a macro that takes a config hash, opens a connection, exposes the `send` function, and cleans up the connection when you're done.

`send`: Spawns a `go` block that sends a metric over the opened telnet connection. If you're feeling terse, just pass the `metric` `timestamp` `value` `tags` in order. If you're feeling explicit, pass a hash with those keys. Up to you!

## Using the API

Sometimes you might want to hold onto a connection at re-use it, I find this style useful in some cases. We also support more options this way.

### Include it
```clojure
(ns com.chartbeat.opentsdb.example-usage
  (:require [cb.cljbeat.opentsdb.core :as tsdb]))
```
### Use it
```
    (let [client (tsdb/open-connection! "hbasemaster01" 4242)]
      (dotimes [_ 10]
        (tsdb/send-metric client "test.clj-library-dnd" (System/currentTimeMillis) 1337
                      [{:name "type" :value "ogre"} {:name "event" :value "ready"}]))
      (tsdb/close-connection! client))
```

### Set default tags on your connection
```
 (let [client (tsdb/open-connection! "hbasemaster01" 4242 {:tags [{:name "foo" :value "bar"}]})]
      (dotimes [_ 10]
        (tsdb/send-metric client "test.clj-library-dnd" (now) 1337
                      [{:name "type" :value "ogre"} {:name "event" :value "ready"}]))
      (tsdb/close-connection! client))
```
All of the recorded data will have foo=bar along with whatever other tags you add to individual metric calls.

## Reliablity and Error handling
Currently exceptions throw in connecting and sending data to opentsdb are printed to stderr. The send-metric! call returns true if it succeeds and false otherwise. It is up to you to decide to try to reconnect or not. This is a compromise between metrics being mission critical vs. "nice to have". In the future we hope to build up more comprehensive capabilities around connection handling.
## License

BSD 3-clause

