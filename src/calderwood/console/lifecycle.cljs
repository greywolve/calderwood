(ns calderwood.console.lifecycle)

(defprotocol Lifecycle
  (start [this])
  (stop [this]))
