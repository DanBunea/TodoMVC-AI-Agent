(ns ds.adapters.components.todomvc.todo-chat
  "Chat messages component - renders chat messages and input for a specific chat.
   Loaded via data-on-load from the todomvc chat modal overlay."
  (:require
   [charred.api :as charred]
   [ds.adapters.handlers.agent-output-handler :as aoh]
   [ds.adapters.components.assistant.common :as rcc :refer [loading-container]]
   [ds.adapters.components.assistant.tool-components :as th]
   [ds.adapters.components.assistant.replies :as rh]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- endpoint
  "Builds the NDS endpoint path for a todo-chat event."
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

(defn todo-chat
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
         (loading-container component-id)
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
                 :placeholder "Type a message..."
                 :disabled    completing?
                 :style       {:flex "1" :padding "10px 16px" :border "1px solid #ddd"
                               :border-radius "20px" :font-size "14px" :outline "none"
                               :opacity (if completing? "0.5" "1")
                               :cursor  (if completing? "not-allowed" "text")}
                 :data-on-input "$chatInputText=el.value"
                 :data-on-keydown (str "if(event.key==='Enter'&&el.value.trim()){"
                                       "el.value='';"
                                       "@post('/ds/nds-connect/todomvc.todo-chat/" component-id  "/SEND_MESSAGE')"
                                       "}")}])
      (when-not completing?
        [:button {:type "button"
                  :style {:background "#4a90d9" :color "#fff" :border "none"
                          :border-radius "50%" :width "36px" :height "36px"
                          :font-size "16px" :cursor "pointer" :display "flex"
                          :align-items "center" :justify-content "center"
                          :flex-shrink "0"}
                  :data-on-click (str "var inp=document.getElementById('"
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
                  :style   {:background "#e74c3c" :color "#fff" :border "none"
                            :border-radius "50%" :width "36px" :height "36px"
                            :font-size "16px" :cursor "pointer" :display "flex"
                            :align-items "center" :justify-content "center"
                            :flex-shrink "0"}
                  :data-on-click (post-action component-id "stop_completion_requested")}
         "■"])]]))

(defn intialize-out-listener! []
  (aoh/initialize-agent-listener! :todomvc))
