(ns ds.adapters.components.counter.counter-controller
  "State machine controller for the counter component.")

(def machine
  {:id :counter
   :initial :displayed
   :states {:displayed {:on {:INIT {:target :displayed
                                    :actions (fn [ctx _]
                                               (assoc-in ctx [:memory :count] 0))}
                             :INCREMENT {:target :displayed
                                         :actions (fn [ctx _]
                                                    (update-in ctx [:memory :count] inc))}}}}})

