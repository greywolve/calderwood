(ns calderwood.console.routing
  (:require [clojure.string :as string]
            [taoensso.timbre :as timbre :refer-macros [debug]]
            [calderwood.console.lifecycle :refer [Lifecycle start stop]]
            [cljs.core.async :as async]
            [bidi.bidi :as bidi]
            [goog.events :as events]
            [goog.history.EventType :as HistoryEventType])
  (:import [goog History]))

;; History

(defn set-history-token! [history token]
  (when-not (= token (.getToken history))
    (.setToken history token)))

;; Routing

(defn match-route [routes route-segment]
  (let [{:keys [handler route-params]} (bidi/match-route routes route-segment)]
    (merge {:view handler} route-params)))

(defn route-segment [event]
  (let [t (.-token event)]
    (if (string/blank? t)
      "/"
      t)))

(defn navigation-event-handler [history routes {:keys [action-chan]} event]
  (let [token (-> event route-segment)
        match (match-route routes token)]
    (set-history-token! history token)
    (async/put! action-chan [:navigate-to match])))

(defrecord Routing [history routes listener inputs]
  Lifecycle
  (start [component]
    (let [history (History.)
          listener (events/listen history
                                  HistoryEventType/NAVIGATE
                                  (partial navigation-event-handler history routes inputs))]
      (.setEnabled history true)
      (assoc component
             :history history
             :listener listener)))
  (stop [component]
    (.dispose history)
    component))

(defn init! [routes inputs]
  (map->Routing {:history nil
                 :routes routes
                 :inputs inputs
                 :listener nil}))
