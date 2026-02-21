(ns agents.use-cases.chat.procs.ui
  (:require
   [clojure.core.async.flow :as flow]
   [agents.domain.chat :as chat-domain]
   [agents.use-cases.flow-commons :as fc]
   [logging.interface :as log]))

(defn ui!
  "UI proc - bridges external channels with internal flow.
   
   External in-chan → :external-in → routes to :to-llm or :to-tools
   :from-llm or :from-tools → :external-out → External out-chan"
  ([] {:ins {:external-in "Events from external world (user interactions)"
             :from-llm "Streaming responses from LLM"
             :from-tools "State/results from tools"}
       :outs {:to-llm "User messages to LLM"
              :to-tools "State requests to tools"
              :external-out "Events to external world"}})

  ([args]
   (-> args
       fc/init
       (assoc ::flow/in-ports {:external-in (get-in args [:config :in-chan])}
              ::flow/out-ports {:external-out (get-in args [:config :out-chan])})))

  ([state transition]
   (fc/transition state transition))

  ([state in msg]
   (let [[event-name event-payload] msg
         mulog-ctx (:mulog/ctx event-payload)
         trace-level (if (contains? chat-domain/verbose-trace-event-names event-name) :verbose :info)]
     (log/with-context mulog-ctx
       (log/trace-at
        trace-level
        (str "ui/" event-name)
        [:in in
         :event-name event-name
         :event-payload (dissoc event-payload :mulog/ctx)]
        (let [local-context (log/local-context)]
          (if-not (:ready state)
            [state nil]

            (case in
        ;; External events come in - route to appropriate proc
              :external-in
              (case event-name
                :agents.domain.chat/user-connected
                [state {:to-tools [[:get-chat-state (assoc event-payload :mulog/ctx local-context)]]}]

                :agents.domain.chat/user-message-sent
                [state {:to-llm [[event-name (assoc event-payload :mulog/ctx local-context)]]}]

                :agents.domain.chat/stop-requested
                [state {:to-llm [[event-name (assoc event-payload :mulog/ctx local-context)]]}]

            ;; Unknown event
                [state nil])

        ;; Messages from LLM - forward to external world
              :from-llm
              [state {:external-out [[event-name (assoc event-payload :mulog/ctx local-context)]]}]

        ;; Messages from tools - forward to external world
              :from-tools
              [state {:external-out [[event-name (assoc event-payload :mulog/ctx local-context)]]}]

        ;; Default
              [state nil]))))))))