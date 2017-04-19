(ns user
  (:require [calderwood.core :as c]
            [calderwood.domain :as cd]
            [calderwood.perf-test :as perf]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.core.async :as async]
            [calderwood.lifecycle :as lifecycle]
            [taoensso.timbre :as timbre]
            [datomic.api :as d]))

(def system nil)

(defn init []
  (alter-var-root #'system
                  (constantly (c/dev-system {:db-uri
                                             "datomic:free://localhost:4334/calderwood"}))))

(defn start []
  (alter-var-root #'system lifecycle/start))

(defn stop []
  (alter-var-root #'system
                  (fn [s] (when s (lifecycle/stop s)))))

(defn go []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/go))


(defn perf-test []
  (timbre/set-level! :error)
  (let [{:keys [results
                ops-per-second]}
        (perf/run-perf-test 10000 2500)
        chart (perf/view-percentile-distribution-plot
               (perf/output-results results)
               ops-per-second)]
    (timbre/set-level! :debug)
    chart))
