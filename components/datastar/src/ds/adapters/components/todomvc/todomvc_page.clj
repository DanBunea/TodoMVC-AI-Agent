(ns ds.adapters.components.todomvc.todomvc-page
  "TodoMVC page component that renders the full HTML page with a data-init
   trigger to initialize the todomvc component."
  (:require
   [ds.adapters.components.html-page :as hp]
   [taia.ds.datastar.interface :as ds]))

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
       {:data-signals (ds/ctx->data-signals (assoc ctx :component-id page-id))}
       [:div.todomvc-wrapper
        {:id component-id
         "data-init" (str "@post('/ds/nds/todomvc.todomvc/" component-id "/init')")}]]]]))
