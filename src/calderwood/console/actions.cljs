(ns calderwood.console.actions
  (:require [taoensso.timbre :as timbre :refer-macros [log info warn debug infof warnf debugf]]
            [cljs-http.client :as http]
            [calderwood.console.ws :as ws]
            [calderwood.console.diode :refer [action reaction]]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defmethod action :navigate-to
  [state [_ {:keys [view] :as view-data}]]
  (assoc state
         :error nil
         :success? false
         :active-view view
         :view-params (dissoc view-data :view)))

(defmethod action :edit-command-text
  [state [_ command-text]]
  (let [valid? (try (map? (cljs.reader/read-string command-text))
                    (catch :default e
                      false)) ]
    (assoc state
           :command-text command-text
           :command-text-valid? valid?)))

(defmethod action :submit-command
  [state [_ command-text]]
  (assoc state :loading? true))

(defmethod reaction :submit-command
  [state inputs {:keys [ws]} [_ command-text]]
  (when-not (:loading state)
    (let [command (cljs.reader/read-string command-text)]
      (ws/send! ws
                (assoc-in command
                          [:command/meta :client-sent-ts]
                          (.getTime (js/Date.)))))))

(defmethod action :edit-query-text
  [state [_ query-text]]
  (let [valid? (try (map? (cljs.reader/read-string query-text))
                    (catch :default e
                      false)) ]
    (assoc state
           :query-text query-text
           :query-text-valid? valid?)))

(defmethod action :submit-query
  [state [_ query-text]]
  (assoc state :loading? true)
  state)

(defmethod reaction :submit-query
  [state inputs components [_ query-text]]
  (let [query (cljs.reader/read-string query-text)]
    (debug "Got here!")
    (go (let [sent-ts (.getTime (js/Date.))
              {:keys [status body]} (async/<! (http/post "/query"
                                                         {:edn-params query}))
              arrival-ts (.getTime (js/Date.))]
          (if (= status 200)
            (let [[ok-or-err data err-msg] body]
              (if (= :ok ok-or-err)
                (async/put! (:action-chan inputs)
                            [:query-result data (- arrival-ts sent-ts)])
                (async/put! (:action-chan inputs)
                            [:query-error err-msg])))
            (async/put! (:action-chan inputs)
                        [:query-error :server-error]))))))

(defmethod action :query-result
  [state [_ query-data roundtrip-ms]]
  (assoc state
         :loading? false
         :success? true
         :error nil
         :query-result query-data
         :query-roundtrip-ms roundtrip-ms))

(defmethod action :update
  [state [_ data]]
  (let [key (count (:events state))]
    (update state :events conj (assoc data :key key))))

(defmethod action :cmd-ack
  [state [_ _ _]]
  (assoc state
         :error nil
         :success? true
         :loading? false))

(defmethod action :error
  [state [_ _ _ data]]
  (assoc state
         :loading? false
         :success? false
         :error data))

(defmethod action :query-error
  [state [_ data]]
  (assoc state
         :loading? false
         :success? false
         :error data))

(defmethod action :dismiss-error
  [state [_]]
  (assoc state
         :error nil))

(defmethod action :dismiss-success
  [state [_]]
  (assoc state
         :success? false))
