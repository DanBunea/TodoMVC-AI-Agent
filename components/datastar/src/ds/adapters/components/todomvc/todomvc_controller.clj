(ns ds.adapters.components.todomvc.todomvc-controller
  "State machine controller for the TodoMVC component.
   Manages todo items with add, toggle, edit, delete, and filter operations."
  (:require [clojure.string :as str]
            [agents.use-cases.chat.todomvc-agent :as todomvc-agent])
  (:import (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Storage delegates — all read/write goes through todomvc-agent
;; ---------------------------------------------------------------------------

(def read-todos  todomvc-agent/read-todos)
(def write-todos todomvc-agent/write-todos)
(def read-chats  todomvc-agent/read-chats)
(def write-chats todomvc-agent/write-chats)
(def read-chat   todomvc-agent/read-chat)

(defn- delete-chat
  "Removes a chat by id for a user-id.
   Returns the updated chats vector."
  [user-id chat-id]
  (todomvc-agent/delete-chat user-id chat-id))

(defn- generate-uuid
  "Generates a random UUID string."
  []
  (UUID/randomUUID))

(defn- toggle-todo
  "Toggles the :completed status of a todo by id."
  [todos todo-id]
  (mapv (fn [todo]
          (if (= (str (:id todo)) (str todo-id))
            (update todo :completed not)
            todo))
        todos))

(defn- remove-todo
  "Removes a todo by id."
  [todos todo-id]
  (into [] (remove #(= (str (:id %)) (str todo-id))) todos))

(defn- update-todo-text
  "Updates the text of a todo by id."
  [todos todo-id new-text]
  (mapv (fn [todo]
          (if (= (str (:id todo)) (str todo-id))
            (assoc todo :text new-text)
            todo))
        todos))

(def machine
  {:id :todomvc
   :initial :listing
   :states
   {;; -----------------------------------------------------------------------
    ;; Todo states
    ;; -----------------------------------------------------------------------

    :listing
    {:on
     {:INIT {:target :loading}

      :ADD {:target :adding
            :actions (fn [ctx event-params]
                       (assoc ctx :event-params event-params))}

      :TOGGLE {:target :toggling
               :actions (fn [ctx event-params]
                          (assoc ctx :event-params event-params))}

      :DELETE {:target :deleting
               :actions (fn [ctx event-params]
                          (assoc ctx :event-params event-params))}

      :FILTER {:target :listing
               :actions (fn [ctx event-params]
                          (let [filter-value (:actionFilter event-params)]
                            (assoc-in ctx [:memory :filter] (or filter-value "all"))))}

      :CLEAR_COMPLETED {:target :clearing-completed}

      :TOGGLE_ALL {:target :toggling-all}

      :EDIT {:target :editing
             :actions (fn [ctx event-params]
                        (let [todo-id (get-in event-params [:selected :item-id])]
                          (assoc-in ctx [:memory :selected] {:item-id todo-id})))}

      :OPEN_CHAT {:target :loading-chat
                  :actions (fn [ctx event-params]
                             (assoc ctx :event-params event-params))}

      :NEW_CHAT {:target :creating-chat}}}

    :loading
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])]
                       (read-todos user-id)))
              :on-done {:target :listing
                        :actions (fn [ctx result]
                                   (let [current-chat-id (get-in ctx [:memory :selected :current-chat-id])
                                         selected (if current-chat-id
                                                    {:current-chat-id current-chat-id}
                                                    {})]
                                     (-> ctx
                                         (assoc-in [:memory :todos] result)
                                         (assoc-in [:memory :filter] "all")
                                         (assoc-in [:memory :selected] selected))))}}}

    :adding
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           event-params (:event-params ctx)
                           text (:newTodoText event-params "")]
                       (if (str/blank? text)
                         (read-todos user-id)
                         (let [current-todos (read-todos user-id)
                               updated-todos (conj current-todos
                                                   {:id (str (generate-uuid))
                                                    :text (str/trim text)
                                                    :completed false})]
                           (write-todos user-id updated-todos)
                           updated-todos))))
              :on-done {:target :listing
                        :actions (fn [ctx result]
                                   (-> ctx
                                       (assoc-in [:memory :todos] result)
                                       (dissoc :event-params)))}}}

    :toggling
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           event-params (:event-params ctx)
                           todo-id (get-in event-params [:selected :item-id])
                           current-todos (read-todos user-id)
                           updated-todos (toggle-todo current-todos todo-id)]
                       (write-todos user-id updated-todos)
                       updated-todos))
              :on-done {:target :listing
                        :actions (fn [ctx result]
                                   (-> ctx
                                       (assoc-in [:memory :todos] result)
                                       (dissoc :event-params)))}}}

    :deleting
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           event-params (:event-params ctx)
                           todo-id (get-in event-params [:selected :item-id])
                           current-todos (read-todos user-id)
                           updated-todos (remove-todo current-todos todo-id)]
                       (write-todos user-id updated-todos)
                       updated-todos))
              :on-done {:target :listing
                        :actions (fn [ctx result]
                                   (-> ctx
                                       (assoc-in [:memory :todos] result)
                                       (dissoc :event-params)))}}}

    :clearing-completed
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           current-todos (read-todos user-id)
                           updated-todos (into [] (remove :completed) current-todos)]
                       (write-todos user-id updated-todos)
                       updated-todos))
              :on-done {:target :listing
                        :actions (fn [ctx result]
                                   (assoc-in ctx [:memory :todos] result))}}}

    :toggling-all
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           current-todos (read-todos user-id)
                           new-completed (not (every? :completed current-todos))
                           updated-todos (mapv #(assoc % :completed new-completed) current-todos)]
                       (write-todos user-id updated-todos)
                       updated-todos))
              :on-done {:target :listing
                        :actions (fn [ctx result]
                                   (assoc-in ctx [:memory :todos] result))}}}

    :editing
    {:on
     {:UPDATE {:target :updating
               :actions (fn [ctx event-params]
                          (assoc ctx :event-params event-params))}

      :CANCEL {:target :listing
               :actions (fn [ctx _]
                          (update-in ctx [:memory :selected] dissoc :item-id))}}}

    :updating
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           event-params (:event-params ctx)
                           editing-id (get-in ctx [:memory :selected :item-id])
                           new-text (:editText event-params "")
                           current-todos (read-todos user-id)]
                       (if (str/blank? new-text)
                         (let [updated-todos (remove-todo current-todos editing-id)]
                           (write-todos user-id updated-todos)
                           updated-todos)
                         (let [updated-todos (update-todo-text current-todos editing-id (str/trim new-text))]
                           (write-todos user-id updated-todos)
                           updated-todos))))
              :on-done {:target :listing
                        :actions (fn [ctx result]
                                   (-> ctx
                                       (assoc-in [:memory :todos] result)
                                       (update-in [:memory :selected] dissoc :item-id)
                                       (dissoc :event-params)))}}}

    ;; -----------------------------------------------------------------------
    ;; Chat states
    ;; -----------------------------------------------------------------------

    :creating-chat
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           current-chats (read-chats user-id)
                           new-chat {:id (str (generate-uuid))
                                     :created-at (System/currentTimeMillis)
                                     :messages []}
                           updated-chats (conj current-chats new-chat)]
                       (write-chats user-id updated-chats)
                       new-chat))
              :on-done {:target :chat-displayed
                        :actions (fn [ctx result]
                                   (-> ctx
                                       (assoc-in [:memory :selected :current-chat-id] (:id result))
                                       (dissoc :event-params)))}}}

    :loading-chat
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           event-params (:event-params ctx)
                           chat-id (or (:actionChatId event-params)
                                       (:chat-id event-params)
                                       (get-in ctx [:memory :selected :current-chat-id]))
                           chat (read-chat user-id chat-id)]
                       (if chat
                         {:chat-id (:id chat)
                          :messages (:messages chat)}
                         nil)))
              :on-done {:target :chat-displayed
                        :actions (fn [ctx result]
                                   (if result
                                     (-> ctx
                                         (assoc-in [:memory :selected :current-chat-id] (:chat-id result))
                                         (dissoc :event-params))
                                     (-> ctx
                                         (dissoc :event-params)
                                         (assoc :_force-state :listing))))}}}

    :chat-displayed
    {:on
     {:CLOSE {:target :loading
              :actions (fn [ctx _]
                         (-> ctx
                             (update-in [:memory :selected] dissoc :chat-input-text)
                             (dissoc :chat-messages)))}

      :LIST_CHATS {:target :loading-chats}

      :NEW_CHAT {:target :creating-chat}}}

    :loading-chats
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])]
                       (read-chats user-id)))
              :on-done {:target :list-of-chats-displayed
                        :actions (fn [ctx result]
                                   (assoc ctx :chats result))}}}

    :list-of-chats-displayed
    {:on
     {:CLOSE {:target :loading
              :actions (fn [ctx _]
                         (-> ctx
                             (update-in [:memory :selected] dissoc :chat-input-text)
                             (dissoc :chat-messages :chats)))}

      :CANCEL {:target :chat-displayed
               :actions (fn [ctx _]
                          (dissoc ctx :chats))}

      :OPEN_CHAT {:target :loading-chat
                  :actions (fn [ctx event-params]
                             (let [chat-id (or (:actionChatId event-params)
                                               (:chat-id event-params))]
                               (if chat-id
                                 (assoc ctx :event-params {:chat-id chat-id})
                                 ctx)))}

      :DELETE_CHAT {:target :deleting-chat
                    :actions (fn [ctx event-params]
                               (let [chat-id (or (:actionChatId event-params)
                                                 (:chat-id event-params))]
                                 (if chat-id
                                   (assoc ctx :event-params {:chat-id chat-id})
                                   ctx)))}}}

    :deleting-chat
    {:invoke {:src (fn [ctx]
                     (let [user-id (get-in ctx [:logged-user :user/id])
                           event-params (:event-params ctx)
                           chat-id (or (:actionChatId event-params)
                                       (:chat-id event-params))
                           remaining-chats (delete-chat user-id chat-id)]
                       remaining-chats))
              :on-done {:target :loading-chats
                        :actions (fn [ctx result]
                                   (let [current-chat-id (get-in ctx [:memory :selected :current-chat-id])
                                         deleted-chat-id (or (get-in ctx [:event-params :actionChatId])
                                                             (get-in ctx [:event-params :chat-id]))
                                         deleted-current? (and current-chat-id deleted-chat-id
                                                               (= (str current-chat-id) (str deleted-chat-id)))]
                                     (cond-> ctx
                                       true             (dissoc :chat-messages :chats :event-params)
                                       deleted-current? (update-in [:memory :selected] dissoc :current-chat-id :chat-input-text))))}}}}})
