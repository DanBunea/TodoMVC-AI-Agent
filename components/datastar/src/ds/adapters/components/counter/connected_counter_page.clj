(ns ds.adapters.components.counter.connected-counter-page
  "Connected counter page component that inlines the counter UI
   and establishes an SSE connection on load for server-push updates."
  (:require
   [ds.adapters.components.html-page :as hp]
   [ds.adapters.handlers.context :as c]))

(defn connected-counter-page
  "Counter page component that renders the counter using data-on-load.
   The inner div connects via SSE and renders the counter in on-open."
  [{:keys [component-id] :as ctx}]
  [:html {:lang "en"}
   (hp/page-head)
   [:body
    [:div#cp1
     {:data-signals (c/ctx->data-signals ctx)}
     [:h1 "Connected Counter Page"]
     [:div.counter-wrapper
      {:id component-id
       :data-on-load (str "@get('/ds/nds-connect/counter.connected-counter/" component-id "/init')")}]]]])


