(ns ds.adapters.components.assistant.common
  (:require
   [ds.adapters.components.assistant.tool-components :as th]
   [ds.adapters.components.assistant.replies :as rh]))

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