(ns ds.adapters.components.todomvc.todomvc
  "TodoMVC component - classic todo list with add, edit, toggle, delete, and filter.
   Renders the TodoMVC UI using hiccup with DataStar attributes for reactivity."
  (:require
   [ds.adapters.components.expandable :as exp]
   [charred.api :as charred]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- endpoint
  "Builds the NDS endpoint path for a todomvc event."
  [component-id event]
  (str "/ds/nds/todomvc.todomvc/" component-id "/" event))

(defn- post-action
  "Builds a DataStar @post expression for a todomvc event."
  [component-id event]
  (str "@post('" (endpoint component-id event) "')"))

(defn- filtered-todos
  "Returns the todos matching the current filter."
  [todos current-filter]
  (case current-filter
    "active"    (filterv (comp not :completed) todos)
    "completed" (filterv :completed todos)
    todos))

(defn- active-count
  "Returns the number of non-completed todos."
  [todos]
  (count (remove :completed todos)))

(defn- completed-count
  "Returns the number of completed todos."
  [todos]
  (count (filter :completed todos)))

;; ---------------------------------------------------------------------------
;; Signals
;; ---------------------------------------------------------------------------

(defn- todomvc-signals
  "Builds the data-signals JSON string for the TodoMVC component.
   Includes the component-level signals (memory + state) under the component-id,
   plus top-level signals for user inputs and transient action parameters."
  [{:keys [state memory component-id]}]
  (let [editing-id (get-in memory [:selected :item-id])
        todos      (:todos memory [])
        edit-text  (if editing-id
                     (:text (first (filter #(= (str (:id %)) (str editing-id)) todos)) "")
                     "")
        current-chat-id (get-in memory [:selected :current-chat-id])]
    (charred/write-json-str
     (merge
      {component-id (merge memory {:state state})}
      {"newTodoText" ""
       "editText"    edit-text
       "selected"    (merge {"item-id" ""}
                            (when current-chat-id {"current-chat-id" current-chat-id}))
       "actionFilter" ""
       "actionChatId" ""}))))

;; ---------------------------------------------------------------------------
;; Chat UI Components
;; ---------------------------------------------------------------------------

(defn- floating-chat-button
  "Renders a floating textbox button at the bottom of the screen.
   If there's already a current-chat-id in memory, reopens that chat;
   otherwise creates a new one."
  [component-id ctx]
  (let [current-chat-id (get-in ctx [:memory :selected :current-chat-id])
        click-action    (if current-chat-id
                          (str "$actionChatId='" (str current-chat-id) "';"
                               (post-action component-id "open_chat"))
                          (post-action component-id "new_chat"))]
    [:div {:style {:position   "fixed"
                   :bottom     "20px"
                   :left       "50%"
                   :transform  "translateX(-50%)"
                   :z-index    "1000"
                   :width      "300px"
                   :max-width  "90%"
                   :background "#fff"
                   :border     "1px solid #ddd"
                   :border-radius "25px"
                   :box-shadow "0 2px 10px rgba(0,0,0,0.1)"
                   :cursor     "pointer"
                   :transition "all 0.3s ease"}}
     [:input {:type        "text"
              :placeholder (if current-chat-id
                             "Continue your chat..."
                             "Ask AI to help with your todos...")
              :read-only   true
              :style       {:width        "100%"
                            :padding      "12px 20px"
                            :font-size    "14px"
                            :border       "none"
                            :border-radius "25px"
                            :outline      "none"
                            :background   "transparent"
                            :cursor       "pointer"}
              :data-on-click click-action}]]))

(defn- chat-list-view
  "Renders the list-of-chats content area."
  [component-id chats]
  [:div {:style {:flex "1" :overflow-y "auto" :padding "10px 20px"
                 :display "flex" :flex-direction "column"}}
   (if (seq chats)
     [:ul {:style {:list-style "none" :padding "0" :margin "0" :flex "1"}}
      (for [chat chats]
        (let [chat-id      (:id chat)
              last-message  (last (:messages chat))
              preview-text  (if last-message
                              ""
                              #_(subs (:text last-message)
                                      0 (min 50 (count (:text last-message))))
                              "No messages yet")]
          [:li {:key   chat-id
                :style {:display "flex" :align-items "center"
                        :justify-content "space-between"
                        :padding "10px" :border-bottom "1px solid #eee"
                        :transition "background 0.2s"}}
           ;; Clickable area for opening — sibling of delete button, not parent
           [:div {:style         {:flex "1" :cursor "pointer"}
                  :data-on-click (str "$actionChatId='" (str chat-id) "';"
                                      (post-action component-id "open_chat"))}
            [:div {:style {:font-size "14px" :font-weight "500" :color "#333"}}
             (str "Chat " (subs (str chat-id) 0 8) "...")]
            [:div {:style {:font-size "12px" :color "#999" :margin-top "4px"}}
             preview-text]]
           ;; Delete button — sibling of the open-click div, no bubbling conflict
           [:button {:data-on-click (str "$actionChatId='" (str chat-id) "';"
                                         (post-action component-id "delete_chat"))
                     :type "button"
                     :style {:background "none" :border "none" :color "#cc9a9a"
                             :font-size "18px" :cursor "pointer" :padding "0 10px"}}
            "\u00d7"]]))]
     [:div {:style {:text-align "center" :color "#999" :padding "20px"}}
      "No chats yet"])
   [:button {:data-on-click (post-action component-id "cancel")
             :type "button"
             :style {:margin-top "10px" :padding "8px 16px" :background "#f5f5f5"
                     :border "1px solid #ddd" :border-radius "4px" :cursor "pointer"}}
    "Cancel"]])

(defn- chat-modal-overlay
  "Renders the chat modal: a dark transparent overlay on the top 20%,
   and the chat panel occupying the bottom 80% of the screen.
   Chat messages are loaded via data-on-load from the todo-chat component."
  [component-id ctx]
  (let [state           (get ctx :state)
        current-chat-id (get-in ctx [:memory :selected :current-chat-id])
        chats           (get ctx :chats [])]
    (when (or (= state :chat-displayed) (= state :list-of-chats-displayed))
      [:div
       ;; Dark transparent overlay on top 20% — click to close
       [:div {:style         {:position   "fixed"
                              :top        "0"
                              :left       "0"
                              :width      "100%"
                              :height     "100vh"
                              :background "rgba(0, 0, 0, 0.4)"
                              :z-index    "1001"}
              :data-on-click (post-action component-id "close")}]

       ;; Chat panel occupying bottom 80%
       [:div {:style {:position        "fixed"
                      :top             "20vh"
                      :left            "0"
                      :width           "100%"
                      :height          "80vh"
                      :background      "rgba(255, 255, 255, 0.98)"
                      :backdrop-filter "blur(10px)"
                      :z-index         "1002"
                      :box-shadow      "0 -2px 10px rgba(0,0,0,0.15)"
                      :display         "flex"
                      :flex-direction  "column"
                      :border-radius   "16px 16px 0 0"}}

        ;; ---- Header ----
        [:div {:style {:display         "flex"
                       :align-items     "center"
                       :justify-content "space-between"
                       :padding         "10px 20px"
                       :border-bottom   "1px solid #e6e6e6"}}
         ;; Left: new chat + list chats + chat info
         [:div {:style {:display "flex" :align-items "center" :gap "10px"}}
          [:button {:data-on-click (post-action component-id "new_chat")
                    :type "button"
                    :title "New chat"
                    :style {:background "none" :border "none" :font-size "20px"
                            :cursor "pointer" :padding "5px 10px" :color "#777"}}
           "+"]
          [:button {:data-on-click (post-action component-id "list_chats")
                    :type "button"
                    :title "List chats"
                    :style {:background "none" :border "none" :font-size "18px"
                            :cursor "pointer" :padding "5px 10px" :color "#777"}}
           "\u2630"]  ;; ☰ hamburger icon
          [:span {:style {:font-size "14px" :color "#999"}}
           (if current-chat-id
             (str "Chat: " (subs (str current-chat-id) 0 8) "...")
             "New Chat")]]
         ;; Right: close button
         [:button {:data-on-click (post-action component-id "close")
                   :type "button"
                   :title "Close"
                   :style {:background "none" :border "none" :font-size "20px"
                           :cursor "pointer" :padding "5px 10px" :color "#777"}}
          "\u2715"]]

        ;; ---- Content area ----
        (if (= state :list-of-chats-displayed)
          ;; --- List chats view ---
          (chat-list-view component-id chats)
          ;; --- Chat messages view (loaded via data-on-load) ---
          (let [chat-component-id (str current-chat-id)]
            [:div {:id           chat-component-id
                   :style        {:flex "1" :display "flex" :flex-direction "column" :overflow "hidden"}
                   :data-signals (charred/write-json-str
                                  {chat-component-id {:chat-id              (str current-chat-id)
                                                      :parent-component-id  component-id}})
                   :data-on-load (str "@post('/ds/nds/todomvc.todo-chat/"
                                      chat-component-id "/init')")}]))]])))

;; ---------------------------------------------------------------------------
;; Sub-components
;; ---------------------------------------------------------------------------

(defn- todo-item
  "Renders a single todo list item. Shows an edit input when the todo
   is the one being edited; otherwise shows the view mode with checkbox,
   label (double-click to edit), and delete button."
  [component-id todo editing-id]
  (let [is-editing (= (str (:id todo)) (str editing-id))
        todo-id    (str (:id todo))]
    [:li {:id    (str "todo-" todo-id)
          :style (merge
                  {:border-bottom "1px solid #ededed"
                   :position      "relative"}
                  (when (:completed todo)
                    {:color "#949494"}))}
     (if is-editing
       ;; ---- Edit mode ----
       [:input {:type      "text"
                :value     (:text todo)
                :autofocus true
                :style     {:width      "100%"
                            :padding    "16px 16px 16px 60px"
                            :font-size  "24px"
                            :font-family "inherit"
                            :border     "1px solid #999"
                            :box-shadow "inset 0 -1px 5px 0 rgba(0,0,0,0.2)"
                            :box-sizing "border-box"}
                :data-on-keydown
                (str "$editText=el.value;"
                     "event.key==='Enter'&&" (post-action component-id "update") ";"
                     "event.key==='Escape'&&" (post-action component-id "cancel"))}]

       ;; ---- View mode ----
       [:div {:style {:display     "flex"
                      :align-items "center"}}
        ;; Toggle checkbox
        [:input {:type          "checkbox"
                 :checked       (boolean (:completed todo))
                 :data-on-click (str "$selected.item-id='" todo-id "';"
                                     (post-action component-id "toggle"))
                 :style         {:width       "40px"
                                 :height      "40px"
                                 :margin      "0 0 0 10px"
                                 :cursor      "pointer"
                                 :flex-shrink "0"}}]
        ;; Label (double-click to edit)
        [:label {:data-on-dblclick (str "$selected.item-id='" todo-id "';"
                                        (post-action component-id "edit"))
                 :style (merge
                         {:flex       "1"
                          :padding    "15px 15px 15px 10px"
                          :font-size  "24px"
                          :word-break "break-all"
                          :cursor     "text"}
                         (when (:completed todo)
                           {:text-decoration "line-through"
                            :color           "#949494"}))}
         (:text todo)]
        ;; Delete button
        [:button {:data-on-click (str "$selected.item-id='" todo-id "';"
                                      (post-action component-id "delete"))
                  :type  "button"
                  :style {:background "none"
                          :border     "none"
                          :color      "#cc9a9a"
                          :font-size  "30px"
                          :cursor     "pointer"
                          :padding    "0 15px"
                          :transition "color 0.2s ease-out"}}
         "\u00D7"]])]))

;; ---------------------------------------------------------------------------
;; Main component
;; ---------------------------------------------------------------------------

(defn todomvc
  "Renders the full TodoMVC UI: header (title + new-todo input),
   main section (toggle-all + filtered todo list), and footer
   (item count, filter buttons, clear completed).
   Also includes floating chat button and chat modal overlay."
  [{:keys [component-id] :as ctx}]
  ;; [[:broadcast-elements!
  (let [todos          (get-in ctx [:memory :todos] [])
        current-filter (get-in ctx [:memory :filter] "all")
        editing-id     (get-in ctx [:memory :selected :item-id])
        state          (get ctx :state :listing)
        visible-todos  (filtered-todos todos current-filter)
        active         (active-count todos)
        completed      (completed-count todos)]
    [:div {:id           component-id
           :data-signals (todomvc-signals ctx)
           :style {:position "relative"
                   :min-height "100vh"}}
     (exp/expandable
      ""
      [:pre.cl-12.cs-12
       (with-out-str (clojure.pprint/pprint (-> ctx
                                                (dissoc :logged-user))))])
     [:section.todoapp
      {:style        {:background "#fff"
                      :margin     "0 auto"
                      :position   "relative"
                      :box-shadow "0 2px 4px 0 rgba(0,0,0,0.2), 0 25px 50px 0 rgba(0,0,0,0.1)"
                      :max-width  "550px"}}

      ;; ---- Header ----
      [:header
       [:h1 {:style {:font-size   "80px"
                     :font-weight "200"
                     :text-align  "center"
                     :color       "#b83f45"
                     :margin      "0"
                     :padding     "20px 0 10px 0"}}
        "todos"]
       [:input {:placeholder     "What needs to be done?"
                :autofocus       true
                :style           {:width        "100%"
                                  :padding      "16px 16px 16px 60px"
                                  :font-size    "24px"
                                  :font-family  "inherit"
                                  :border       "none"
                                  :border-bottom "1px solid #ededed"
                                  :box-sizing   "border-box"
                                  :background   "rgba(0,0,0,0.003)"
                                  :box-shadow   "inset 0 -2px 1px rgba(0,0,0,0.03)"}
                :data-on-keydown (str "$newTodoText=el.value;"
                                      "if(event.key==='Enter'){el.value='';"
                                      (post-action component-id "add") "}")}]]

      ;; ---- Main section (visible only when todos exist) ----
      (when (seq todos)
        [:section {:style {:position   "relative"
                           :border-top "1px solid #e6e6e6"}}
         ;; Toggle all
         [:div {:style {:display     "flex"
                        :align-items "center"
                        :border-bottom "1px solid #ededed"}}
          [:input {:type          "checkbox"
                   :checked       (zero? active)
                   :data-on-click (post-action component-id "toggle_all")
                   :style         {:width  "40px"
                                   :height "40px"
                                   :margin "0 0 0 10px"
                                   :cursor "pointer"}}]
          [:label {:style {:color     "#737373"
                           :font-size "14px"
                           :padding   "10px"}}
           "Mark all as complete"]]

         ;; Todo list
         [:ul {:style {:list-style "none"
                       :padding    "0"
                       :margin     "0"}}
          (for [todo visible-todos]
            (todo-item component-id todo editing-id))]])

      ;; ---- Footer (visible only when todos exist) ----
      (when (seq todos)
        [:footer {:style {:display         "flex"
                          :align-items     "center"
                          :justify-content "space-between"
                          :padding         "10px 15px"
                          :color           "#777"
                          :font-size       "14px"
                          :border-top      "1px solid #e6e6e6"}}
         ;; Items left
         [:span
          [:strong (str active)]
          (str " item" (when (not= active 1) "s") " left")]

         ;; Filter buttons
         [:ul {:style {:display    "flex"
                       :list-style "none"
                       :padding    "0"
                       :margin     "0"
                       :gap        "4px"}}
          (for [[label value] [["All" "all"] ["Active" "active"] ["Completed" "completed"]]]
            [:li
             [:a {:data-on-click (str "$actionFilter='" value "';"
                                      (post-action component-id "filter"))
                  :style (merge
                          {:cursor        "pointer"
                           :padding       "3px 7px"
                           :border        "1px solid transparent"
                           :border-radius "3px"
                           :text-decoration "none"
                           :color          "inherit"}
                          (when (= current-filter value)
                            {:border-color "rgba(175, 47, 47, 0.2)"}))}
              label]])]

         ;; Clear completed
         (when (pos? completed)
           [:button {:data-on-click (post-action component-id "clear_completed")
                     :type  "button"
                     :style {:background "none"
                             :border     "none"
                             :cursor     "pointer"
                             :color      "inherit"
                             :font-size  "14px"
                             :padding    "0"}}
            "Clear completed"])])]

     ;; Floating chat button (only visible when in listing state)
     (when (= state :listing)
       (floating-chat-button component-id ctx))

     ;; Chat modal overlay
     (chat-modal-overlay component-id ctx)])
  ;;  ]]
  )
