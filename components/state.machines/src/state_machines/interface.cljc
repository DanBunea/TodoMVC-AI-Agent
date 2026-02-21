(ns state-machines.interface
  (:require
   [state-machines.hierarchical-state-machine :as hsm]))

(defn transition
  ([machine from-state [event-name event-params]]
   (hsm/transition machine from-state [event-name event-params]))
  ([machine from-state [event-name event-params] opts]
   (hsm/transition machine from-state [event-name event-params] opts)))