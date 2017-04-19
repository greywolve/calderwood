(ns calderwood.common
  (:require [clojure.spec :as s]
            [taoensso.timbre :as timbre]
            [calderwood.spec]
            [datomic.api :as d]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Commands

(defmulti -coerce-command (fn [command]
                           (:command/name command)))

;; coerce does [:ok command] or [:error cmd msg]
(defn coerce-command [command]
  (timbre/debug "Coercing command:" command)
  (let [command* (-> command
                     (select-keys [:command/name
                                   :command/data
                                   :command/meta
                                   :command/client-id
                                   :command/client-seq
                                   :command/user-uuid])
                     (assoc :command/uuid (d/squuid)))]
    (if (s/valid? :command/command command*)
      (-coerce-command command*)
      (do
        (timbre/error "Command validation failed:"
                      command*
                      (s/explain-str :command/command command*))
        [:error command* :command-validation-failed]))))

(defmethod -coerce-command :default [command]
  (timbre/error "No coerce available for command:" command)
  [:error command :missing-command])


(defmulti -handle-command (fn [db command]
                           (:command/name command)))

(defn handle-command [db command]
  (timbre/debug "Handling command:" command)
  (-handle-command db command))

(defmethod -handle-command :default [db command]
  (timbre/error "No command handler available for command:" command)
  [])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Events

(defn event
  ([{:keys [command/uuid
            command/client-seq
            command/client-id
            command/user-uuid]}
    name data meta]
   (cond-> {:event/name name
            :event/uuid (d/squuid)
            :event/data data}
     meta (assoc :event/meta meta)
     uuid (assoc :event/command-uuid uuid)
     user-uuid (assoc :event/user [:user/uuid user-uuid])
     client-seq (assoc :event/client-id client-id)
     client-id (assoc :event/client-seq client-seq)))
  ([command name data]
   (event command name data nil)))

(defmulti -aggregate-event (fn [db event]
                            (:event/name event)))

(defmethod -aggregate-event :default [db event]
  (timbre/debug "No event handler for event:" event)
  [])

(defn aggregate-event [db event]
  (timbre/debug "Aggregating event:" event)
  (let [txes (-aggregate-event db event)]
    (conj txes
          (-> event
              (assoc :db/id (d/tempid :db.part/tx))
              (update :event/data pr-str)
              (update :event/meta pr-str)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queries

(defmulti -coerce-query (fn [query]
                          (:query/name query)))

(defmethod -coerce-query :default [query]
  [:error query :missing-query])

(defn coerce-query [query]
  (timbre/debug "Coercing query:" query)
  (let [query* (select-keys query [:query/name
                                   :query/data
                                   :query/user])]
    (if (s/valid? :query/query query*)
      (-coerce-query query*)
      (do
        (timbre/error "Query validation failed, reason:" (s/explain-str
                                                          :query/query
                                                          query*))
        [:error query* :query-validation-failed]))))

(defmulti -do-query (fn [db query]
                      (:query/name query)))

(defmethod -do-query :default [db query]
  [])

(defn do-query [db query]
  (timbre/debug "Performing query:" query)
  (-do-query db query))
