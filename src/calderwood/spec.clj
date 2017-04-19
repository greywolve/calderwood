(ns calderwood.spec
  (:require [clojure.spec :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Command Spec

(s/def :command/name keyword?)
(s/def :command/uuid uuid?)
(s/def :command/data map?)
(s/def :command/meta map?)
(s/def :command/client-id string?)
(s/def :command/client-seq int?)
(s/def :command/user-uuid uuid?)

(s/def :command/command
  (s/keys :req [:command/name
                :command/uuid
                :command/data]
          :opt [:command/meta
                :command/client-id
                :command/client-seq
                :command/user-uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Event Spec

(s/def :event/name keyword?)
(s/def :event/uuid uuid?)
(s/def :event/command-uuid uuid?)
(s/def :event/data map?)
(s/def :event/meta map?)
(s/def :event/client-id string?)
(s/def :event/client-seq int?)
(s/def :event/user (s/tuple #(= :user/uuid %) uuid?))

(s/def :event/event
  (s/keys :req [:event/name
                :event/uuid
                :event/data]
          :opt [:event/meta
                :event/command-uuid
                :event/client-id
                :event/client-seq
                :event/user]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Query Spec

(s/def :query/name keyword?)
(s/def :query/data map?)
(s/def :query/user (s/tuple #(= :user/uuid %) uuid?))

(s/def :query/query
  (s/keys :req [:query/name
                :query/data
                :query/user]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Custom Commands

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Register User

(s/def :user/first-name string?)

(s/def :user/last-name string?)

(s/def :user/email string?)

(s/def :user/password string?)

(s/def :command.data/register-user
  (s/keys :req [:user/first-name
                :user/last-name
                :user/email
                :user/password]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Visit Page

(s/def :page-view/url string?)

(s/def :command.data/visit-page
  (s/keys :req [:page-view/url]))






