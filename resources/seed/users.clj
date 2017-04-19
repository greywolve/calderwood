(ns seed.users
  (:require [calderwood.common :refer [aggregate-event]]
            [calderwood.util :as util]
            [datomic.api :as d]))

(defn txes [db]
  {:users
   {:txes [(aggregate-event db {:event/name :user-registered
                                :event/uuid (d/squuid)
                                :event/data {:user/email "o@o.com"
                                             :user/uuid (d/squuid)
                                             :user/first-name "Oliver"
                                             :user/last-name "Powell"
                                             :user/password-digest (util/hash-password "s")}})]}})

