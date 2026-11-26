(ns agents.use-cases.chat.procs.llm
  (:require
   [clojure.core.async :as a]
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

(defn- close-and-drain!
  "Closes the completion callback in-port and drains it. close! alone does not
   release a callback parked in >!! on a full buffer — taking does — and after
   flow/stop nobody takes any more. Once drained, every late >!! from a
   completion still streaming returns false: a no-op, not an error."
  [ch]
  (a/close! ch)
  (while (some? (a/poll! ch))))

(defn complete!
  "LLM completion proc. Completions stream asynchronously; their callbacks come
   back through the proc's own :from-completion in-port, so every event the
   proc emits leaves through this step function."
  ;; :ins order is READ PRIORITY: finish running completions first.
  ([] {:ins {:from-completion "callbacks from LLM completions streaming"
             :from-tools "cart/history responses from tools"
             :from-ui "user messages from UI"}
       :outs {:to-ui "streaming responses to UI"
              :to-tools "state requests to tools"}})

  ([args]
   (let [callback-chan (a/chan 1024)]
     (-> args
         fc/init
         (assoc :config (li/config))
         ;; Completion callbacks hand events back through this in-port. >!!
         ;; blocks while the proc lags — backpressure on the provider stream.
         ;; Callers are the HTTP client's streaming thread, never go blocks.
         (assoc ::flow/in-ports {:from-completion callback-chan})
         (assoc :callback-chan callback-chan)
         (assoc :callback! (fn [event] (a/>!! callback-chan event)))
         (assoc :stopped {}))))

  ([state transition]
   ;; Late callbacks from completions still streaming after stop are dropped by
   ;; the closed in-port (flow/start re-runs init, so a restart gets a new one).
   (when (= transition ::flow/stop)
     (some-> (:callback-chan state) close-and-drain!))
   (fc/transition state transition))

  ([state in msg]
   (if (= ::llmc/emit (first msg))
     ;; A completion callback: re-emit its events on this proc's own output
     ;; port. Untraced — one per streamed chunk; the receiving proc traces it.
     (let [{:keys [out msgs]} (second msg)]
       [state {out msgs}])
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

                  [state nil]))))))))))

(tests
 "a completion callback is re-emitted on the :llm proc's own output port,
  untouched (no stop bookkeeping)"
 (complete! {:ready true} :from-completion [::llmc/emit {:out :to-ui :msgs [[:x {:id 1}]]}])
 := [{:ready true} {:to-ui [[:x {:id 1}]]}]

 "close-and-drain! releases a callback parked on a full in-port, and every
  later put is a no-op false"
 (let [ch (a/chan 1)
       _ (a/>!! ch :buffered)
       worker (future (a/>!! ch :parked))]
   (close-and-drain! ch)
   (boolean? (deref worker 1000 ::still-parked)) := true
   (a/>!! ch :late) := false))
