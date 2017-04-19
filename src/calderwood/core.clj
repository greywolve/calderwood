(ns calderwood.core
  (:require [org.httpkit.server :as http-kit]
            [calderwood.common :refer [coerce-command
                                       handle-command
                                       aggregate-event
                                       coerce-query
                                       do-query]]
            [calderwood.lifecycle :refer [Lifecycle start stop]]
            [clojure.string :as string]
            [compojure.core :as compojure]
            [compojure.route :as compojure.route]
            [taoensso.timbre :as timbre]
            [hiccup.page :as hiccup.page]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.util.response :as ring.response]
            [io.rkn.conformity :as conformity]
            [datomic.api :as d]
            [calderwood.util :as util]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.spec :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def ENV "DEV")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Datomic related

(defn ensure-migrations! [conn]
  (doseq [tx (util/get-migration-txes)]
    (conformity/ensure-conforms conn tx))
  conn)

(defn ensure-seeds! [conn]
  (doseq [tx (util/get-seed-txses (d/db conn))]
    (conformity/ensure-conforms conn tx)))

(defrecord TempDatomic [db-uri]
  Lifecycle
  (start [component]
    (d/delete-database db-uri)
    (d/create-database db-uri)
    (ensure-migrations! (d/connect db-uri))
    (ensure-seeds! (d/connect db-uri))
    component)
  (stop [component]
    (d/delete-database db-uri)
    component))

(defn temp-datomic [db-uri]
  (TempDatomic. db-uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Websocket Channels

(defrecord WSChannels [channels])

(defn ws-channels []
  (WSChannels. (atom {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Web Server

(defrecord HTTPKitWebServer [port app-handler stop-fn]
  Lifecycle
  (start [component]
    (if-not stop-fn
      (assoc component :stop-fn (http-kit/run-server
                                 (:handler app-handler)
                                 {:port port}))
      component))
  (stop [component]
    (if stop-fn
      (do (stop-fn)
          (assoc component :stop-fn nil))
      component)))

(defn http-kit-web-server [port]
  (map->HTTPKitWebServer {:port port}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Command Queue

(defprotocol CommandQueue
  (put-command [queue cmd]))

(defrecord LocalCommandQueue [queue]
  CommandQueue
  (put-command [component cmd]
    (.put (:queue component) cmd)))

(defn local-command-queue [buffer-size]
  (LocalCommandQueue. (java.util.concurrent.ArrayBlockingQueue. buffer-size)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Command Processor

(defn transact-with-exponential-backoff-retry! [conn running? txes]
  (let [max-backoff-ms 64000
        rand-sleep-ms 1000]
    (loop [backoff-time 1]
      (when @running?
        (or (try @(d/transact conn txes)
                 (catch java.util.concurrent.ExecutionException e
                   ;; Log an application error here, not a good
                   ;; idea to try again since it won't help.
                   (timbre/error e)
                   true)
                 (catch clojure.lang.ExceptionInfo e
                   ;; Log transaction timeout/unavailable here
                   ;; exponential backoff and it might eventually
                   ;; reconnect.
                   (timbre/error e)
                   nil))
            (let [backoff-time (min backoff-time max-backoff-ms)
                  backoff-time* (+ backoff-time (rand-int rand-sleep-ms))]
              (timbre/warn "Transact failed, retrying again in:"
                           backoff-time*
                           "ms")
              (Thread/sleep backoff-time*)
              (recur (* 2 backoff-time))))))))

(defrecord CommandProcessor [local-command-queue datomic running?]
  Lifecycle
  (start [component]
    (reset! running? true)
    (.start (Thread. (fn []
                       (timbre/debug "Starting Command Processor thread...")
                       (while @running?
                         (try
                           (let [conn (d/connect (:db-uri datomic))
                                 db (d/db conn)
                                 cmd (.poll (:queue local-command-queue)
                                            1000
                                            java.util.concurrent.TimeUnit/MILLISECONDS)]
                             (when cmd
                               (timbre/info "Command received:" cmd)
                               (let [event-txes (mapv (partial aggregate-event db)
                                                      (handle-command db cmd))]

                                 (doseq [e-tx event-txes]
                                   (timbre/debug "Transacting event:" e-tx)
                                   (transact-with-exponential-backoff-retry! conn running? e-tx)))))
                           (catch Exception err
                             (timbre/error "Exception in Command Processor:" err)))))))
    component)
  (stop [component]
    (reset! running? false)
    component))

(defn command-processor []
  (CommandProcessor. nil nil (atom false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Update Handler

(defrecord UpdateHandler [ws-channels datomic running?]
  Lifecycle
  (start [component]
    (reset! running? true)
    (.start (Thread.
             (fn []
               (timbre/debug "Starting Update Handler thread...")
               (while @running?
                 (try
                   (let [conn      (d/connect (:db-uri datomic))
                         txr       (d/tx-report-queue conn)
                         tx-report (.poll txr
                                          1000
                                          java.util.concurrent.TimeUnit/MILLISECONDS)]
                     (when tx-report
                       (timbre/debug "Received update:" tx-report)
                       (let [{:keys [db-after tx-data]} tx-report
                             tx                         (d/t->tx (d/basis-t db-after))
                             {:keys [event/uuid
                                     event/name
                                     event/data
                                     event/meta
                                     event/client-id
                                     event/client-seq]
                              :as tx-entity}            (d/entity db-after tx)]
                         (when (:event/name tx-entity)
                           (timbre/debug "Update transaction entity:" (d/touch tx-entity))
                           (doseq [channel (vals @(:channels ws-channels))]
                             (http-kit/send! channel
                                             (pr-str [:update
                                                      client-id
                                                      client-seq
                                                      {:event/uuid uuid
                                                       :event/name name
                                                       :event/data (clojure.edn/read-string data)
                                                       :event/meta (clojure.edn/read-string meta)
                                                       :event/client-seq client-seq}])))))))
                   (catch Exception err
                     (timbre/error "Exception in Update Handler:" err)))))))
    component)
  (stop [component]
    (reset! running? false)
    component))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Login / Console Handlers

(defn update-handler []
  (UpdateHandler. nil nil (atom false)))

(defn not-authorized [body]
  {:status  401
   :headers {}
   :body    body})

(defn console-handler [request]
  (if (:identity request)
    (hiccup.page/html5
     {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:http-equiv "x-ua-compatible"
              :content "ie=edge"}]
      [:meta {:name "viewport"
              :content "width=device-width, initial-scale=1"}]
      [:link {:rel "stylesheet" :type "text/css" :href "/css/bulma.css"}]]
     [:body
      [:div {:id "console" :class "full-height"}]
      [:script {:src "/js/console.js"}]])
    (ring.response/redirect "/login" :see-other)))

(defn view-login-handler [request]
  (if (:identity request)
    (ring.response/redirect "/console" :see-other)
    (let [error? (-> request :params :error)]
      (hiccup.page/html5
       {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:http-equiv "x-ua-compatible"
                :content "ie=edge"}]
        [:meta {:name "viewport"
                :content "width=device-width, initial-scale=1"}]
        [:link {:rel "stylesheet" :type "text/css" :href "/css/bulma.css"}]]
       [:body
        [:div {:class "content"
               :style "margin: 2em auto; width: 50%;"}
         [:h1 "Calderwood Login"]
         (when error?
           [:span {:class "help is-danger"} "Invalid email or password."])
         [:hr]

         [:form {:action "/login" :method "post"}
          [:label {:class "label"} "Email"]
          [:p {:class "control"}
           [:input {:class "input"
                    :type "email"
                    :name "email"}]]
          [:label {:class "label"} "Password"]
          [:p {:class "control"}
           [:input {:class "input"
                    :type "password"
                    :name "password"}]]

          [:button {:class "button is-primary"} "Go!"]]]]))))

(defn user-credentials-valid? [db user password]
  (util/password-hash-valid? password (:user/password-digest user)))

(defn create-session-tx-map [user-uuid remote-address]
  {:db/id (d/tempid :db.part/user)
   :session/uuid (d/squuid)
   :session/user [:user/uuid user-uuid]
   :session/remote-address remote-address})

(defn create-session! [conn user-uuid remote-address]
  (let [session-tx-map (create-session-tx-map user-uuid remote-address)
        session-uuid (:session/uuid session-tx-map)
        db-after (:db-after @(d/transact conn [session-tx-map]))]
    (d/entity db-after [:session/uuid session-uuid])))

(defn login-handler [{:keys [db conn params] :as request}]
  (let [{:keys [email password]} params
        email* (-> email
                   (string/lower-case)
                   (string/trim))]
    (if-let [user (d/entity db [:user/email email*])]
      (if (user-credentials-valid? db user password)
        (let [session (create-session! conn
                                       (:user/uuid user)
                                       (:remote-addr request))]
          (-> (ring.response/redirect "/console" :see-other)
              (assoc-in [:session :session-uuid] (:session/uuid session))
              (update :session (fn [s] (vary-meta s assoc :recreate true)))))
        (ring.response/redirect "/login?error=true" :see-other))
      (ring.response/redirect "/login?error=true" :see-other))))

(defn logout-handler [request]
  (-> (ring.response/redirect "/login" :see-other)
      (assoc :session nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Websocket Handler

(defn ws-handler [ws-channels command-queue request]
  (timbre/debug request)
  (if-let [id (:identity request)]
    (http-kit/with-channel request channel
      (timbre/debug "Websocket channel opened for user id:" id)
      (swap! (:channels ws-channels) assoc id channel)
      (http-kit/on-close channel (fn [status]
                                   (timbre/debug "Websocket channel closed for user id:" id)
                                   (swap! (:channels ws-channels) dissoc id)))
      (http-kit/on-receive channel (fn [msg]
                                     (timbre/debug "Received WS message:" msg)
                                     (let [[tp client-id client-seq data] (clojure.edn/read-string msg)]
                                       (when (and (= tp :cmd)
                                                  client-id
                                                  client-seq)
                                         (let [[ok-or-err cmd err]
                                               (-> data
                                                   (assoc :command/user-uuid id
                                                          :command/client-id client-id
                                                          :command/client-seq client-seq)
                                                   (coerce-command))]
                                           (if (= :error ok-or-err)
                                             (http-kit/send! channel (pr-str [:error
                                                                              client-id
                                                                              client-seq
                                                                              err]))
                                             (do (put-command command-queue cmd)
                                                 (http-kit/send! channel (pr-str [:cmd-ack
                                                                                  client-id
                                                                                  client-seq]))))))))))
    (not-authorized "Not authorized")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Query Handler

(defn bad-request []
  {:status 400
   :headers {}
   :body "Bad request."})

(defn edn-response [data]
  {:status 200
   :headers {"Content-Type" "application/edn"}
   :body (pr-str data)})

(defn query-handler [request]
  (if-let [id (:identity request)]
    (if-let [query (try (util/input-stream->edn (:body request))
                        (catch Exception e
                          (timbre/error "Error parsing query body:" e)
                          nil))]
      (let [[ok-or-error query* :as query-result] (coerce-query (assoc query
                                                                      :query/user [:user/uuid id]))]
        (if (= :error ok-or-error)
          (edn-response query-result)
          (edn-response [:ok (do-query (:db request)
                                       query*)])))
      (bad-request))
    (not-authorized)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Ring Middleware

(defn wrap-impersonate [handler]
  (when (= ENV "DEV")
    (fn [{:keys [db query-params] :as request}]
      (if-let [user (d/entity db [:user/email (get query-params "login-email")])]
        (handler (assoc request :identity (:user/uuid user)))
        (handler request)))))

(defn wrap-identity [handler]
  (fn [{:keys [db session] :as request}]
    (if-let [session (d/entity db [:session/uuid (:session-uuid session)])]
      (handler (assoc request :identity (-> session :session/user :user/uuid)))
      (handler request))))

(defn wrap-components [handler datomic ws-channels]
  (fn [request]
    (let [conn (d/connect (:db-uri datomic))]
      (handler (assoc request
                      :db  (d/db conn)
                      :conn conn
                      :ws-channels (:channels ws-channels))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; App Handler

(defn create-handler [datomic command-queue ws-channels]
  (-> (compojure/routes
       (compojure/GET "/console" request (console-handler request))
       (compojure/GET "/ws" request (ws-handler ws-channels
                                                command-queue
                                                request))
       (compojure/POST "/query" request (query-handler request))
       (compojure/GET "/login" request (view-login-handler request))
       (compojure/POST "/login" request (login-handler request))
       (compojure/GET "/logout" request (logout-handler request))
       (compojure.route/resources "/"))
      (wrap-impersonate)
      (wrap-identity)
      (wrap-session)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-components datomic ws-channels)))

(defrecord AppHandler [datomic command-queue ws-channels handler]
  Lifecycle
  (start [component]
    (if-not handler
      (assoc component :handler (create-handler datomic command-queue ws-channels))
      component))
  (stop [component]
    (assoc :handler nil)))

(defn app-handler []
  (map->AppHandler {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; System

(defrecord DevSystem [datomic
                      local-command-queue
                      ws-channels
                      app-handler
                      http-kit-web-server
                      update-handler
                      command-processor]
  Lifecycle
  (start [component]
    (let [datomic* (start datomic)
          app-handler* (-> app-handler
                           (assoc :datomic datomic*
                                  :ws-channels ws-channels
                                  :command-queue local-command-queue)
                           start)
          http-kit-web-server* (-> http-kit-web-server
                                   (assoc :app-handler app-handler*)
                                   start)
          update-handler (-> update-handler
                             (assoc :datomic datomic*
                                    :ws-channels ws-channels)
                             start)
          command-processor (-> command-processor
                                (assoc :datomic datomic*
                                       :local-command-queue local-command-queue)
                                start)]

      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (timbre/info "Shutdown hook")
                                   (stop component))))
      (assoc component
             :datomic datomic*
             :ws-channels ws-channels
             :app-handler app-handler*
             :http-kit-web-server http-kit-web-server*
             :update-handler update-handler
             :command-processor command-processor)))
  (stop [component]
    (timbre/info "Shutting down")
    (assoc component
           :datomic (stop datomic)
           :http-kit-web-server (stop http-kit-web-server)
           :update-hander (stop update-handler)
           :command-processor (stop command-processor))))

(defn dev-system [{:keys [db-uri]}]
  (map->DevSystem {:datomic (temp-datomic db-uri)
                   :ws-channels (ws-channels)
                   :local-command-queue (local-command-queue 1000)
                   :app-handler (app-handler)
                   :http-kit-web-server (http-kit-web-server 8080)
                   :update-handler (update-handler)
                   :command-processor (command-processor)}))
