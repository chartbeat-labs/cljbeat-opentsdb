(defproject com.chartbeat.opentsdb "0.1.2-SNAPSHOT"
  :description "Simple lil' clojure library for OpenTSDB"
  :url "https://github.com/chartbeat-labs/cljbeat-opentsdb"
  :license {:name "BSD 3 Clause"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [tcp-server "0.1.0"]]
  :aot :all
  :profiles {:benchmark {:main com.chartbeat.opentsdb.example_usage}}
  :aliases {"benchmark" ["with-profile" "benchmark" "run"]}
  :vcs :git
  
)
