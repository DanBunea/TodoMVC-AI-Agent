(ns ds.adapters.handlers.response
  (:require
   [charred.api :as charred]
   [dev.onionpancakes.chassis.compiler :as hc]
   [dev.onionpancakes.chassis.core :as h]
   [ring.util.response :as ruresp]
   [starfederation.datastar.clojure.api :as d*]))

(defn ->html [hiccup]
  (-> hiccup
      h/html
      hc/compile
      ruresp/response
      (ruresp/content-type "text/html; charset=UTF-8")))