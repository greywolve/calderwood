(ns calderwood.lifecycle)

(defprotocol Lifecycle
  (start [this])
  (stop [this]))
