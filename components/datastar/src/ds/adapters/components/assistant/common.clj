(ns ds.adapters.components.assistant.common
  (:require
   [ds.adapters.components.assistant.tool-components :as th]
   [ds.adapters.components.assistant.replies :as rh]))

(defn loading-container [chat-id]
  [:div.cs-12.cl-12.ac
   {:id (str "loading-container-" chat-id)
    :style {:order 9999}}]) ;; CSS order ensures it's always last in flex container

(defn- shimmer-effect [text]
  (map-indexed
   (fn [idx char]
     [:span.shimmer-effect
      {:key idx
       :style {:animation-delay (str (* idx 0.05) "s")}}
      (if (= char \space) "\u00A0" char)])
   text))

;; Loading content - the spinner and text shown when loading
(defn loading-content []
  [:div
   {:style {:display "flex"
            :align-items "center"}}

   #_[:span.material-symbols-outlined.secondary
      {:style {:display "inline-block"
               :animation-name "spin"
               :animation-duration "1s"
               :animation-timing-function "linear"
               :animation-iteration-count "infinite"}}
      "progress_activity"]
   [:span.secondary
    (shimmer-effect "Preparing next steps")]])

(defn chat-details [{:keys [messages] :as event-payload}]
  (for [{:keys [id role content]} messages]
    (cond
      (some #{role} ["user" "assistant"])
      (rh/message id role content)

      (= role "tool_call")
      (th/message-tool-call (str id "-" (:id content)) content)

      #_(= role "tool_call_output")
      #_(message-tool-call-output (str id "-" (:id content)) content))))
;; )