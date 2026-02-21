(ns ds.adapters.handlers.pipeline
  "Shared component event processing pipeline.
   Extracts the core steps (discovery, event extraction, state machine,
   rendering) used by both the default HTTP handler and the SSE connect handler."
  (:require
   [ds.adapters.handlers.discovery :as discovery]
   [clojure.string :as str]
   [state-machines.interface :refer [transition]]))

(defn assoc-component-fn-and-machine
  "Associates :machine-var and :component-fn-var by discovering the component.
   Keeps vars instead of dereferencing immediately so changes in REPL are reflected.
   Optionally uses controller-name if provided, otherwise uses default controller naming."
  [{:keys [component-name controller-name] :as m}]
  (let [discovered (discovery/discover-component-in-ds-adapters-component component-name controller-name)]
    (-> m
        (assoc :machine-var (:machine discovered))
        (assoc :component-fn-var (:component-fn discovered)))))

(defn assoc-event
  "Extracts event parameters from the request."
  [{:keys [request component-id] :as m} event-name]
  (assoc m :event [event-name (-> request
                                  :body-params
                                  (dissoc (keyword component-id))
                                  (merge (get-in request [:params]))
                                  (dissoc :logged-user :component-name))]))

(defn assoc-initial-ctx-and-state
  "Extracts context from the request."
  [{:keys [request component-id machine-var] :as m}]
  (let [machine @machine-var  ;; Dereference var to get latest value
        logged-user (get-in request [:params :logged-user])
        cid (keyword component-id)
        body-memory (get-in request [:body-params cid])

        memory (-> body-memory
                   (dissoc :state))
        state (or (-> body-memory
                      :state)
                  (:initial machine))]
    (-> (assoc m :initial-ctx {:state state
                               :memory memory
                               :component-id component-id
                               :logged-user logged-user}))))

(defn assoc-process-event-results
  "Processes the event through the state machine and associates results.
   Skips DataStar internal events (RENDERER.* and INSTALLHOOK.*) which are
   automatically sent by the client-side JavaScript library."
  [{:keys [initial-ctx event machine-var] :as m}]
  (let [machine @machine-var  ;; Dereference var to get latest value
        [event-name event-params] event
        event-keyword (keyword (str/upper-case event-name))
        event-name-str (name event-keyword)
        ;; Skip DataStar internal events
        skip-event? (or (str/starts-with? event-name-str "RENDERER.")
                        (str/starts-with? event-name-str "INSTALLHOOK."))]
    (assoc m :ctx
           (if skip-event?
             ;; For internal events, just return the context unchanged (no state transition)
             initial-ctx
             ;; Normal event processing through state machine
             (transition
              machine
              (update initial-ctx :state keyword)
              [event-keyword event-params]
              {:state-key :state})))))

(defn assoc-html
  "Renders the component and associates the HTML."
  [{:keys [component-fn-var ctx] :as m}]
  (let [component-fn @component-fn-var]  ;; Dereference var to get latest value
    (assoc m :html (component-fn ctx))))

(defn process-component-event
  "Runs the component pipeline: discovery, event extraction, state machine,
   and rendering. Returns the result map with :html key containing the
   rendered component (plain hiccup or effects list)."
  [req component-name component-id event-name controller-name]
  (-> {:request req
       :component-name component-name
       :component-id component-id
       :controller-name controller-name}
      assoc-component-fn-and-machine
      (assoc-event event-name)
      assoc-initial-ctx-and-state
      assoc-process-event-results
      assoc-html))
