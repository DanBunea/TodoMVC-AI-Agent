(ns ds.adapters.handlers.context
  (:require
   [charred.api :as charred]))

(defn ctx->data-signals [{:keys [state memory component-id] :as ctx}]
  (-> {}
      (assoc component-id (merge memory {:state state}))
      charred/write-json-str))