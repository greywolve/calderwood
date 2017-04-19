(ns calderwood.console.diode
  (:require [cljs.core.async :as async]
            [calderwood.console.lifecycle :refer [Lifecycle]]
            [taoensso.timbre :as timbre :refer-macros [log info warn debug infof warnf debugf]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defmulti action
  (fn [state [action-type _]]
    action-type))

(defmulti reaction
  (fn [state inputs components [action-type _]]
    action-type))

(defmethod reaction :default
  [state inputs components [action-type _]]
  (timbre/debugf "No reaction for %s" action-type))

(defn update-state [state action-fn action]
  (-> (action-fn state action)
      (update :version inc)))

(defn perform-action [state action*]
  (timbre/debugf "Action: %s" action*)
  (action state action*))

(defn fire-reactions! [state inputs components action]
  (timbre/debugf "Reaction for: %s" action)
  (reaction state inputs components action))

(defrecord DiodeRenderLoop [state-atom running? inputs render-fn components debug-atom]
  Lifecycle
  (start [component]
    (when-not @running?
      (let [{:keys [action-chan]} inputs
            current-state @state-atom]
        (reset! running? true)
        (swap! debug-atom assoc :initial-state current-state)
        (render-fn current-state inputs)

        (go-loop [action (<! action-chan)]
          (when (and @running? action)
            (let [new-state (swap! state-atom update-state perform-action action)]
              (render-fn new-state inputs)
              (swap! debug-atom (fn [s] (update s :past-actions conj action)))
              (fire-reactions! new-state inputs components action))
            (recur (<! action-chan))))))
    component)
  (stop [_]
    (reset! running? false)
    (doseq [i (vals inputs)]
      (async/close! i))))

(defn init! [state-atom inputs render-fn components & {:keys [debug-atom]}]
  (map->DiodeRenderLoop
   {:state-atom state-atom
    :inputs inputs
    :render-fn render-fn
    :running? (atom false)
    :debug-atom debug-atom
    :components components}))
