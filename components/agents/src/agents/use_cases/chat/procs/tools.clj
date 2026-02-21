(ns agents.use-cases.chat.procs.tools
  (:require
   [agents.use-cases.flow-commons :as fc]

   [logging.interface :as log]))

(defn run-tool!
  "Tools proc - manages chat state and executes tools"
  ([] {:ins {:from-ui "state requests from UI"
             :from-llm "state requests and tool executions from LLM"}
       :outs {:to-ui "state responses to UI"
              :to-llm "state responses to LLM"}})

  ([{:keys [load-chat-messages-fn add-chat-message-fn process-user-message-fn tools execute-tools-fn locale] :as args}]
   (-> (assoc args
              :load-chat-messages-fn load-chat-messages-fn
              :add-chat-message-fn add-chat-message-fn
              ;; :system-instructions system-instructions
              :tools tools
              :process-user-message-fn process-user-message-fn
              :execute-tools-fn execute-tools-fn
              :locale locale)
       fc/init))

  ([state transition]
   (fc/transition state transition))

  ([{:keys [system-instructions tools execute-tools-fn process-user-message-fn load-chat-messages-fn add-chat-message-fn] :as state} in msg]
   (let [[event-name event-payload] msg
         mulog-ctx (:mulog/ctx event-payload)]
     (log/with-context mulog-ctx
       (log/trace
        (str "tools/" event-name)
        [:in in
         :event-name event-name
         :event-payload (dissoc event-payload :mulog/ctx)]
        (let [local-context (log/local-context)]
          (if-not (:ready state)
            [state nil]

            (let [{:keys [id event-trace]} event-payload]
              #_(prn 343284 msg)
              (case event-name
         ;; UI requests chat state
                :get-chat-state
                [state {:to-ui [[:agents.domain.chat/chat-details-sent {:id id
                                                                        :mulog/ctx local-context
                                                                        ;; :chat-id chat-id
                                                                        ;; :user user
                                                                        :event-trace event-trace
                                                                        :messages
                                                                        (->> (load-chat-messages-fn (:ai-chat/id event-trace))
                                                                             (mapv #(select-keys % [:role :content])))}]]}]

         ;; Execute tool
                :execute-tools
                (execute-tools-fn state (assoc event-payload :locale (:locale state) :mulog/ctx local-context))

                :add-user-message-requested
                (process-user-message-fn state (assoc event-payload :locale (:locale state) :mulog/ctx local-context))

;; Add assistant message to history
                :add-assistant-message
                (do
                  (add-chat-message-fn (:ai-chat/id event-trace)
                                       {:id id :role "assistant" :content (:content event-payload)})
                  [state nil])

         ;; Default
                [state nil])))))))))
