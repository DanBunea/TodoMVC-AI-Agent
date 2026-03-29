(ns ds.adapters.components.assistant.replies
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]))

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

(def ^:private default-llm-error-message
  "Sorry , update your open ai plan :)")

;; Ordered list of [pattern message]. First match wins. Add entries for new error types.
(def ^:private error-classifications
  [[#"(?i)quota|billing|plan|invalid header|bearer|api.?key|secret key" default-llm-error-message]
   ;; Example: [#"(?i)rate limit|too many requests" "Too many requests. Please try again in a moment."]
   ])

(defn- exception-message [x]
  (when (instance? Throwable x)
    (.getMessage ^Throwable x)))

(defn- safe-display-string? [s]
  (and (string? s)
       (not (str/blank? s))
       (not (re-find #"#error|:via\s*\[|:trace\s*\[" s))
       (< (count s) 2000)))

(defn- error->raw [error]
  "Extract a single string from error (string, map, or seq of maps). No sanitization."
  (cond (string? error) error
        (map? error) (or (:message error)
                         (:cause error)
                         (exception-message (:exception error))
                         (when (string? (:error error)) (:error error))
                         (str error))
        (sequential? error) (when-let [first (first error)]
                             (when (map? first) (error->raw first)))
        :else (str error)))

(defn- error->message [error]
  "Normalize error to a safe display string, or nil if it looks like an exception dump."
  (when-let [raw (error->raw error)]
    (when (safe-display-string? raw) raw)))

(defn- classified-error-message [raw-msg]
  "First matching classification, or nil. Add new error types to error-classifications."
  (when (string? raw-msg)
    (some (fn [[pattern message]]
            (when (re-find pattern raw-msg) message))
          error-classifications)))

(defn llm-error-display-message
  "Returns the user-facing message for an llm-error event-payload.
   Uses error-classifications for known types; else safe raw message; else default."
  [event-payload]
  (let [raw (error->message (:error event-payload))]
    (or (classified-error-message raw) raw default-llm-error-message)))

(defn llm-error [event-payload]
  "Handles LLM errors (e.g. quota exceeded). Appends a user-friendly message to the chat."
  (let [{:keys [id event-trace]} event-payload
        chat-id (str (:ai-chat/id event-trace))
        display-msg (llm-error-display-message event-payload)]
    [[:broadcast-hide-loading chat-id]
     [:broadcast-elements!
      [:div.cs-12.cl-12
       {:id (str "message-error-" id)
        :data-on-load (str "@post('/ds/nds/todomvc.todo-chat/" chat-id "/completion_done')")}
       [:span {:style {:font-weight "600"}} "assistant: "]
       [:span {:style {:font-size "var(--typography-small-regular-font-size)" :color "#c0392b"}}
        display-msg]]
      {:d*/selector "#messages"
       :d*/patch-mode :d*/pm-append}]]))

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

;; ---------------------------------------------------------------------------
;; Tests (key LLM error handling)
;; ---------------------------------------------------------------------------

(tests
 "error->raw: string and map shapes"
 (error->raw "hello") := "hello"
 (error->raw {:message "API error"}) := "API error"
 (error->raw {:cause "cause text"}) := "cause text"
 (error->raw {:error "error key"}) := "error key"
 (error->raw [{:message "first"}]) := "first")

(tests
 "error->raw: exception in map"
 (error->raw {:exception (ex-info "ex message" {})}) := "ex message")

(tests
 "safe-display-string?: accept safe, reject dumps and empty"
 (safe-display-string? "short") := true
 (safe-display-string? "") := false
 (safe-display-string? "   ") := false
 (safe-display-string? "has #error inside") := false
 (safe-display-string? "has :via [ in it") := false
 (safe-display-string? (apply str (repeat 2001 "x"))) := false)

(tests
 "error->message: safe string passes, exception dump returns nil"
 (error->message "safe short msg") := "safe short msg"
 (error->message {:message "safe"}) := "safe"
 (error->message {:message "contains #error dump"}) := nil)

(tests
 "classified-error-message: known patterns get friendly message"
 (classified-error-message "You exceeded your quota") := "Sorry , update your open ai plan :)"
 (classified-error-message "invalid header value") := "Sorry , update your open ai plan :)"
 (classified-error-message "check your api key") := "Sorry , update your open ai plan :)"
 (classified-error-message "generic failure") := nil)

(tests
 "llm-error-display-message: classified -> friendly, else raw, else default"
 (llm-error-display-message {:error "quota exceeded"}) := "Sorry , update your open ai plan :)"
 (llm-error-display-message {:error "Something went wrong"}) := "Something went wrong"
 (llm-error-display-message {:error nil}) := "Sorry , update your open ai plan :)"
 (llm-error-display-message {:error {:message "unsafe #error dump"}}) := "Sorry , update your open ai plan :)")

(tests
 "llm-error: returns broadcast effects with correct chat-id and display message"
 (let [payload {:id "msg-1"
               :event-trace {:ai-chat/id "chat-123"}
               :error "quota exceeded"}
       effects (llm-error payload)
       [_ hiccup _] (second effects)]
   (count effects) := 2
   (first effects) := [:broadcast-hide-loading "chat-123"]
   (get-in hiccup [1 :id]) := "message-error-msg-1"
   (get-in hiccup [1 :data-on-load]) := "@post('/ds/nds/todomvc.todo-chat/chat-123/completion_done')"
   (get-in hiccup [3 2]) := "Sorry , update your open ai plan :)")
)
