(ns calderwood.perf-test
  (:gen-class)
  (:require [calderwood.core :as c]
            [calderwood.lifecycle :as lifecycle]
            [clojure.string :as string]
            [taoensso.timbre :as timbre]
            [gniazdo.core :as ws]
            [incanter.core :as incanter]
            [incanter.charts :as incanter.charts])
  (:import org.HdrHistogram.Histogram
           java.io.PrintStream))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers

(defn parse-double
  "Parses a double from a string. Returns nil if not a number."
  [s]
  (when (re-find #"^-?\d+\.?\d*$" s)
    (Double/parseDouble s)))

(defn busy-wait [us]
  (let [start-ns (System/nanoTime)]
    (while (>= (+ start-ns (* 1000 us)) (System/nanoTime)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; HDR Histogram, creating, recording, outputting results, etc

(defn create-histogram
  "Creates an instance of the HDR Histogram."
  ^Histogram []
  (Histogram.
   ;; Track up to 1 hours
   (* 1 3600 1000)
   ;; Number of significant digits
   3))

(defn record-value
  [^Histogram hdr value]
  (.recordValue hdr value))

(defn output-percentile-distribution
  [values]
  (let [h (create-histogram)
        boas (java.io.ByteArrayOutputStream.)]
    (doseq [v values]
      (record-value h v))
    (.outputPercentileDistribution h (PrintStream. boas) 1.0)
    (.toString boas)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; HDR Histogram, graphing

(def percentile-distribution-format
  {1.0	      "0%"
   10.0       "90%"
   100.0      "99%"
   1000.0		  "99.9%"
   10000.0		"99.99%"
   100000.0		"99.999%"
   1000000.0  "99.9999%"
   10000000.0 "99.99999%"})

(def percentile-distribution-formatter
  (proxy [java.text.NumberFormat] []
    (format [^:double n _ _]
      (StringBuffer. (get percentile-distribution-format n "")))))

(defn prepare-output-percentile-distribution-for-plotting
  [s]
  (->> (string/split-lines s)
       (remove empty?)
       (map string/trim)
       (map #(string/split % #"\s+"))
       (map (juxt (comp parse-double first)
                  (comp parse-double last)))
       (remove (comp nil? first))
       ;;drop the very last result since max is already included
       ;;sort of
       (butlast)))

(defn percentile-distribution-plot
  [s ops-per-s]
  (let [hdr-data (prepare-output-percentile-distribution-for-plotting s)
        normalised-percentiles (map second hdr-data)
        values (map first hdr-data)
        chart (incanter.charts/xy-plot normalised-percentiles values
                                       :y-label "Latency (ms)"
                                       :series-label (str ops-per-s " ops/s")
                                       :legend true)
        log-chart (incanter.charts/set-axis chart :x (incanter.charts/log-axis
                                                      :label "Percentile"))]
    (-> log-chart
        .getPlot
        .getDomainAxis
        (.setNumberFormatOverride percentile-distribution-formatter))
    log-chart))

(defn add-percentile-distribution-plot-to-existing-chart
  [s log-chart ops-per-s]
  (let [hdr-data (prepare-output-percentile-distribution-for-plotting s)
        normalised-percentiles (map second hdr-data)
        values (map first hdr-data)]
    (incanter.charts/add-lines log-chart normalised-percentiles values
                               :series-label (str ops-per-s " ops/s"))))

(defn view-percentile-distribution-plot [s ops-per-s]
  (let [chart (percentile-distribution-plot s ops-per-s)]
    (incanter/view chart)
    chart))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Perf Test

(def CLIENT-ID "12345")

(defn ws-uri-for-email [email]
  (str "ws://localhost:8080/ws?login-email=" email))

(defn output-results [results]
  (->> results
       (map (fn [{:keys [arrive-ms msg]}]
              (let [[tp _ _ data] (read-string msg)]
                (when (= tp :update)
                  (- arrive-ms
                     (-> data
                         :event/meta
                         :sent-ms))))))
       (remove nil?)
       (output-percentile-distribution)))

(defn run-perf-test [iterations interval-us]
  (let [results (volatile! [])
        latch (java.util.concurrent.CountDownLatch. 1)
        ws-socket (ws/connect
                   (ws-uri-for-email "o@o.com")
                   :on-receive (fn [msg]
                                 (let [results (vswap! results
                                                       conj
                                                       {:arrive-ms (System/currentTimeMillis)
                                                        :msg msg})]
                                   (when (= (count results) (* 2 iterations))
                                     (.countDown latch)))))
        start-ms (System/currentTimeMillis)
        _ (doseq [i (range iterations)]
            (busy-wait interval-us)
            (ws/send-msg ws-socket (pr-str [:cmd CLIENT-ID i
                                            {:command/name :visit-page
                                             :command/data {:page-view/url "www.example.com"}
                                             :command/meta {:sent-ms (System/currentTimeMillis)}}])))
        _ (.await latch)
        ops-per-second (int (/  (* iterations 1000)
                                (- (System/currentTimeMillis) start-ms)))]

    {:ops-per-second ops-per-second
     :results @results}))
