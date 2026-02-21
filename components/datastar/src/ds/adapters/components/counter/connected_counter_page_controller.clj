(ns ds.adapters.components.counter.connected-counter-page-controller)

;; (def machine
;;   {:id :connected-counter-page
;;    :initial :displayed
;;    :states {:displayed {:on {:LOAD {:target :displayed}
;;                              :INIT {:target :displayed
;;                                     :actions (fn [ctx _]
;;                                                (assoc-in ctx [:memory :count] 0))}
;;                              :INCREMENT {:target :displayed
;;                                          :actions (fn [ctx _]
;;                                                     (update-in ctx [:memory :count] inc))}}}}})

(def machine
  {:id :connected-counter-page
   :initial :displayed
   :states {:displayed {}}})