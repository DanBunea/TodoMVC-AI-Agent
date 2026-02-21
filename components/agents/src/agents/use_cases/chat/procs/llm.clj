(ns agents.use-cases.chat.procs.llm
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.pprint]
   [clojure.string]
   [agents.use-cases.flow-commons :as fc]
   [llm.interface :as li]

   [logging.interface :as log]
   [agents.use-cases.chat.procs.llm.complete :as llmc]
   [agents.use-cases.chat.procs.llm.stopper :as stopper]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn inject! [{:keys [state channel-path event]}]
  (let [flow-ref (:flow-ref state)]
    (when @flow-ref
      (flow/inject @flow-ref channel-path event))))

(defn complete!
  "LLM completion proc that spawns async work and uses flow injection for callbacks"
  ([] {:ins {:from-ui "user messages from UI"
             :from-tools "cart/history responses from tools"}
       :outs {:to-ui "streaming responses to UI"
              :to-tools "state requests to tools"}})

  ([args]
   (-> args
       fc/init
       (assoc :config (li/config))
       (assoc :flow-ref (:flow-ref args))
       (assoc :inject-in-flow! inject!)
       (assoc :stopped {})
       #_(assoc :pending-requests {})))  ;; Track pending user messages

  ([state transition]
   (fc/transition state transition))

  ([state in msg]
   (let [[event-name event-payload] msg
         mulog-ctx (:mulog/ctx event-payload)]
     (log/with-context mulog-ctx
       (log/trace
        (str "llm/" event-name)
        [:in in
         :event-name event-name
         :event-payload (dissoc event-payload :mulog/ctx)]
        (let [local-context (log/local-context)]
          (if-not (:ready state)
            [state nil]
            (let [{:keys [id content event-trace]} event-payload]
              (case event-name
                :agents.domain.chat/user-message-sent
                [(-> state
                     (assoc-in [:pending-requests id] {:event-trace event-trace
                                                       :content content}))
                 {:to-tools [[:add-user-message-requested       {:id id
                                                                 :mulog/ctx local-context

                                                                 :event-trace event-trace
                                                                 :content content}]]}]

                :agents.domain.chat/stop-requested
                (let [message-id (get-in event-payload [:event-trace :message-id])]
                  [(update state :stopped stopper/record-stop message-id) nil])

                :llm-complete-requested
                (let [message-id (get-in event-payload [:event-trace :message-id])]
                  (if (stopper/is-stopped? (:stopped state) message-id)
                    [state nil]
                    (llmc/on-llm-complete-requested! state (assoc event-payload :mulog/ctx local-context))))

                :tools-applied
                (let [message-id (get-in event-payload [:event-trace :message-id])]
                  (if (stopper/is-stopped? (:stopped state) message-id)
                    [state nil]
                    (llmc/on-llm-complete-requested! state (assoc event-payload :mulog/ctx local-context))))

                [state nil])))))))))
