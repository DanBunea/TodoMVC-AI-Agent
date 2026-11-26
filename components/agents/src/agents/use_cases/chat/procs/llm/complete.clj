(ns agents.use-cases.chat.procs.llm.complete
  (:require
   [clojure.pprint]
   [clojure.string]
   [llm.interface :as li]

   [logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(def empty-reply-after-tools
  "What the assistant says when the model ends a tool round without any text."
  "Done.")

(defn- emit!
  "Hands events from a completion callback (the HTTP client's streaming thread)
   back to the :llm proc, which re-emits `msgs` on its own `out` port
   (:to-ui | :to-tools). `:callback!` blocks while the proc lags and is a no-op
   once the flow has stopped — see procs.llm."
  [state out msgs]
  ((:callback! state) [::emit {:out out :msgs msgs}]))

(defn on-llm-complete-requested! [state {:keys [id #_chat-id #_user messages system-instructions tools] :as event-payload}]
  (log/trace
   ::on-llm-complete-requested!
   [:event-payload event-payload]
   (let [local-context (log/local-context)

         received-event-trace (:event-trace event-payload)
         completion-step (inc (or (:completion-step received-event-trace) 0))
         event-trace (assoc received-event-trace :completion-step completion-step)
         ;; The step's own output: moves the UI into its "thinking" state. It is
         ;; written before the proc reads any callback, so it always precedes
         ;; the first chunk.
         reply-requested [:agents.domain.chat/llm-reply-requested
                          {:id id
                           :mulog/ctx local-context
                           :event-trace event-trace}]]
     (try
       (tap> [::on-llm-complete-requested! {:instructions system-instructions
                                            :user-messages messages
                                            :tools tools}])

       (li/complete!
        {:provider "openai"
         :model "gpt-5-mini"
         :model-capabilities {:tools true :reason? true}
         :trace-level :info
         :instructions system-instructions
         :user-messages messages
         :past-messages []
         :tools tools
         :config (:config state)

         ;; All callbacks come back through the :llm proc's :from-completion in-port
         :on-first-response-received
         (fn [_]
           (emit! state :to-ui [[:agents.domain.chat/first-llm-reply-received
                                 {:id id
                                  :mulog/ctx local-context
                                  :event-trace event-trace}]]))

         :on-message-received
         (fn [{:keys [type text status]}]

           (if (= type :text)
             (emit! state :to-ui [[:agents.domain.chat/partial-llm-reply-received
                                   {:id id
                                    :mulog/ctx local-context
                                    :status status
                                    :reply text
                                    :event-trace event-trace}]])

             ;; type :finish
             (let [;; After a tool round the model sometimes ends the turn with
                   ;; no text at all: nothing would be shown or stored and the
                   ;; chat looks frozen. Close the turn with a short reply.
                   silent-after-tools? (and (> completion-step 1)
                                            (clojure.string/blank? (clojure.string/join " " text)))
                   text (if silent-after-tools? [empty-reply-after-tools] text)
                   content (clojure.string/join " " text)]
               (tap> [:agents.domain.chat/llm-reply-received {:reply text}])
               ;; Send final message to UI
               (emit! state :to-ui [[:agents.domain.chat/llm-reply-received
                                     {:id id
                                      :mulog/ctx local-context
                                      :status status
                                      :reply text
                                      :event-trace event-trace}]])

               ;; Add to history in tools only when there is non-empty content
               (when (not (clojure.string/blank? content))
                 (emit! state :to-tools [[:add-assistant-message {:id id
                                                                  :mulog/ctx local-context
                                                                  :content content
                                                                  :event-trace event-trace}]])))))

         :on-prepare-tool-call
         (fn [{:keys [name arguments-text status]}]
           (emit! state :to-ui [[:agents.domain.chat/partial-llm-tools-reply-received
                                 {:id id
                                  :mulog/ctx local-context
                                  :reply arguments-text
                                  :tool-name name
                                  :status status
                                  :event-trace event-trace}]]))

         :on-tools-called
         (fn [tool-calls]
           (emit! state :to-ui [[:agents.domain.chat/llm-tools-reply-received
                                 {:id id
                                  :mulog/ctx local-context
                                  :tool-calls tool-calls
                                  :event-trace event-trace}]])
           (emit! state :to-tools [[:execute-tools {:id id
                                                    :mulog/ctx local-context
                                                    :tool-calls tool-calls
                                                    :event-trace event-trace}]]))

         :on-error
         (fn [error]
           (emit! state :to-ui [[:agents.domain.chat/llm-error
                                 {:id id
                                  :mulog/ctx local-context
                                  :error error
                                  :event-trace event-trace}]]))

         :on-reason (fn [{:keys [text status]}]
                      (emit! state :to-ui [[:agents.domain.chat/partial-reasoning-reply-received
                                            {:id id
                                             :mulog/ctx local-context
                                             :reply text
                                             :status status
                                             :event-trace event-trace}]]))
         :on-usage-updated (fn [& usage-response]
                             (prn 8888545 usage-response))})

       ;; Return immediately — the completion streams on the HTTP client's
       ;; thread and calls back through the :from-completion in-port.
       [state {:to-ui [reply-requested]}]

       (catch Exception e
         ;; Thrown synchronously on the proc thread: report it in the step's
         ;; own output rather than putting into the proc's own in-port.
         [state {:to-ui [reply-requested
                         [:agents.domain.chat/llm-error
                          {:id id
                           :error (str "Exception in LLM proc: " (.getMessage e))
                           :event-trace event-trace}]]}])))))

(tests
 "Unit tests for on-llm-complete-requested!"

 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:config {:test-config true}
                   :callback! callback-spy}
       event-payload {:id "test-id"

                      :messages [{:role "user" :content "test message"}]
                      :system-instructions "test instructions"
                      :event-trace {:ai-chat/id "test-chat-id"
                                    :user {:user/id "test-user"}
                                    :message-id 5}
                      :tools []}]

   ;; llm-reply-requested is the step's own output (proc thread), not a
   ;; callback — and a completion that streams nothing calls back nothing
   (with-redefs [li/complete! (spy/spy (fn [_] nil))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state event-payload)
     := [mock-state {:to-ui [[:agents.domain.chat/llm-reply-requested
                              {:id "test-id"
                               :mulog/ctx {:mulog/trace-id "test-trace"}
                               :event-trace {:ai-chat/id "test-chat-id"
                                             :user {:user/id "test-user"}
                                             :message-id 5
                                             :completion-step 1}}]]}]
     (spy/calls callback-spy) := [])))

(tests
 "Test on-first-response-received callback"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ;; Directly invoke the callback with test data
                                ((:on-first-response-received opts) nil))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"

                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :event-trace {:message-id 5
                                                           :completion-step 1
                                                           :ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :tools []})

     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/first-llm-reply-received
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace {:message-id 5
                                                                   :completion-step 2
                                                                   :ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}}}]]}]]])))

(tests
 "Test on-message-received callback with type :text"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :text :status :started :text ""}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/partial-llm-reply-received
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :status :started
                                                     :reply ""}]]}]]]))

 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :text :status :delta :text "partial reply"}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/partial-llm-reply-received
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :status :delta
                                                     :reply "partial reply"}]]}]]])))

(tests
 "Test on-message-received callback with type :finish"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :finish :status :finish :text ["final" "reply"]}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/llm-reply-received
                                                    {:id "test-id"
                                                     :mulog/ctx _
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :status :finish
                                                     :reply ["final" "reply"]}]]}]]
                                  [[::emit {:out :to-tools
                                            :msgs [[:add-assistant-message
                                                    {:id "test-id"
                                                     :mulog/ctx _
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :content "final reply"}]]}]]])))

(defn- emitted
  "Test helper: the [event-name reply-or-content] of each event handed to
   :callback!, in order."
  [callback-spy]
  (->> (spy/calls callback-spy)
       (map (fn [[[_ {:keys [msgs]}]]] (first msgs)))
       (map (fn [[event-name payload]] [event-name (or (:reply payload) (:content payload))]))))

(tests
 "A blank :finish after a tool round is replaced by a short reply, shown and stored"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :finish :status nil :text [""]}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :completion-step 2}
                                             :messages []
                                             :tools []})
     (emitted callback-spy)
     := [[:agents.domain.chat/llm-reply-received [empty-reply-after-tools]]
         [:add-assistant-message empty-reply-after-tools]]))

 "A blank :finish on the first step (no tools ran) is left alone and not stored"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :finish :status nil :text [""]}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"}
                                             :messages []
                                             :tools []})
     (map first (emitted callback-spy))
     := [:agents.domain.chat/llm-reply-received])))

(tests
 "Test on-prepare-tool-call callback"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-prepare-tool-call opts) {:name "test-tool" :arguments-text "test args" :status :delta}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/partial-llm-tools-reply-received
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :status :delta
                                                     :reply "test args"
                                                     :tool-name "test-tool"}]]}]]])))

(tests
 "Test on-tools-called callback"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}
       tool-calls [{:name "test-tool" :arguments {}}]]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-tools-called opts) tool-calls))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/llm-tools-reply-received
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :tool-calls tool-calls}]]}]]
                                  [[::emit {:out :to-tools
                                            :msgs [[:execute-tools
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace _
                                                     :tool-calls tool-calls}]]}]]])))

(tests
 "Test on-error callback"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-error opts) {:error "test error"}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/llm-error
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :error {:error "test error"}}]]}]]])))

(tests
 "Test on-reason callback"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-reason opts) {:text "reasoning"
                                                    :status :thinking}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls callback-spy) := [[[::emit {:out :to-ui
                                            :msgs [[:agents.domain.chat/partial-reasoning-reply-received
                                                    {:id "test-id"
                                                     :mulog/ctx {:mulog/trace-id "test-trace"}
                                                     :event-trace {:ai-chat/id "test-chat-id"
                                                                   :user {:user/id "test-user"}
                                                                   :completion-step 1}
                                                     :status :thinking
                                                     :reply "reasoning"}]]}]]])))

(tests
 "Test exception handling: a synchronous throw is reported in the step's own
  output, never through the callback in-port"
 (let [callback-spy (spy/spy (fn [_]))
       mock-state {:callback! callback-spy}]
   (with-redefs [li/complete! (fn [_] (throw (Exception. "test exception")))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     := [mock-state {:to-ui [[:agents.domain.chat/llm-reply-requested _]
                             [:agents.domain.chat/llm-error
                              {:id "test-id"
                               :event-trace {:ai-chat/id "test-chat-id"
                                             :user {:user/id "test-user"}
                                             :completion-step 1}
                               :error "Exception in LLM proc: test exception"}]]}]
     (spy/calls callback-spy) := [])))

(comment

  (log/trace :aa
             [:a 1]
             (prn 3)))
