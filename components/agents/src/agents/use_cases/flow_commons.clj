(ns agents.use-cases.flow-commons
  (:require
   [clojure.core.async.flow :as flow]))

(defn init [arg-map]
  (assoc arg-map :ready true))

(defn transition [state transition-step]
  (case transition-step
    ::flow/resume
    (assoc state :ready true)

    ::flow/pause
    (assoc state :ready false)

    ::flow/stop
    (assoc state :ready false)

    state))
