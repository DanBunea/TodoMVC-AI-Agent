(ns ds.interface
  (:require
   [ds.adapters.handlers.default-component-handler :as ce]
   [ds.adapters.handlers.connected-component-handler :as cce]))

;; Dynamic component event handler
(def handler-component-event #'ce/default-handler)
;; Connected component handler (SSE)
(def handler-connected-component-connect #'cce/handler-connected-component-connect)