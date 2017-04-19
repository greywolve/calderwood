(ns calderwood.console.ws
  (:require [taoensso.timbre :as timbre :refer-macros [debug]]
            [calderwood.console.lifecycle :refer [Lifecycle start stop]]))

(def ^:const WS_CONNECTING 0)
(def ^:const WS_OPEN       1)
(def ^:const WS_CLOSING    2)
(def ^:const WS_CLOSED     3)

(defrecord WS [url ws-chan on-message-fn client-id client-seq]
  Lifecycle
  (start [component]
    (debug "Attempting to connect Websocket")
    (if-let [chan (js/WebSocket. url)]
      (do
        (set! (.-onmessage chan) (fn [msg] (on-message-fn
                                            (cljs.reader/read-string (.-data msg)))))
        (set! (.-onopen chan) (fn []
                                (debug "Websocket connection established with: " url)))
        (assoc component :ws-chan chan))
      (throw (js/Error. "Websocket connection failed!"))))
  (stop [component]))

(defn init! [url on-message-fn]
  (WS. url nil on-message-fn (str (random-uuid)) (atom -1)))

(defn send! [ws cmd]
  (.send (:ws-chan ws) (pr-str [:cmd
                                (:client-id ws)
                                (swap! (:client-seq ws) inc)
                                cmd])))
