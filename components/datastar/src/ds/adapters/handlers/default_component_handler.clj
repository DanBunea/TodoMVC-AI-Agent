(ns ds.adapters.handlers.default-component-handler
  "Dynamic endpoint handler for component events.
   Handles routes like /:component/:component-id/:event"
  (:require
   [ds.adapters.handlers.discovery :as discovery]
   [ds.adapters.handlers.connected-component-handler :as cch]
   [ds.adapters.handlers.pipeline :as pipeline]
   [ds.adapters.handlers.response :refer [->html]]
   [state-machines.interface :refer [transition]]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn- assoc-component-fn-and-machine
  "Associates :machine-var and :component-fn-var by discovering the component.
   Delegates to shared pipeline."
  [m]
  (pipeline/assoc-component-fn-and-machine m))

(tests
 (def machine {:id :test-component
               :initial :initial-state
               :states {:initial-state {}}})
 (def component-fn (fn [_ctx] [:div "test component"]))
 (def machine-var (var machine))
 (def component-fn-var (var component-fn))
 (with-redefs [discovery/discover-component-in-ds-adapters-component (spy/spy (fn
                                                                                ([component-name]
                                                                                 {:machine machine-var
                                                                                  :component-fn component-fn-var})
                                                                                ([component-name controller-name]
                                                                                 {:machine machine-var
                                                                                  :component-fn component-fn-var})))]
   (def result (assoc-component-fn-and-machine {:component-name "component-name"}))

   (var? (:machine-var result)) := true
   (var? (:component-fn-var result)) := true
   @(:machine-var result) := machine
   @(:component-fn-var result) := component-fn))

(defn- assoc-event
  "Extracts event parameters from the request. Delegates to shared pipeline."
  [m event-name]
  (pipeline/assoc-event m event-name))

(tests
 ;; Test 1: body-params with component-id key, params with logged-user and component-name
 (def request-1 {:body-params {:c1 {:count 5} :other-data "value"}
                 :params {:logged-user {:user/id "123"}
                          :component-name :counter
                          :other-param "param-value"}})

 (assoc-event {:request request-1
               :component-id "c1"
               :other-key "preserved"}
              "increment") := {:request request-1
                               :component-id "c1"
                               :other-key "preserved"
                               :event ["increment" {:other-data "value"
                                                    :other-param "param-value"}]}

 ;; Test 2: empty body-params, params with logged-user and component-name
 (def request-2 {:body-params {}
                 :params {:logged-user {:user/id "123"}
                          :component-name :test
                          :param1 "value1"
                          :param2 "value2"}})

 (assoc-event {:request request-2
               :component-id "xyz"}
              "save") := {:request request-2
                          :component-id "xyz"
                          :event ["save" {:param1 "value1"
                                          :param2 "value2"}]}

 ;; Test 3: body-params with component-id key, empty params
 (def request-3 {:body-params {:c2 {:state :active} :data "test"}
                 :params {}})

 (assoc-event {:request request-3
               :component-id "c2"}
              "update") := {:request request-3
                            :component-id "c2"
                            :event ["update" {:data "test"}]})

(defn- assoc-initial-ctx-and-state
  "Extracts context from the request. Delegates to shared pipeline."
  [m]
  (pipeline/assoc-initial-ctx-and-state m))

(tests
 ;; Test 1: body-memory with state, logged-user present
 (def request-1 {:body-params {:c1 {:count 5 :state :active}}
                 :params {:logged-user {:user/id "123" :user/name "Test"}}})
 (def machine-1 {:id :test :initial :initial-state :states {}})
 (def machine-1-var (var machine-1))

 (assoc-initial-ctx-and-state {:request request-1
                               :component-id "c1"
                               :machine-var machine-1-var
                               :other-key "preserved"}) := {:request request-1
                                                            :component-id "c1"
                                                            :machine-var machine-1-var
                                                            :other-key "preserved"
                                                            :initial-ctx {:state :active
                                                                          :memory {:count 5}
                                                                          :component-id "c1"
                                                                          :logged-user {:user/id "123" :user/name "Test"}}}

 ;; Test 2: body-memory without state (uses machine initial), logged-user present
 (def request-2 {:body-params {:c2 {:count 10}}
                 :params {:logged-user {:user/id "456"}}})
 (def machine-2 {:id :test :initial :default-state :states {}})
 (def machine-2-var (var machine-2))

 (assoc-initial-ctx-and-state {:request request-2
                               :component-id "c2"
                               :machine-var machine-2-var}) := {:request request-2
                                                                :component-id "c2"
                                                                :machine-var machine-2-var
                                                                :initial-ctx {:state :default-state
                                                                              :memory {:count 10}
                                                                              :component-id "c2"
                                                                              :logged-user {:user/id "456"}}}

 ;; Test 3: no body-memory (nil), no logged-user
 (def request-3 {:body-params {}
                 :params {}})
 (def machine-3 {:id :test :initial :start :states {}})
 (def machine-3-var (var machine-3))

 (assoc-initial-ctx-and-state {:request request-3
                               :component-id "c3"
                               :machine-var machine-3-var}) := {:request request-3
                                                                :component-id "c3"
                                                                :machine-var machine-3-var
                                                                :initial-ctx {:state :start
                                                                              :memory nil
                                                                              :component-id "c3"
                                                                              :logged-user nil}}

 ;; Test 4: body-memory with state and other keys
 (def request-4 {:body-params {:c4 {:state :loading :data "test" :count 0}}
                 :params {:logged-user {:user/id "789"}}})
 (def machine-4 {:id :test :initial :idle :states {}})
 (def machine-4-var (var machine-4))

 (assoc-initial-ctx-and-state {:request request-4
                               :component-id "c4"
                               :machine-var machine-4-var}) := {:request request-4
                                                                :component-id "c4"
                                                                :machine-var machine-4-var
                                                                :initial-ctx {:state :loading
                                                                              :memory {:data "test" :count 0}
                                                                              :component-id "c4"
                                                                              :logged-user {:user/id "789"}}})

(defn- assoc-process-event-results
  "Processes the event through the state machine. Delegates to shared pipeline."
  [m]
  (pipeline/assoc-process-event-results m))

(tests
 ;; Test 1: basic transition with state and event
 (def initial-ctx-1 {:state "active"
                     :memory {:count 5}
                     :component-id "c1"
                     :logged-user {:user/id "123"}})
 (def event-1 ["increment" {:amount 1}])
 (def machine-1 {:id :test
                 :initial :idle
                 :states {:idle {}}})
 (def machine-1-var (var machine-1))
 (def transition-result-1 {:state :active
                           :memory {:count 6}
                           :component-id "c1"})

 (with-redefs [transition (spy/spy (fn [machine from-state [event-name event-params] _]
                                     transition-result-1))]
   (assoc-process-event-results {:initial-ctx initial-ctx-1
                                 :event event-1
                                 :machine-var machine-1-var
                                 :other-key "preserved"}) := {:initial-ctx initial-ctx-1
                                                              :event event-1
                                                              :machine-var machine-1-var
                                                              :other-key "preserved"
                                                              :ctx {:state :active
                                                                    :memory {:count 6}
                                                                    :component-id "c1"}}

   (spy/calls transition) := [[machine-1 {:memory {:count 5}
                                          :component-id "c1"
                                          :logged-user {:user/id "123"}
                                          :state :active}
                               [:INCREMENT {:amount 1}] {:state-key :state}]])

;; Test 2: transition with different event name (should be uppercased)
 (def initial-ctx-2 {:state "idle"
                     :memory {}
                     :component-id "c2"})
 (def event-2 ["save" {:data "test"}])
 (def machine-2 {:id :test :initial :idle :states {}})
 (def machine-2-var (var machine-2))
 (def transition-result-2 {:state :saved
                           :memory {:data "test"}})

 (with-redefs [transition (spy/spy (fn [machine from-state [event-name event-params] _]
                                     transition-result-2))]
   (assoc-process-event-results {:initial-ctx initial-ctx-2
                                 :event event-2
                                 :machine-var machine-2-var}) := {:initial-ctx initial-ctx-2
                                                                  :event event-2
                                                                  :machine-var machine-2-var
                                                                  :ctx {:state :saved
                                                                        :memory {:data "test"}}}

   (spy/called-once-with? transition
                          machine-2
                          {:memory {}
                           :component-id "c2"
                           :state :idle}
                          [:SAVE {:data "test"}]
                          {:state-key :state}) := true)

 ;; Test 3: transition with empty event params
 (def initial-ctx-3 {:state "loading"
                     :component-id "c3"})
 (def event-3 ["init" {}])
 (def machine-3 {:id :test :initial :idle :states {}})
 (def machine-3-var (var machine-3))
 (def transition-result-3 {:state :initialized})

 (with-redefs [transition (spy/spy (fn [machine from-state [event-name event-params] +]
                                     transition-result-3))]
   (assoc-process-event-results {:initial-ctx initial-ctx-3
                                 :event event-3
                                 :machine-var machine-3-var}) := {:initial-ctx initial-ctx-3
                                                                  :event event-3
                                                                  :machine-var machine-3-var
                                                                  :ctx {:state :initialized}}

   (spy/called-once-with? transition
                          machine-3
                          {:component-id "c3"
                           :state :loading}
                          [:INIT {}]
                          {:state-key :state}) := true))

(defn- assoc-html
  "Renders the component and associates the HTML. Delegates to shared pipeline."
  [m]
  (pipeline/assoc-html m))

(defn- component-responses?
  "Returns true if the component returned a list of effects (like chat_simulator2)
   rather than plain hiccup. Effects are a seq of vectors: [[:effect-name ...] ...]."
  [html]
  (and (vector? html) (vector? (first html))))

(defn- extract-html-from-responses
  "Extracts the hiccup from the first :broadcast-elements! effect for HTTP fallback."
  [responses]
  (some (fn [[effect-name & effect-args]]
          (when (= :broadcast-elements! effect-name)
            (first effect-args)))
        responses))

(defn- broadcast-to-connections
  "If the component returned a list of effects, dispatches them via broadcast-replies!
   when there are connected clients. Otherwise passes plain hiccup through unchanged."
  [{:keys [component-name component-id html] :as m}]
  (if (component-responses? html)
    (assoc m :html [] :broadcast-done (cch/broadcast-to-connections! component-name component-id html))
    (assoc m :broadcast-done false)))

(defn- default-component-handler
  "Default handler for component events.
   Discovers component, processes event through state machine,
   renders component, broadcasts to connected clients, and returns HTML.
   The HTTP response always contains the rendered HTML.
   If there are connected SSE clients and the component returned effects,
   the result is also broadcast to them."
  [req component-name component-id event-name controller-name]
  (try
    (-> (pipeline/process-component-event req component-name component-id event-name controller-name)
        broadcast-to-connections
        :html
        ->html)
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body (str "Error processing component event: " (.getMessage e) "\n"
                  "Component: " component-name "\n"
                  "Component-ID: " component-id "\n"
                  "Event: " event-name "\n"
                  (when controller-name (str "Controller: " controller-name "\n"))
                  "Error message: " (with-out-str (.getMessage e)) "\n"
                  "Stack trace: " (with-out-str (.printStackTrace e)))})))

(defn default-handler
  "Main handler for dynamic component events.
   Supports two route patterns:
   - /:component/:component-id/:event (backward compatible)
   - /:component/:component-id/:controller/:event (with optional controller)
   
   Example: /ds/user-form/abc123/save
   - component: user-form
   - component-id: abc123
   - event: save
   
   Example with controller: /ds/counter/c1/counter-controller/increment
   - component: counter
   - component-id: c1
   - controller: counter-controller
   - event: increment
   
   Supports both synchronous (1-arity) and asynchronous (3-arity) handlers.
   Async version: [req respond raise]"
  ([req]
   (let [component-name (get-in req [:path-params :component])
         component-id (get-in req [:path-params :component-id])
         controller-name (get-in req [:path-params :controller])
         event-name (get-in req [:path-params :event])]
     (cond
       (not component-name)
       {:status 400 :body "Missing component name"}

       (not component-id)
       {:status 400 :body "Missing component-id"}

       (not event-name)
       {:status 400 :body "Missing event name"}

       :else
       (default-component-handler req
                                  (keyword component-name)
                                  component-id
                                  event-name
                                  controller-name))))
  ([req respond raise]
   (try
     (let [component-name (get-in req [:path-params :component])
           component-id (get-in req [:path-params :component-id])
           controller-name (get-in req [:path-params :controller])
           event-name (get-in req [:path-params :event])]
       (cond
         (not component-name)
         (respond {:status 400 :body "Missing component name"})

         (not component-id)
         (respond {:status 400 :body "Missing component-id"})

         (not event-name)
         (respond {:status 400 :body "Missing event name"})

         :else
         (try
           (let [response (default-component-handler req
                                                     (keyword component-name)
                                                     component-id
                                                     event-name
                                                     controller-name)]
             (respond response))
           (catch Exception e
             (raise e)))))
     (catch Exception e
       (raise e)))))

