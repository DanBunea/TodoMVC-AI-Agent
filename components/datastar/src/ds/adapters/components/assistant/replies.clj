(ns ds.adapters.components.assistant.replies
  (:require
   [clojure.string :as str]))

(defn partial-reply [{:keys [reply]}]
  (let [parts (str/split reply #"\n")
                      ;; starts-with-newline? (str/starts-with? "\n")
        ends-with-newline? (str/ends-with? reply "\n")]
    (cond-> (->> parts
                 (map (fn [s] s))
                 (interpose [:br])
                 (into []))
      ends-with-newline? (conj [:br]))))

(defn message-view
  "Pure view: returns hiccup for a single user/assistant message (no broadcast wrapper)."
  [id role content]
  [:div.cs-12.cl-12
   {:id (str "message-" id)}
   [:span {:style {:font-weight "600"}} (str role ": ")]
   [:span #_.secondary
    {:style {:font-size "var(--typography-small-regular-font-size)"}}
    content]])

(defn message [id role content]
  [:broadcast-elements!
   (message-view id role content)
   {:d*/selector "#messages"
    :d*/patch-mode :d*/pm-append}])

;; todo tests - after fixing components

(defn llm-reply-requested [event-payload]
  (let [{:keys [event-trace]} event-payload
        chat-id (:ai-chat/id event-trace)]
    [[:broadcast-loading chat-id]]))

(defn partial-llm-reply-received [event-payload]
  (let [{:keys [id reply status event-trace]} event-payload
        chat-id (:ai-chat/id event-trace)]
    (cond
      (= :started status)
      [[:broadcast-hide-loading chat-id]
       [:broadcast-elements!
        [:div.cl-12.cs-12
         {:id (str "llm-partial-reply-" id)}
         [:span {:style {:font-weight "600"}} (str "assistant" ": ")]
         [:span.secondary {:id (str "llm-partial-replies-" id)}]]
        {:d*/selector "#messages"
         :d*/patch-mode :d*/pm-append}]]

      :else
      [[:broadcast-elements!
        (partial-reply {:reply reply})
        {:d*/selector (str "#llm-partial-replies-" id)
         :d*/patch-mode :d*/pm-append}]])))

(defn llm-reply-received [event-payload]
  (let [{:keys [id reply event-trace]} event-payload
        chat-id (:ai-chat/id event-trace)]
    [[:broadcast-elements!
      []
      {:d*/selector (str "#llm-partial-reply-" id)
       :d*/patch-mode :d*/pm-remove}]
     [:broadcast-elements!
      [:div.cs-12.cl-12
       {:id           (str "message-" id)
        :data-on-load (str "@post('/ds/nds/todomvc.todo-chat/" chat-id "/completion_done')")}
       [:span {:style {:font-weight "600"}} "assistant: "]
       [:span {:style {:font-size "var(--typography-small-regular-font-size)"}}
        (partial-reply {:reply (str/join "" reply)})]]
      {:d*/selector "#messages"
       :d*/patch-mode :d*/pm-append}]]))

