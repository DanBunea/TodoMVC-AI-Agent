(ns ds.adapters.components.counter.counter-page-controller
  "State machine controller for the counter page component.")

(def machine
  {:id :counter-page
   :initial :displayed
   :states {:displayed {}}})

