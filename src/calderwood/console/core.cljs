(ns calderwood.console.core
  (:require [calderwood.console.ws :as ws]
            [calderwood.console.routing :as routing]
            [calderwood.console.views :as views]
            [calderwood.console.diode :as diode]
            [calderwood.console.actions :as actions]
            [calderwood.console.lifecycle :refer [Lifecycle start stop]]
            [taoensso.timbre :as timbre :refer-macros [debug]]
            [cljs.core.async :as async]
            [rum.core :as rum]
            [bidi.bidi :as bidi]
            [goog.events :as events]
            [goog.history.EventType :as HistoryEventType])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:import [goog History]))

(def routes
  ["/" [["" :commands]
        ["commands" :commands]
        ["queries" :queries]
        [true :not-found]]])

(defn render-app [state inputs]
  (rum/mount (views/app state inputs) (.getElementById js/document "console")))

(let [app-state (atom {:events []})
      action-chan (async/chan (async/buffer 100))
      debug-atom (atom {:initial-state {}
                        :past-actions []})
      ws (-> (ws/init! (str "ws://" (.-host js/location) "/ws")
                       (fn [msg]
                         (debug "Incoming WS msg:" msg)
                         (let [[tp client-id client-seq data] msg]
                           (case tp
                             :update
                             (async/put! action-chan
                                         [:update (assoc-in data
                                                            [:event/meta :client-arrival-ts]
                                                            (.getTime (js/Date.)))])
                             :cmd-ack
                             (async/put! action-chan
                                         [:cmd-ack client-id client-seq])
                             :error
                             (async/put! action-chan
                                         [:error client-id client-seq data])
                             nil))))
             start)
      diode-render-loop (-> (diode/init! app-state
                                         {:action-chan action-chan}
                                         render-app
                                         {:ws ws}
                                         :debug-atom debug-atom)
                            start)
      routing (-> (routing/init! routes (:inputs diode-render-loop))
                  start)])

