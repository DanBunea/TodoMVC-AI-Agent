(ns agents.use-cases.chat.procs.llm.complete
  (:require
   [clojure.pprint]
   [clojure.string]
   [llm.interface :as li]

   [logging.interface :as log]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn on-llm-complete-requested! [state {:keys [id #_chat-id #_user messages system-instructions tools] :as event-payload}]
  (log/trace
   ::on-llm-complete-requested!
   [:event-payload event-payload]
   (let [inject-in-flow! (:inject-in-flow! state)
         local-context (log/local-context)

         received-event-trace (:event-trace event-payload)
         completion-step (inc (or (:completion-step received-event-trace) 0))
         event-trace (assoc received-event-trace :completion-step completion-step)]
     (try
       (tap> [::on-llm-complete-requested! {:instructions system-instructions
                                            :user-messages messages
                                            :tools tools}])
       (inject-in-flow! {:state state
                         :channel-path [:llm :to-ui]
                         :event [[:agents.domain.chat/llm-reply-requested
                                  {:id id
                                   :mulog/ctx local-context
                                   :event-trace event-trace
                                  ;;  :chat-id chat-id
                                  ;;  :user user
                                   }]]})

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

         ;; All callbacks inject into the flow
         :on-first-response-received
         (fn [_]
           (inject-in-flow! {:state state
                             :channel-path [:llm :to-ui]
                             :event [[:agents.domain.chat/first-llm-reply-received
                                      {:id id
                                       :mulog/ctx local-context
                                      ;;  :chat-id chat-id
                                      ;;  :user user
                                       :event-trace event-trace}]]}))

         :on-message-received
         (fn [{:keys [type text status]}]

           (if (= type :text)
             (inject-in-flow! {:state state
                               :channel-path [:llm :to-ui]
                               :event [[:agents.domain.chat/partial-llm-reply-received
                                        {:id id
                                         :mulog/ctx local-context
                                        ;;  :user user
                                        ;;  :chat-id chat-id
                                         :status status
                                         :reply text
                                         :event-trace event-trace}]]})

                         ;; type :finish
             (let [content (clojure.string/join " " text)]
               (tap> [:agents.domain.chat/llm-reply-received {:reply text}])
                           ;; Send final message to UI
               (inject-in-flow! {:state state
                                 :channel-path [:llm :to-ui]
                                 :event [[:agents.domain.chat/llm-reply-received
                                          {:id id
                                           :mulog/ctx local-context
                                          ;;  :user user
                                          ;;  :chat-id chat-id
                                           :status status
                                           :reply text
                                           :event-trace event-trace}]]})

                           ;; Add to history in tools only when there is non-empty content
               (when (not (clojure.string/blank? content))
                 (inject-in-flow! {:state state
                                   :channel-path [:llm :to-tools]
                                   :event [[:add-assistant-message {:id id
                                                                    :mulog/ctx local-context
                                                                    ;; :chat-id chat-id
                                                                    ;; :user user
                                                                    :content content
                                                                    :event-trace event-trace}]]})))))

         :on-prepare-tool-call
         (fn [{:keys [name arguments-text status]}]
           (inject-in-flow! {:state state
                             :channel-path [:llm :to-ui]
                             :event [[:agents.domain.chat/partial-llm-tools-reply-received
                                      {:id id
                                       :mulog/ctx local-context
                                      ;;  :user user
                                      ;;  :chat-id chat-id
                                       :reply arguments-text
                                       :tool-name name
                                       :status status
                                       :event-trace event-trace}]]}))

         :on-tools-called
         (fn [tool-calls]
           (inject-in-flow! {:state state
                             :channel-path [:llm :to-ui]
                             :event [[:agents.domain.chat/llm-tools-reply-received
                                      {:id id
                                       :mulog/ctx local-context
                                      ;;  :user user
                                      ;;  :chat-id chat-id
                                       :tool-calls tool-calls
                                       :event-trace event-trace}]]})
           (inject-in-flow! {:state state
                             :channel-path [:llm :to-tools]
                             :event [[:execute-tools {:id id
                                                      :mulog/ctx local-context
                                                      ;; :chat-id chat-id
                                                      ;; :user user
                                                      :tool-calls tool-calls
                                                      :event-trace event-trace}]]}))

         :on-error
         (fn [error]
           (inject-in-flow! {:state state
                             :channel-path [:llm :to-ui]
                             :event [[:agents.domain.chat/llm-error
                                      {:id id
                                       :mulog/ctx local-context
                                      ;;  :user user
                                      ;;  :chat-id chat-id
                                       :error error
                                       :event-trace event-trace}]]}))

         :on-reason (fn [{:keys [text status]}] #_[& reason-response]
                        ;; (prn 777773434 reason-response (type reason-response))
                      (inject-in-flow! {:state state
                                        :channel-path [:llm :to-ui]
                                        :event [[:agents.domain.chat/partial-reasoning-reply-received
                                                 {:id id
                                                  :mulog/ctx local-context
                                                  ;; :user user
                                                  ;; :chat-id chat-id
                                                  :reply text
                                                  :status status                                         ;; :tool-name name
                                                  :event-trace event-trace}]]}))
         :on-usage-updated (fn [& usage-response]
                             (prn 8888545 usage-response))})

       (catch Exception e
         (inject-in-flow! {:state state
                           :channel-path [:llm :to-ui]
                           :event [[:agents.domain.chat/llm-error
                                    {:id id
                                    ;;  :user user
                                    ;;  :chat-id chat-id
                                     :error (str "Exception in LLM proc: " (.getMessage e))
                                     :event-trace event-trace}]]})))

     ;; Return immediately with updated state (remove from pending)
     [state
      nil])))

(tests
 "Unit tests for on-llm-complete-requested!"

 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:flow-ref (atom :mock-flow)
                   :config {:test-config true}
                   :inject-in-flow! inject-in-flow-spy}
       event-payload {:id "test-id"

                      :messages [{:role "user" :content "test message"}]
                      :system-instructions "test instructions"
                      :event-trace {:ai-chat/id "test-chat-id"
                                    :user {:user/id "test-user"}
                                    :message-id 5}
                      :tools []}]

   ;; Test initial injection
   (with-redefs [li/complete! (spy/spy (fn [_] nil))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state event-payload)
     (spy/calls inject-in-flow-spy) := [[{:state _
                                          :channel-path [:llm :to-ui]
                                          :event [[:agents.domain.chat/llm-reply-requested
                                                   {:id "test-id"
                                                    :mulog/ctx {:mulog/trace-id "test-trace"}
                                                    :event-trace {:ai-chat/id "test-chat-id"
                                                                  :user {:user/id "test-user"}
                                                                  :message-id 5
                                                                  :completion-step 1}}]]}]])))

(tests
 "Test on-first-response-received callback"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}]
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

     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/first-llm-reply-received
                                                     {:id "test-id"
                                                      :mulog/ctx {:mulog/trace-id "test-trace"}
                                                      :event-trace {:message-id 5
                                                                    :completion-step 2
                                                                    :ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}}}]]}]])))

(tests
 "Test on-message-received callback with type :text"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :text :status :started :text ""}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/partial-llm-reply-received
                                                     {:id "test-id"
                                                      :mulog/ctx {:mulog/trace-id "test-trace"}
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}
                                                      :status :started
                                                      :reply ""}]]}]]))

 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :text :status :delta :text "partial reply"}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/partial-llm-reply-received
                                                     {:id "test-id"
                                                      :mulog/ctx {:mulog/trace-id "test-trace"}
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}
                                                      :status :delta
                                                      :reply "partial reply"}]]}]])))

(tests
 "Test on-message-received callback with type :finish"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:flow-ref (atom :mock-flow)
                   :inject-in-flow! inject-in-flow-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-message-received opts) {:type :finish :status :finish :text ["final" "reply"]}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/llm-reply-received
                                                     {:id "test-id"
                                                      :mulog/ctx _
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}
                                                      :status :finish
                                                      :reply ["final" "reply"]}]]}]
                                        [{:state mock-state
                                          :channel-path [:llm :to-tools]
                                          :event [[:add-assistant-message
                                                   {:id "test-id"
                                                    :mulog/ctx _
                                                    :event-trace {:ai-chat/id "test-chat-id"
                                                                  :user {:user/id "test-user"}
                                                                  :completion-step 1}

                                                    :content "final reply"}]]}]])))

(tests
 "Test on-prepare-tool-call callback"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-prepare-tool-call opts) {:name "test-tool" :arguments-text "test args" :status :delta}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/partial-llm-tools-reply-received
                                                     {:id "test-id"
                                                      :mulog/ctx {:mulog/trace-id "test-trace"}
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}
                                                      :status :delta
                                                      :reply "test args"
                                                      :tool-name "test-tool"}]]}]])))

(tests
 "Test on-tools-called callback"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}
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
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/llm-tools-reply-received
                                                     {:id "test-id"
                                                      :mulog/ctx {:mulog/trace-id "test-trace"}
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}

                                                      :tool-calls tool-calls}]]}]
                                        [{:state mock-state
                                          :channel-path [:llm :to-tools]
                                          :event [[:execute-tools
                                                   {:id "test-id"
                                                    :mulog/ctx {:mulog/trace-id "test-trace"}
                                                    :event-trace _

                                                    :tool-calls tool-calls}]]}]])))

(tests
 "Test on-error callback"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}]
   (with-redefs [li/complete! (fn [opts]
                                ((:on-error opts) {:error "test error"}))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/llm-error
                                                     {:id "test-id"
                                                      :mulog/ctx {:mulog/trace-id "test-trace"}
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}

                                                      :error {:error "test error"}}]]}]])))

(tests
 "Test on-reason callback"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}]
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
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/partial-reasoning-reply-received
                                                     {:id "test-id"
                                                      :mulog/ctx {:mulog/trace-id "test-trace"}
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}
                                                      :status :thinking

                                                      :reply "reasoning"}]]}]])))

(tests
 "Test exception handling"
 (let [inject-in-flow-spy (spy/spy (fn [_]))
       mock-state {:inject-in-flow! inject-in-flow-spy}]
   (with-redefs [li/complete! (fn [_] (throw (Exception. "test exception")))
                 log/local-context (fn [] {:mulog/trace-id "test-trace"})]
     (on-llm-complete-requested! mock-state {:id "test-id"
                                             :event-trace {:ai-chat/id "test-chat-id"
                                                           :user {:user/id "test-user"}}
                                             :messages [{:role "user" :content "test message"}]
                                             :system-instructions "test instructions"
                                             :tools []})
     (spy/calls inject-in-flow-spy) := [_ [{:state mock-state
                                            :channel-path [:llm :to-ui]
                                            :event [[:agents.domain.chat/llm-error
                                                     {:id "test-id"
                                                      :event-trace {:ai-chat/id "test-chat-id"
                                                                    :user {:user/id "test-user"}
                                                                    :completion-step 1}

                                                      :error "Exception in LLM proc: test exception"}]]}]])))

(comment

  (log/trace :aa
             [:a 1]
             (prn 3)))