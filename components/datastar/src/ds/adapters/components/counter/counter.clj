(ns ds.adapters.components.counter.counter
  "Counter component for testing the dynamic component endpoint mechanism."
  (:require
   [ds.adapters.handlers.context :as c]))

(defn counter [{:keys [component-id] :as ctx}]
  [:div.counter-container
   {:id component-id
    :data-signals (c/ctx->data-signals ctx)}
   [:h3 "Counter Component"]
   [:div.count-display
    {:style {:font-size "24px"
             :font-weight "bold"
             :margin "20px 0"}}
    (str "Count: " (get-in ctx [:memory :count]))]
   [:button.btn.contained
    {:data-on-click (str "@post('/ds/nds/counter.counter/" component-id "/increment')")
     :type "button"
     :style {:padding "10px 20px"
             :font-size "16px"}}
    "Increment"]])

