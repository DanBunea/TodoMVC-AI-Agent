(ns ds.adapters.handlers.agent-output-handler
  "Bridges the agent's out-chan to the UI. Each agent output event becomes a
   server event (`ds/send-event!`) on the flow of the chat's open stream,
   [chat-id user-id]: the todo-chat controller takes it in and the todo-chat
   render fn turns it into patches. Nothing here builds markup."
  (:require
   [logging.interface :as log]
   [agents.interface :refer [agents verbose-trace-event-names]]
   [taia.ds.datastar.interface :as ds]
   [clojure.core.async :refer [go-loop <!]]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(def ui-event-names
  "Agent output event → the todo-chat server event that renders it."
  {:agents.domain.chat/llm-reply-requested              "llm_reply_requested"
   :agents.domain.chat/partial-reasoning-reply-received "partial_reasoning_reply_received"
   :agents.domain.chat/partial-llm-tools-reply-received "partial_llm_tools_reply_received"
   :agents.domain.chat/partial-llm-reply-received       "partial_llm_reply_received"
   :agents.domain.chat/llm-tools-reply-received         "llm_tools_reply_received"
   :agents.domain.chat/tool-applied                     "tool_applied"
   :agents.domain.chat/llm-reply-received               "llm_reply_received"})

(defn- destructure-event-trace [event-payload]
  (let [{:keys [event-trace]} event-payload
        chat-id (-> event-trace
                    :ai-chat/id)
        user-id (get-in event-trace [:user :user/id])]
    (merge event-trace {:chat-id chat-id
                        :user-id user-id})))

(defn- send-to-chat!
  "Sends the agent event to the chat's flow. Returns what `send-event!` returns
   (false: the chat has no open stream), or nil for an event the UI ignores."
  [chat-id user-id event-name event-payload]
  (if-let [ui-event (ui-event-names event-name)]
    (let [sent? (ds/send-event! (str chat-id) user-id ui-event
                                {:agent-event event-name
                                 :payload     (dissoc event-payload :mulog/ctx)})]
      (when-not sent?
        (log/trace ::chat-not-connected
                   [:chat-id chat-id :user-id user-id :event-name event-name
                    :connections (ds/connections)]
                   nil))
      sent?)
    (log/trace ::unknown-agent-event [:event-name event-name] nil)))

(defn- handle-out-event [[event-name event-payload]]
  (let [mulog-ctx (:mulog/ctx event-payload)
        {:keys [chat-id user-id]} (destructure-event-trace event-payload)
        trace-level (if (contains? verbose-trace-event-names event-name) :verbose :info)]
    (log/with-context mulog-ctx
      (log/trace-at
       trace-level
       ::handle-out-event
       [event-name (-> event-payload
                       (dissoc :mulog/ctx)
                       (update :event-trace dissoc :user))]
       ;; send-event! carries this trace into the chat's flow
       (send-to-chat! chat-id user-id event-name event-payload)))))

(tests
 "an agent event goes to the chat's flow as its server event, the payload
  (without the trace) as params"
 (with-redefs [ds/send-event! (spy/stub true)]
   (send-to-chat! "chat-1" "u1" :agents.domain.chat/partial-llm-reply-received
                  {:id "m1" :reply "hi" :mulog/ctx {:t 1}})
   := true
   (spy/called-once-with? ds/send-event! "chat-1" "u1" "partial_llm_reply_received"
                          {:agent-event :agents.domain.chat/partial-llm-reply-received
                           :payload     {:id "m1" :reply "hi"}})
   := true)

 "the chat has no open stream → false (and it is logged)"
 (with-redefs [ds/send-event! (spy/stub false)
               ds/connections (spy/stub {})]
   (send-to-chat! "chat-1" "u1" :agents.domain.chat/tool-applied {}) := false)

 "an event the UI does not render is not sent"
 (with-redefs [ds/send-event! (spy/spy)]
   (send-to-chat! "chat-1" "u1" :agents.domain.chat/something-else {}) := nil
   (spy/not-called? ds/send-event!) := true)

 "handle-out-event: the chat and user come from the event trace"
 (with-redefs [send-to-chat! (spy/stub true)]
   (handle-out-event [:agents.domain.chat/tool-applied
                      {:id "m1" :event-trace {:ai-chat/id "chat-1" :user {:user/id "u1"}}}])
   (spy/called-once-with? send-to-chat! "chat-1" "u1" :agents.domain.chat/tool-applied
                          {:id "m1" :event-trace {:ai-chat/id "chat-1" :user {:user/id "u1"}}})
   := true))

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
           (try
             (handle-out-event msg)
             (catch Throwable e
               (log/trace ::handle-out-event-failed [:error (ex-message e)] nil)))
           (recur)))))))
