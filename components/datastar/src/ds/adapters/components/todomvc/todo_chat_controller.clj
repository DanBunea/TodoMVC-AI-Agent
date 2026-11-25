(ns ds.adapters.components.todomvc.todo-chat-controller
  "State machine controller for the todo chat component.
   Manages chat message loading and sending for a specific chat.
   Reads/writes chat data from the shared !db atom in todomvc-agent."
  (:require
   [clojure.string :as str]
   [clojure.core.async :refer [go >!]]
   [logging.interface :as log]
   [agents.interface :refer [agents]]
   [agents.use-cases.chat.todomvc-agent :as todomvc-agent]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.state-machines.interface :as sm])
  (:import (java.util UUID)))

(defn- generate-uuid
  "Generates a random UUID string."
  []
  (str (UUID/randomUUID)))

(defn- agent-reply
  "A server event from the agent: its params ({:agent-event :payload}) go in the
   ctx for the render fn, which turns them into patches."
  [ctx params]
  (assoc ctx :agent-reply params))

(def machine
  {:id :todo-chat
   :initial :init
   ;; The agent's output, as the server events
   ;; `ds.adapters.handlers.agent-output-handler` sends to the chat's flow.
   ;; A server event has no request and no signals: it starts from :init, so
   ;; they are handled here, at the machine level, in any state.
   :on {:LLM_REPLY_REQUESTED              {:actions agent-reply}
        :PARTIAL_REASONING_REPLY_RECEIVED {:actions agent-reply}
        :PARTIAL_LLM_TOOLS_REPLY_RECEIVED {:actions agent-reply}
        :PARTIAL_LLM_REPLY_RECEIVED       {:actions agent-reply}
        :LLM_TOOLS_REPLY_RECEIVED         {:actions agent-reply}
        :TOOL_APPLIED                     {:actions agent-reply}
        :LLM_REPLY_RECEIVED               {:actions agent-reply}}
   :states
   {:init
    {:on {:INIT {:target :loading-messages}}}

    :loading-messages
    {:invoke {:src (fn [ctx]
                     (let [chat-id (get-in ctx [:memory :chat-id])
                           user-id (get-in ctx [:logged-user :user/id])
                           chat (todomvc-agent/read-chat user-id chat-id)]
                       (if chat
                         (:messages chat)
                         [])))
              :on-done {:target :displayed
                        :actions (fn [ctx result]
                                   (assoc-in ctx [:memory :messages] result))}}}

    :displayed
    {:on
     {:INIT {:target :loading-messages}

      ;; a completion that ends after a reconnect reloaded the chat
      :COMPLETION_DONE {:target :loading-messages}

      :SEND_MESSAGE {:target :sending-message
                     :actions (fn [ctx event-params]
                                (-> ctx
                                    (assoc :event-params event-params)
                                    (assoc-in [:memory :current-message-id] (generate-uuid))))}}}

    :sending-message
    {:invoke {:src (fn [ctx]
                     (let [logged-user (:logged-user ctx)
                           user-id (:user/id logged-user)
                           chat-id (get-in ctx [:memory :chat-id])
                           chat-input-text (or (:chatInputText (:event-params ctx))
                                               "")
                           chat (todomvc-agent/read-chat user-id chat-id)]
                         (if (and chat (not (str/blank? chat-input-text)))
                         (let [new-message {:id (get-in ctx [:memory :current-message-id])
                                            ;; :text (str/trim chat-input-text)
                                            :content (str/trim chat-input-text)
                                            :timestamp (System/currentTimeMillis)
                                            :role "user"}
                               updated-messages (conj (:messages chat) new-message)
                               updated-chat (assoc chat :messages updated-messages)
                               all-chats (todomvc-agent/read-chats user-id)
                               updated-chats (mapv (fn [c]
                                                     (if (= (str (:id c)) (str chat-id))
                                                       updated-chat
                                                       c))
                                                   all-chats)]
                           (todomvc-agent/write-chats user-id updated-chats)
                           (go
                             (>! (:!in-chan (:todomvc (agents)))
                                 [:agents.domain.chat/user-message-sent
                                  {:mulog/ctx (log/local-context)
                                   :content (str/trim chat-input-text)
                                   :id (:id new-message)
                                   :event-trace {:ai-chat/id chat-id
                                                 :user logged-user
                                                 :message-id (:id new-message)}}]))
                           updated-messages)
                         (if chat (:messages chat) []))))
              :on-done {:target :completion-ongoing
                        :actions (fn [ctx result]
                                   (-> ctx
                                       (assoc-in [:memory :messages] result)
                                       (dissoc :event-params)))}}}

    :completion-ongoing
    {:on
     {:COMPLETION_DONE {:target :loading-messages}

      ;; the reconnect watchdog re-sends INIT in whatever state the chat is
      :INIT {:target :loading-messages}

      :STOP_COMPLETION_REQUESTED {:target :loading-messages
                                  :actions (fn [ctx _]
                                             (let [logged-user (:logged-user ctx)
                                                   chat-id     (get-in ctx [:memory :chat-id])
                                                   message-id  (get-in ctx [:memory :current-message-id])]
                                               (when message-id
                                                 (go (>! (:!in-chan (:todomvc (agents)))
                                                         [:agents.domain.chat/stop-requested
                                                          {:mulog/ctx   (log/local-context)
                                                           :id          (generate-uuid)
                                                           :event-trace {:ai-chat/id chat-id
                                                                         :user        logged-user
                                                                         :message-id  message-id}}])))
                                               ctx))}}}}})

(tests
 "an agent server event starts from :init (no signals) and puts its params in
  the ctx for the render, staying where it is"
 (sm/transition machine
                {:state :init :memory {}}
                [:PARTIAL_LLM_REPLY_RECEIVED {:agent-event :agents.domain.chat/partial-llm-reply-received
                                              :payload     {:id "m1"}}]
                {:state-key :state})
 := {:state       :init
     :memory      {}
     :agent-reply {:agent-event :agents.domain.chat/partial-llm-reply-received
                   :payload     {:id "m1"}}})
