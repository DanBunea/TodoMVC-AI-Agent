(ns ds.adapters.handlers.agent-output-handler
  (:require

   [logging.interface :as log]
   [agents.interface :refer [agents verbose-trace-event-names]]
   [agents.use-cases.chat.todomvc-agent :as todomvc-agent]
   [ds.adapters.handlers.connected-component-handler :refer [send-ui!]]
   [clojure.core.async :refer [go-loop go >! <! >!!]]
   [ds.adapters.components.assistant.common :as rcc :refer [loading-container]]
   [ds.adapters.components.assistant.reasoning :as reash]
   [ds.adapters.components.assistant.tool-components :as th]
   [ds.adapters.components.assistant.replies :as rh]))

(defn- destructure-event-trace [event-payload]
  (let [{:keys [event-trace]} event-payload
        chat-id (-> event-trace
                    :ai-chat/id)]
    (merge event-trace {:chat-id chat-id})))

(defn- handle-out-event [[event-name event-payload]]
  (let [mulog-ctx (:mulog/ctx event-payload)
        {:keys [chat-id]} (destructure-event-trace event-payload)
        trace-level (if (contains? verbose-trace-event-names event-name) :verbose :info)]
    (log/with-context mulog-ctx
      (log/trace-at
       trace-level
       ::handle-out-event
       [event-name (-> event-payload
                       (dissoc :mulog/ctx)
                       (update :event-trace dissoc :user))]

       (-> (case event-name

            ;;  :agents.domain.chat/chat-details-sent
            ;;  (rcc/chat-details event-payload)

             :agents.domain.chat/llm-reply-requested
             (rh/llm-reply-requested event-payload)

             :agents.domain.chat/partial-reasoning-reply-received
             (reash/partial-reasoning-reply event-payload)

             :agents.domain.chat/partial-llm-tools-reply-received
             (th/partial-llm-tools-reply event-payload)

             :agents.domain.chat/partial-llm-reply-received
             (rh/partial-llm-reply-received event-payload)

             :agents.domain.chat/llm-tools-reply-received
             (th/llm-tools-reply event-payload)

             :agents.domain.chat/tool-applied
             (th/tool-applied event-payload)

             :agents.domain.chat/llm-reply-received
             (rh/llm-reply-received event-payload)

             :agents.domain.chat/llm-error
             (let [display-msg (rh/llm-error-display-message event-payload)
                   msg {:id (str (:id event-payload))
                        :role "assistant"
                        :content display-msg}]
               (todomvc-agent/add-chat-message (str chat-id) msg)
               (rh/llm-error event-payload))

             ;; Unknown event - return empty effects
             (do (println "Unknown event received:" event-name "with payload:" event-payload)
                 []))
           ((fn send-replies-to-ui! [component-responses]
              #_(let [valid-component-responses (filter first component-responses)]
                  (doseq [[effect-name & effect-args] valid-component-responses]))
              (send-ui! (str chat-id) component-responses))))))))

;; Initialize the event listener when namespace is loaded
(defonce out-listener-started? (atom false))

(defn initialize-agent-listener! [agent-name]
  (when (compare-and-set! out-listener-started? false true)
    (log/trace
     ::initialize-out-listener!
     []
     (let [{:keys [!out-chan]} (get (agents) agent-name)]
       (go-loop []
         (when-let [msg (<! !out-chan)]
           (handle-out-event msg)
           (recur)))))))