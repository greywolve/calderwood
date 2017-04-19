(ns calderwood.domain
  (:require [clojure.spec :as s]
            [calderwood.spec]
            [calderwood.common :as c :refer [event
                                             -coerce-command
                                             -handle-command
                                             -aggregate-event
                                             -coerce-query
                                             -do-query]]
            [datomic.api :as d]
            [taoensso.timbre :as timbre]
            [calderwood.util :as util]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Commands

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Register User

;; NOTE - in a real production system we would make sure that passwords and
;; other sensitive information is never included when logging commands/events

(defmethod -coerce-command :register-user [{:keys [command/data] :as command}]
  (if (s/valid? :command.data/register-user data)
    [:ok command]
    (do
      (timbre/error "register-user validation failed:"
                    (s/explain-str :command.data/register-user data))
      [:error command :command-validation-failed])))

(defmethod -handle-command :register-user [db {:keys [command/data
                                                      command/meta]
                                               :as command}]
  [(-> (event command :user-registered data meta)
       (update :event/data #(dissoc % :user/password))
       (assoc-in [:event/data :user/uuid] (d/squuid))
       (assoc-in [:event/data :user/password-digest] (util/hash-password
                                                      (:user/password data))))])

(defmethod -aggregate-event :user-registered [db {:keys [event/data]
                                                  :as event}]
  [(-> data
       (select-keys [:user/uuid
                     :user/first-name
                     :user/last-name
                     :user/email
                     :user/password-digest])
       (assoc :db/id (d/tempid :db.part/user)))])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Visit Page

(defmethod -coerce-command :visit-page [{:keys [command/data] :as command}]
  (if (and
       (:command/user-uuid command)
       (s/valid? :command.data/visit-page data))
    [:ok command]
    [:error command :command-validation-failed]))

(defmethod -handle-command :visit-page [db {:keys [command/data
                                                   command/meta]
                                              :as command}]
  [(event command :page-view data meta)])

(defmethod -aggregate-event :page-view [db {:keys [event/data]
                                         :as event}]
  [{:db/id (d/tempid :db.part/user)
    :page-view/url (:page-view/url data)
    :page-view/user (:event/user event)}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Queries

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; List users

(defmethod -coerce-query :list-users [query]
  [:ok query])

(defmethod -do-query :list-users [db query]
  (->> (d/q '[:find [?u ...]
              :where [?u :user/uuid _]]
            db)
       (map (partial d/entity db))
       (mapv #(select-keys % [:user/uuid
                              :user/first-name
                              :user/last-name
                              :user/email]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; List page views

(defmethod -coerce-query :list-page-views [query]
  [:ok query])

(defmethod -do-query :list-page-views [db query]
  (->> (d/q '[:find ?p ?tx
              :where [?p :page-view/url _ ?tx]]
            db)
       (mapv (fn [[p tx]]
               (let [{:keys [page-view/url
                             page-view/user]} (d/entity db p)]
                 {:page-view/timestamp (:db/txInstant (d/entity db tx))
                  :page-view/url url
                  :page-view/user (select-keys
                                   user
                                   [:user/uuid
                                    :user/email])})))))
