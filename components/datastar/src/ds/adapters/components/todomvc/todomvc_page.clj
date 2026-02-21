(ns ds.adapters.components.todomvc.todomvc-page
  "TodoMVC page component that renders the full HTML page with a data-on-load
   trigger to initialize the todomvc component."
  (:require
   [ds.adapters.components.html-page :as hp]
   [ds.adapters.handlers.context :as c]))

(defn todomvc-page
  "Page wrapper for the TodoMVC component. Renders the full HTML document
   with a body div that triggers the todomvc init event on load.
   Uses a page-specific ID for data-signals to avoid signal namespace
   collision with the todomvc component."
  [{:keys [component-id] :as ctx}]
  (let [page-id (str component-id "-page")]
    [:html {:lang "en"}
     (hp/page-head)
     [:body {:style {:background "#f5f5f5"
                     :font-family "'Helvetica Neue', Helvetica, Arial, sans-serif"
                     :min-height  "100vh"
                     :margin      "0"
                     :padding     "0"}}
      [:div#app
       {:data-signals (c/ctx->data-signals (assoc ctx :component-id page-id))}
       [:div.todomvc-wrapper
        {:id component-id
         :data-on-load (str "@post('/ds/nds/todomvc.todomvc/" component-id "/init')")}]]]]))
