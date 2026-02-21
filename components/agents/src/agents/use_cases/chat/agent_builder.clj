(ns agents.use-cases.chat.agent-builder
  "Generic chat agent builder. Extracts the shared boilerplate for creating
   a chat agent flow (on-user-message-fn, on-execute-tools, create-chat-agent-flow,
   create-and-start!) so that each concrete agent only needs to supply:
   - system-instructions-fn  (fn [locale] -> string)
   - tool-descriptions-fn    (fn [locale] -> tool-descriptions-vec)
   - execute-tools-fn        (fn [event-trace tool-calls locale] -> execution-results)"
  (:require
   [agents.use-cases.chat.procs.llm :as llm]
   [agents.use-cases.chat.procs.ui :as ui]
   [agents.use-cases.chat.procs.tools :as imt]
   [clojure.core.async.flow :as flow]
   [logging.interface :as log]))

;; ---------------------------------------------------------------------------
;; Generic callback factories
;; ---------------------------------------------------------------------------

(defn- make-on-user-message-fn
  "Returns an on-user-message-fn that uses the given system-instructions-fn
   and tool-descriptions-fn to build the LLM request.
   Caller must persist the user message before sending user-message-sent."
  [system-instructions-fn tool-descriptions-fn]
  (fn [state {:keys [id] :as event-payload}]
    (let [mulog-ctx (:mulog/ctx event-payload)
          {:keys [load-chat-messages-fn locale]} state]
      (log/with-context mulog-ctx
        (log/trace
         ::on-user-message-fn
         [:event-payload (dissoc event-payload :mulog/ctx)]
         (let [local-context (log/local-context)
               event-trace (:event-trace event-payload)
               chat-id (:ai-chat/id event-trace)
               messages (->> (load-chat-messages-fn chat-id)
                             (mapv #(select-keys % [:role :content])))]
           [state
            {:to-llm [[:llm-complete-requested {:id id
                                                :mulog/ctx local-context
                                                :event-trace event-trace
                                                :messages messages
                                                :system-instructions (system-instructions-fn (or locale :en))
                                                :tools (tool-descriptions-fn (or locale :en))}]]}]))))))

(defn- make-on-execute-tools
  "Returns an on-execute-tools fn that uses the given execute-tools-fn,
   system-instructions-fn, and tool-descriptions-fn."
  [execute-tools-fn system-instructions-fn tool-descriptions-fn]
  (fn [state {:keys [id event-trace tool-calls] :as event-payload}]
    (let [mulog-ctx (:mulog/ctx event-payload)
          {:keys [load-chat-messages-fn add-chat-message-fn locale]} state]
      (log/with-context mulog-ctx
        (log/trace
         ::on-execute-tools
         [:event-payload (dissoc event-payload :mulog/ctx)]

         (let [local-context (log/local-context)
               chat-id (:ai-chat/id event-trace)
               execution-results (execute-tools-fn event-trace tool-calls (or locale :en))
               _ (doseq [result execution-results]
                   (when (:tool-call-msg result)
                     (add-chat-message-fn chat-id (:tool-call-msg result))
                     (add-chat-message-fn chat-id (:tool-call-output-msg result))))
               messages (->> (load-chat-messages-fn chat-id)
                             (mapv #(select-keys % [:role :content])))]

           [state
            {:to-ui (->> execution-results
                         (mapv (fn [execution-result] [:agents.domain.chat/tool-applied
                                                       {:id id
                                                        :mulog/ctx local-context
                                                        :event-trace (:event-trace event-payload)
                                                        :tool-call-msg (:tool-call-msg execution-result)
                                                        :tool-call-output-msg (:tool-call-output-msg execution-result)
                                                        :result (:result execution-result)}])))
             :to-llm [[:tools-applied {:id id
                                       :mulog/ctx local-context
                                       :event-trace (:event-trace event-payload)
                                       :messages messages
                                       :system-instructions (system-instructions-fn (or locale :en))
                                       :tools (tool-descriptions-fn (or locale :en))}]]}]))))))

;; ---------------------------------------------------------------------------
;; Flow creation
;; ---------------------------------------------------------------------------

(defn create-chat-agent-flow
  "Creates a chat agent flow from configuration.

   Decentralized flow where procs communicate directly:

   1. **External -> UI -> LLM**: User messages flow from in-chan through UI to LLM
   2. **External -> UI -> Tools**: State requests (user-connected, etc.)
   3. **LLM -> Tools**: Tool execution requests
   4. **LLM -> UI -> External**: Streaming responses via flow injection to out-chan
   5. **Tools -> UI -> External**: Tool results to out-chan
   6. **Tools -> LLM**: Tool results for continued completion

   Chat message history is managed via injected load-chat-messages-fn / add-chat-message-fn.

   ## Config Map

   Required keys:
   - :in-chan                 - External channel to receive events
   - :out-chan                - External channel to send domain events
   - :load-chat-messages-fn  - (fn [chat-id] -> [{:role ... :content ...}])
   - :add-chat-message-fn   - (fn [chat-id message] -> any)
   - :system-instructions-fn - (fn [locale] -> string)
   - :tool-descriptions-fn   - (fn [locale] -> tool-descriptions-vec)
   - :execute-tools-fn       - (fn [event-trace tool-calls locale] -> execution-results)

   Optional keys:
   - :locale                 - keyword, defaults to :en"
  [{:keys [locale load-chat-messages-fn add-chat-message-fn
           system-instructions-fn tool-descriptions-fn execute-tools-fn]
    :or {locale :en}
    :as config}]
  (log/trace
   ::create-chat-agent-flow
   [:config config]
   (let [flow-ref (atom nil)
         on-user-message (make-on-user-message-fn system-instructions-fn tool-descriptions-fn)
         on-execute-tools (make-on-execute-tools execute-tools-fn system-instructions-fn tool-descriptions-fn)
         f (flow/create-flow
            {:procs {:llm   {:proc (flow/process #'llm/complete!)
                             :args {:flow-ref flow-ref
                                    :config config}}
                     :ui    {:proc (flow/process #'ui/ui!)
                             :args {:config config}}
                     :tools {:proc (flow/process #'imt/run-tool!)
                             :args {:load-chat-messages-fn load-chat-messages-fn
                                    :add-chat-message-fn add-chat-message-fn
                                    :execute-tools-fn on-execute-tools
                                    :process-user-message-fn on-user-message
                                    :locale locale}}}
             :conns [[[:ui :to-llm]        [:llm :from-ui]]
                     [[:ui :to-tools]      [:tools :from-ui]]
                     [[:llm :to-ui]        [:ui :from-llm]]
                     [[:llm :to-tools]     [:tools :from-llm]]
                     [[:tools :to-ui]      [:ui :from-tools]]
                     [[:tools :to-llm]     [:llm :from-tools]]]})]
     (reset! flow-ref f)
     f)))

(defn create-and-start!
  "Creates a chat agent flow, starts it, and resumes it.
   Accepts the same config map as create-chat-agent-flow.
   Returns the started flow."
  [config]
  (let [f (create-chat-agent-flow config)
        _chs (flow/start f)]
    (flow/resume f)
    f))
