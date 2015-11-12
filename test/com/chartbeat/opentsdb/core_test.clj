(ns com.chartbeat.opentsdb.core-test
  (:require [clojure.test :refer :all]
            [com.chartbeat.opentsdb.core :refer :all]
            [net.tcp.server :as tcp]
            [clojure.string :refer [trim]]))

;; Testing pure functions first

(deftest test-serialization
  (testing "serialize-tag"
    (testing "should serialize the vector form"
      (is (= "foo=bar" (serialize-tag ["foo" "bar"]))))
    (testing "should serialize the hash form"
      (is (= "foo=bar" (serialize-tag {:name "foo" :value "bar"})))))
  (testing "serialize-metric"
    (testing "should correctly serialize a given put"
      (is (= "put foo.bar 1234 567.0 baz=qux"
             (serialize-metric "foo.bar" 1234 567.0 [["baz" "qux"]])))
      (is (= "put foo.bar 1234 567.0 baz=qux"
             (serialize-metric "foo.bar" 1234 567.0 [{:name "baz"
                                                       :value "qux"}])))
      (is (= "put foo.bar 1234 567.0 this=has multiple=tags"
             (serialize-metric "foo.bar" 1234 567.0 [["this" "has"]
                                                     ["multiple" "tags"]]))))))

(defmacro test-tcp [& tests]
  "Sets up and tears down a tcp server for the purpose of testing connection code"
  `(let [handler# (fn [reader# writer#] nil)
         server# (tcp/tcp-server :port 5000
                               :handler (tcp/wrap-io handler#))]
      (do (tcp/start server#)
          ~@tests
          (tcp/stop server#))))

(defn mock-connection [connection]
  "Returns a mocked connection (:mocked-conn key) and atoms (in the :state key) indicating
  whether certain operations (write, flush) have been performed"
  (let [written (atom "nothing yet")
        flushed (atom false)
        out-proxy (proxy [java.io.OutputStreamWriter]
                         [(java.io.ByteArrayOutputStream.)]
                         (write [text] (swap! written (fn [old] text)))
                         (flush [] (swap! flushed (fn [old] true))))
        mocked-conn (assoc connection :out out-proxy)
        state {:written written :flushed flushed}]
    {:mocked-conn mocked-conn :state state}))


(deftest test-connections
  (testing "connection opening/closing/sending"
    (testing "open-connection!"
      (test-tcp
       (let [connection (open-connection! "127.0.0.1:5000")
             socket (:socket connection)
             out (:out connection)]
         (testing "successfully opens a connection"
           (is (= 5000 (.getPort socket)))
           (is (.isConnected socket))
           (is (= "127.0.0.1" (.getHostAddress (.getInetAddress socket)))))
         (testing "successfully sets up an OutputStreamWriter"
           (is (not (nil? out))))
         (close-connection! connection))))
    (testing "close-connection!"
      (test-tcp
        (let [connection (open-connection! "127.0.0.1:5000")
              socket (:socket connection)
              out (:out connection)]
          (close-connection! connection)
          (testing "successfully closes the connection"
            (is (.isClosed socket)))
          (testing "successfully closes the OutputStreamWriter"
            (is (thrown? java.io.IOException (.write out "throwpls")))))))
    (testing "send-line"
      (test-tcp (fn []
        (testing "properly writes to and flushes its writer"
          (let [connection (open-connection! "127.0.0.1:5000")
                {:keys [mocked-conn state]} (mock-connection connection)]
            (is (send-line mocked-conn "what's up")) ; should return true if it's a good connection
            (is (= "what's up\n" @(:written state)))
            (is @(:flushed state)))))))
    (testing "send-metric"
      (test-tcp
        (testing "properly writes to and flushes its writer"
          (let [connection (open-connection! "127.0.0.1:5000")
                {:keys [mocked-conn state]} (mock-connection connection)
                reset! #(swap! (:flushed state) (fn [old] false))]
            (send-metric mocked-conn "foo.bar" 1234 567.0 [["baz" "qux"]])
            (is (= "put foo.bar 1234 567.0 baz=qux\n" @(:written state)))
            (is @(:flushed state))
            (reset!)
            (send-metric mocked-conn "foo.bar" 1234 567.0 [{:name "baz" :value "qux"}])
            (is (= "put foo.bar 1234 567.0 baz=qux\n" @(:written state)))
            (is @(:flushed state))
            (reset!)
            (send-metric mocked-conn "foo.bar" 1234 567.0 [["this" "has"]
                                                   ["multiple" "tags"]])
            (is (= "put foo.bar 1234 567.0 multiple=tags this=has\n" @(:written state)))
            (is @(:flushed state))
            (reset!)))))
    (testing "default-tags!"
      (test-tcp
       (let [connection (open-connection! "127.0.0.1" 5000
                                          {:tags [{:name "foo" :value "bar"} {:name "boo" :value "zoob"}]})
             {:keys [mocked-conn state]} (mock-connection connection)
             reset! #(swap! (:flushed state) (fn [old] false))]
         (send-metric mocked-conn "foo.bar" 1234 567.0 [{:name "baz" :value "qux"}])
         (is (= "put foo.bar 1234 567.0 foo=bar boo=zoob baz=qux\n" @(:written state))))))))

(deftest test-with-opentsdb
  (testing "with-opentsdb macro"
    (test-tcp
      (testing "properly sends metrics with send"
        (let [connection (open-connection! "127.0.0.1:5000")
              {:keys [mocked-conn state]} (mock-connection connection)]
          (with-opentsdb [mocked-conn]
            (send "foo.bar" 1234 567.0 [["baz" "qux"]]))
          (is (= "put foo.bar 1234 567.0 baz=qux\n" @(:written state)))
          (is @(:flushed state)))))))

(deftest test-timing-since
  (testing "timing-since! macro"
    (test-tcp
      (let [current-time-stamp (atom 0)
            connection (open-connection! "127.0.0.1:5000")
            {:keys [mocked-conn state]} (mock-connection connection)]
        (with-redefs [milli-time (fn [] @current-time-stamp)]
          (swap! current-time-stamp + 1000)
          (timing-since! mocked-conn "foo" [["baz" "qux"]])
          (swap! current-time-stamp + 1000)
          (timing-since! mocked-conn "foo" [["baz" "qux"]])
          (is (= "put foo 2000 1000.0 baz=qux" (trim @(:written state))))
          (swap! current-time-stamp + 1000)
          (timing-since! mocked-conn "foo" [["baz" "qux"]])
          (is (= "put foo 3000 1000.0 baz=qux" (trim @(:written state)))))))))

(deftest test-with-timing
  (testing "with-timing macro"
    (test-tcp
      (let [current-time-stamp (atom 1234)
            connection (open-connection! "127.0.0.1:5000")
            {:keys [mocked-conn state]} (mock-connection connection)]
        (with-redefs [milli-time (fn [] @current-time-stamp)]
          (with-timing mocked-conn "foo" [["baz" "qux"]]
            (swap! current-time-stamp + 1000))
          (is (= "put foo 2234 1000.0 baz=qux" (trim @(:written state)))))))))
