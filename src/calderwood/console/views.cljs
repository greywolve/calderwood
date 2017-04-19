(ns calderwood.console.views
  (:require [taoensso.timbre :as timbre :refer-macros [log info warn debug infof warnf debugf]]
            [rum.core :as rum]
            [cljs.core.async :as async]))

(defn commands [state inputs]
  [:div
   [:p {:class "control"
        :style {:width "50%"}}
    [:textarea {:class (str "textarea "
                            (when-not (:command-text-valid? state)
                              "is-danger"))
                :on-change (fn [e]
                             (async/put! (:action-chan inputs)
                                         [:edit-command-text (-> e .-target .-value)]))
                :value (:command-text state)}]]
   [:button {:class (if (:loading? state)
                      "button is-primary is-loading"
                      "button is-primary")
             :on-click (fn [e] (async/put! (:action-chan inputs)
                                           [:submit-command (:command-text state)]))
             :disabled (or (not (:command-text-valid? state))
                           (:loading? state))}
    "Submit command"]
   [:hr]
   [:div {:class "content"}
    [:h2 "Live updates"]]
   [:table {:class "table"}
    [:thead
     [:tr [:th "Event"] [:th "Data"] [:th "Roundtrip (ms)"]]]
    [:tbody
     [:tr]
     (for [e (->> (:events state) (reverse) (take 20))]
       [:tr {:key (:key e)}
        [:td (str (:event/name e))]
        [:td [:table {:class "table is-bordered is-narrow is-striped"}
              [:tbody
               (for [[k v] (-> e
                               :event/data
                               sort)]
                 [:tr {:key k}
                  [:td (name k)] [:td (str v)]])]]]
        [:td (if-let [sent-ts (-> e :event/meta :client-sent-ts)]
               (- (-> e :event/meta :client-arrival-ts)
                  sent-ts)
               "n/a")]])]]])

(defn table [m]
  [:table {:class "table is-bordered is-narrow is-striped"}
   [:tbody
    (for [[k v] (-> m
                    sort)]
      [:tr {:key k}
       [:td (str k)] [:td (if (map? v)
                             (table v)
                             (str v))]])]])

(defn queries [state inputs]
  [:div
   [:p {:class "control"
        :style {:width "50%"}}
    [:textarea {:class (str "textarea "
                            (when-not (:query-text-valid? state)
                              "is-danger"))
                :on-change (fn [e]
                             (async/put! (:action-chan inputs)
                                         [:edit-query-text (-> e .-target .-value)]))
                :value (:query-text state)}]]
   [:button {:class (if (:loading? state)
                      "button is-primary is-loading"
                      "button is-primary")
             :on-click (fn [e] (async/put! (:action-chan inputs)
                                           [:submit-query (:query-text state)]))
             :disabled (or (not (:query-text-valid? state))
                           (:loading? state))}
    "Submit query"]
   [:hr]
   (when-let [qrt (:query-roundtrip-ms state)]
     [:p (str "Query time: " qrt " ms")])
   [:table {:class "table"}
    [:tbody
     [:tr]
     (for [[n qr] (map-indexed (fn [n qr]
                             [n qr])
                           (:query-result state))]
       [:tr {:key n}
        [:td (table qr)]])]]])

(rum/defc app [state inputs]
  (info (:active-view state))
  [:div {:class "section"}
   (when-let [error (:error state)]
     [:div {:class "notification is-danger"
            :style {:position "fixed"
                    :top 0
                    :left 0
                    :width "100%"}}
      [:button {:class "delete"
                :on-click (fn [e]
                            (async/put! (:action-chan inputs)
                                        [:dismiss-error]))}]
      (str "Error " error)])
   (when (:success? state)
     [:div {:class "notification is-success"
            :style {:position "fixed"
                    :z-index 99999
                    :top 0
                    :left 0
                    :width "100%"}}
      [:button {:class "delete"
                :on-click (fn [e]
                            (async/put! (:action-chan inputs)
                                        [:dismiss-success]))}]
      "Success!"])
   [:div {:style {:margin-top "1em"}}
    [:h1 {:class "title"} "Calderwood Console"]]
   [:hr]
   [:div {:class "columns"}
    ;; Menu
    [:div {:class "column is-2"}
     [:aside {:class "menu"}
      [:p {:class "menu-label"} "Menu"]
      [:ul {:class "menu-list"}
       [:li [:a {:href "#/commands"} "Commands and Events"]]
       [:li [:a {:href "#/queries"} "Queries"]]]]]
    ;; Content
    [:div {:class "column"}
     (case (:active-view state)
       :commands
       (commands state inputs)
       :queries
       (queries state inputs)
       nil)]]])
