(defproject calderwood "0.1.0-SNAPSHOT"
  :description "Calderwood: An opinionated reference for event sourced applications."

  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [org.clojure/clojurescript "1.9.293"]
                 [org.clojure/core.async "0.2.385"]
                 [org.clojure/java.classpath "0.2.3"]

                 [http-kit "2.2.0"]
                 [ring/ring-core "1.5.0"]

                 [com.datomic/datomic-free "0.9.5544"
                  :exclusions [com.google.guava/guava]]

                 [io.rkn/conformity "0.4.0"]

                 [bultitude "0.2.8"]

                 [clj-time "0.12.0"]

                 [com.taoensso/timbre       "4.8.0"]

                 [clojurewerkz/scrypt "1.2.0"]

                 [compojure                 "1.5.1"]
                 [hiccup                    "1.0.5"]

                 [rum "0.10.7"]
                 [bidi "2.0.9"]
                 [cljs-http "0.1.42"]]

  :plugins [[lein-cljsbuild "1.1.5"]]

  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [stylefruits/gniazdo "1.0.0"]
                                  [org.hdrhistogram/HdrHistogram "2.1.7"]
                                  [incanter/incanter-core "1.5.6"]
                                  [incanter/incanter-charts "1.5.6"]]
                   :source-paths ["dev" "test"]}}

  :cljsbuild {:builds [{:id           "console-dev"
                        :source-paths ["src/calderwood/console"]
                        :compiler     {:output-to     "resources/public/js/console.js"
                                       :optimizations :whitespace
                                       :pretty-print  true}}]})
