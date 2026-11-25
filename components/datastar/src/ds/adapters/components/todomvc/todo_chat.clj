(ns ds.adapters.components.todomvc.todo-chat
  "Chat messages component - renders chat messages and input for a specific chat.
   A connected component: the todomvc chat modal overlay opens its connection
   (an SSE stream and a flow) with @get on /ds/nds-connect/…; the chat's own
   events are one-shot POSTs to /ds/nds/…, and the agent's output arrives as
   server events through the connection's flow
   (`ds/send-event!`, see `ds.adapters.handlers.agent-output-handler`) whose
   render is an effects list pushed down the stream."
  (:require
   [charred.api :as charred]
   [clojure.string]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]
   [ds.adapters.components.assistant.loading :refer [loading-container loading-content]]
   [ds.adapters.components.assistant.reasoning :as reash]
   [ds.adapters.components.assistant.tool-components :as th]
   [ds.adapters.components.assistant.replies :as rh]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- endpoint
  "Builds the endpoint path for a todo-chat event: a one-shot /ds/nds/ event.
   (A request on the nds-connect path would open a connection of its own and
   take over the one the agent's server events reach.)"
  [component-id event]
  (str "/ds/nds/todomvc.todo-chat/" component-id "/" event))

(defn- post-action
  "Builds a DataStar @post expression for a todo-chat event."
  [component-id event]
  (str "@post('" (endpoint component-id event) "')"))

;; ---------------------------------------------------------------------------
;; Signals
;; ---------------------------------------------------------------------------

(defn- chat-signals
  "Builds the data-signals JSON string for the chat component.
   Includes the component-level signals (memory + state) under the component-id,
   plus a top-level chatInputText signal for the input field."
  [{:keys [state memory component-id]}]
  (charred/write-json-str
   (merge
    {component-id (merge memory {:state state})}
    {"chatInputText" ""})))

;; ---------------------------------------------------------------------------
;; Main component
;; ---------------------------------------------------------------------------

(defn- chat-view
  "Renders the chat messages area with input.
   Expects ctx with :memory containing :messages, :chat-id, and :parent-component-id."
  [{:keys [component-id] :as ctx}]
  (let [chat-messages (get-in ctx [:memory :messages] [])
        completing?   (= (:state ctx) :completion-ongoing)]
    [:div {:id component-id
           :data-signals (chat-signals ctx)
           :style {:flex "1" :display "grid" :grid-template-rows "1fr auto" :overflow "hidden"}}
     ;; Messages area
     [:div {:style {:overflow-y "auto" :padding "10px 20px"}}
      (if (seq chat-messages)
        [:div#messages.grid
         {:style {:grid-gap "0px"}}
         ;; while the agent works the indicator is part of the render, so a
         ;; render that lands after the agent's own show-loading keeps it
         (if completing?
           (conj (loading-container component-id) (loading-content))
           (loading-container component-id))
         (into []
               (keep
                (fn [message]
                  (let [{:keys [id role content]} message]
                    (when-let [el (cond
                                    (some #{role} ["user" "assistant"])
                                    (rh/message-view id role content)

                                    (= role "tool_call")
                                    (th/tools-called {:id (str id "-" (:id content)) :content content})

                                    :else nil)]
                      (update-in el [1] assoc :key (if (= role "tool_call")
                                                     (str id "-" (:id content))
                                                     id)))))
                chat-messages))]
        [:div.cl-12.cs-12 {:style {:text-align "center" :color "#999" :padding "40px 20px"}}
         [:div {:style {:font-size "40px" :margin-bottom "10px"}} "\uD83D\uDCAC"]
         "No messages yet. Start a conversation!"])]
     ;; Chat input
     [:div {:style {:padding "10px 20px" :border-top "1px solid #e6e6e6"
                    :display "flex" :align-items "center" :gap "10px"}}
      (let [input-id (str component-id "-chat-input")]
        [:input {:id          input-id
                 :type        "text"
                 "data-testid" "chat-input"
                 :placeholder "Type a message..."
                 :disabled    completing?
                 :style       {:flex "1" :padding "10px 16px" :border "1px solid #ddd"
                               :border-radius "20px" :font-size "14px" :outline "none"
                               :opacity (if completing? "0.5" "1")
                               :cursor  (if completing? "not-allowed" "text")}
                 "data-on:input" "$chatInputText=el.value"
                 "data-on:keydown" (str "if(evt.key==='Enter'&&el.value.trim()){"
                                       "el.value='';"
                                       (post-action component-id "send_message")
                                       "}")}])
      (when-not completing?
        [:button {:type "button"
                  "data-testid" "chat-send"
                  :style {:background "#4a90d9" :color "#fff" :border "none"
                          :border-radius "50%" :width "36px" :height "36px"
                          :font-size "16px" :cursor "pointer" :display "flex"
                          :align-items "center" :justify-content "center"
                          :flex-shrink "0"}
                  "data-on:click" (str "var inp=document.getElementById('"
                                      component-id "-chat-input');"
                                      "if(inp&&inp.value.trim()){"
                                      "$chatInputText=inp.value;"
                                      "inp.value='';"
                                      (post-action component-id "send_message")
                                      "}")}
         "\u27A4"])
      (when completing?
        [:button {:type    "button"
                  :title   "Stop"
                  "data-testid" "chat-stop"
                  :style   {:background "#e74c3c" :color "#fff" :border "none"
                            :border-radius "50%" :width "36px" :height "36px"
                            :font-size "16px" :cursor "pointer" :display "flex"
                            :align-items "center" :justify-content "center"
                            :flex-shrink "0"}
                  "data-on:click" (post-action component-id "stop_completion_requested")}
         "■"])]]))

(defn- agent-reply-effects
  "The effects for one agent output event (a server event; the controller put
   it in the ctx as :agent-reply)."
  [{:keys [agent-event payload]}]
  (case agent-event
    :agents.domain.chat/llm-reply-requested              (rh/llm-reply-requested payload)
    :agents.domain.chat/partial-reasoning-reply-received (reash/partial-reasoning-reply payload)
    :agents.domain.chat/partial-llm-tools-reply-received (th/partial-llm-tools-reply payload)
    :agents.domain.chat/partial-llm-reply-received       (rh/partial-llm-reply-received payload)
    :agents.domain.chat/llm-tools-reply-received         (th/llm-tools-reply payload)
    :agents.domain.chat/tool-applied                     (th/tool-applied payload)
    :agents.domain.chat/llm-reply-received               (rh/llm-reply-received payload)
    []))

(defn todo-chat
  "The chat's render fn. A server event from the agent (ctx has :agent-reply)
   renders as an effects list patched into the open chat; any other event
   renders the whole chat."
  [{:keys [agent-reply] :as ctx}]
  (if agent-reply
    (let [effects (agent-reply-effects agent-reply)]
      (if (seq effects)
        (vec effects)
        ;; nothing to show for this event: an empty effects list would be sent
        ;; as an empty element patch, so send an empty signals patch instead
        [[:patch-signals {} {}]]))
    (chat-view ctx)))

(tests
 "an agent server event renders as the effects its builder returns"
 (with-redefs [rh/partial-llm-reply-received (spy/stub [[:patch [:span "hi"] {}]])]
   (todo-chat {:component-id "c1"
               :agent-reply  {:agent-event :agents.domain.chat/partial-llm-reply-received
                              :payload     {:id "m1" :reply "hi"}}})
   := [[:patch [:span "hi"] {}]]
   (spy/called-once-with? rh/partial-llm-reply-received {:id "m1" :reply "hi"}) := true)

 "each agent event goes to its own builder"
 (with-redefs [rh/llm-reply-requested         (spy/stub [[:patch [:a] {}]])
               reash/partial-reasoning-reply  (spy/stub [[:patch [:b] {}]])
               th/partial-llm-tools-reply     (spy/stub [[:patch [:c] {}]])
               th/tool-applied                (spy/stub [[:patch [:d] {}]])
               rh/llm-reply-received          (spy/stub [[:patch [:e] {}]])]
   (mapv #(todo-chat {:agent-reply {:agent-event % :payload {}}})
         [:agents.domain.chat/llm-reply-requested
          :agents.domain.chat/partial-reasoning-reply-received
          :agents.domain.chat/partial-llm-tools-reply-received
          :agents.domain.chat/tool-applied
          :agents.domain.chat/llm-reply-received])
   := [[[:patch [:a] {}]] [[:patch [:b] {}]] [[:patch [:c] {}]] [[:patch [:d] {}]] [[:patch [:e] {}]]])

 "an agent event with nothing to show (or an unknown one) → an empty signals patch"
 (todo-chat {:agent-reply {:agent-event :agents.domain.chat/llm-tools-reply-received :payload {}}})
 := [[:patch-signals {} {}]]
 (todo-chat {:agent-reply {:agent-event :unknown :payload {}}})
 := [[:patch-signals {} {}]]

 "any other event renders the chat, its root div keyed by the component-id"
 (-> (todo-chat {:component-id "c1" :state :displayed :memory {:messages []}})
     (get-in [1 :id]))
 := "c1"

 "the input POSTs send_message as a one-shot /ds/nds/ event, not to the
  nds-connect path"
 (-> (todo-chat {:component-id "c1" :state :displayed :memory {:messages []}})
     str
     (clojure.string/includes? "@post('/ds/nds/todomvc.todo-chat/c1/send_message')"))
 := true

 "while the agent works, the loading indicator is rendered filled"
 (-> (todo-chat {:component-id "c1" :state :completion-ongoing
                 :memory {:messages [{:id "1" :role "user" :content "hi"}]}})
     str
     (clojure.string/includes? "shimmer-effect"))
 := true
 (-> (todo-chat {:component-id "c1" :state :displayed
                 :memory {:messages [{:id "1" :role "user" :content "hi"}]}})
     str
     (clojure.string/includes? "shimmer-effect"))
 := false)
