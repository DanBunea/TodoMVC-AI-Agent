(ns ds.adapters.components.counter.counter-page
  "Counter page component that renders the counter using data-on-load."
  (:require
   [ds.adapters.components.html-page :as hp]

   [ds.adapters.handlers.context :as c]))

(defn counter-page
  "Counter page component that renders the counter using data-on-load."
  [ctx]
  [:html {:lang "en"}
   (hp/page-head)
   [:body
    [:div#cp1
     {:data-signals (c/ctx->data-signals ctx)}
     [:h1 "Counter Page"]
     [:div.counter-wrapper
      {:id "c1"
       :data-on-load (str "@post('/ds/nds/counter.counter/c1/init')")}]]]])

