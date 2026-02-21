(ns ds.adapters.handlers.connected-component-handler
  "Handler for establishing persistent SSE connections to component instances.
   Manages a `!connections` atom that tracks open SSE connections per
   (component, component-id), enabling future server-push to connected clients.
   On connection open, runs the component pipeline and sends the initial render via SSE."
  (:require
   [logging.interface :as log]
   [ds.adapters.handlers.pipeline :as pipeline]
   [ds.adapters.components.assistant.common :refer [loading-content]]
   [starfederation.datastar.clojure.adapter.ring :refer [->sse-response on-open on-close]]
   [starfederation.datastar.clojure.api :as d*]
   [dev.onionpancakes.chassis.compiler :as hc]
   [dev.onionpancakes.chassis.core :as h]
   [clojure.core.async :refer [chan <!! put!]]))

;; {component-id {user-id sse-gen }}
(def !connections (atom {}))

;; Channel for pushing UI updates after the initial SSE response.
;; Message format: [component-id [[:broadcast-elements! [:div ...]] ...]]
(def !ui-chan (chan 100))

(defn register-connection! [component component-id sse-gen logged-user]
  (swap! !connections assoc-in [#_component component-id (:user/id logged-user)] #_(fnil conj []) sse-gen))

(defn unregister-connection! [component component-id sse-gen logged-user]
  (swap! !connections update-in [#_component component-id]
         (fn [user-ids] (filterv #(not= % (:user/id logged-user)) user-ids))))

;;; Broadcast infrastructure

(defn- resolve-d* [x]
  (if (and (keyword? x) (= "d*" (namespace x)))
    @(ns-resolve 'starfederation.datastar.clojure.api (symbol (name x)))
    x))

(defn to-d* [m]
  (into {}
        (map (fn [[k v]]
               [(resolve-d* k) (resolve-d* v)]))
        m))

(defn broadcast-elements!
  ([connections elements]
   (broadcast-elements! connections elements {}))
  ([connections elements options]
   (doseq [c connections]
     (d*/patch-elements! c (h/html (hc/compile elements)) (to-d* options)))))

(defn broadcast-replies!
  "Dispatches a seq of component-responses to the connected SSE clients.
   Each response is a vector like [:broadcast-elements! elements options]."
  [component-responses connections]
  (let [valid-component-responses (filter first component-responses)]
    (doseq [[effect-name & effect-args] valid-component-responses]
      (case effect-name
        :broadcast-elements!
        (let [[elements options] effect-args]
          (if options
            (broadcast-elements! connections elements options)
            (broadcast-elements! connections elements)))

        :broadcast-loading
        (let [[chat-id] effect-args]
          (broadcast-elements!
           connections
           (loading-content)
           {d*/selector (str "#loading-container-" chat-id)
            d*/patch-mode d*/pm-inner}))

        :broadcast-hide-loading
        (let [[chat-id] effect-args]
          (broadcast-elements!
           connections
           []
           {d*/selector (str "#loading-container-" chat-id)
            d*/patch-mode d*/pm-inner}))

        ;; Default case - log unknown effect
        (log/trace ::unknown-broadcast-effect
                   [:effect-name effect-name
                    :effect-args effect-args]
                   nil)))))

(defn broadcast-to-connections!
  "Broadcasts component-responses to all SSE clients connected for
   the given (component, component-id).
   Returns true if broadcast was performed, false otherwise."
  [component component-id component-responses]
  (let [connections (->>
                     (get-in @!connections [#_component component-id])
                    ;;  (mapcat second)
                     vals
                     distinct
                     (into []))]
    (if (seq connections)
      (do (broadcast-replies! component-responses connections)
          true)
      false)))

;;; Async UI channel – push updates after the initial SSE response

(defn handle-ui-event
  "Handles a single message from !ui-chan.
   Expects [component-id component-responses] where component-responses
   is a seq of effect vectors like [[:broadcast-elements! [:div ...] opts]]."
  [[component-id component-responses]]
  (let [connections (->> (get-in @!connections [component-id])
                         vals
                         distinct
                         (into []))]
    (when (seq connections)
      (broadcast-replies! component-responses connections))))

(defn send-ui!
  "Puts component-responses onto !ui-chan for async broadcast to all
   SSE clients connected for the given component-id.
   component-responses is a seq of effect vectors,
   e.g. [[:broadcast-elements! [:div#foo \"hello\"] {:d*/selector \"#foo\"}]]."
  [component-id component-responses]
  (put! !ui-chan [component-id component-responses]))

(comment

  ;; After initial connection is established, push updates at any time:
  (send-ui! "todo-chat-42"
            [[:broadcast-elements!
              [:div#chat-messages
               [:p "New message from AI"]]
              {:d*/selector "#chat-messages"
               :d*/patch-mode :d*/pm-append}]])

  (send-ui! "44fd9979-5599-4fe7-9bfc-c98f30495fa8"
            [[:broadcast-elements!
              [:div#chat-messages
               [:p "New message from AI"]]
              {:d*/selector "#chat-messages"
               :d*/patch-mode :d*/pm-append}]])

  nil)

;;; Response type detection

(defn- component-responses?
  "Returns true if the component returned a list of effects (like chat_simulator2)
   rather than plain hiccup. Effects are a seq of vectors: [[:effect-name ...] ...]."
  [html]
  (and (vector? html) (vector? (first html))))

(defn- send-html-to-sse!
  "Sends pre-computed HTML to a single sse-gen.
   If the html is component-responses (effects), dispatches via broadcast-replies!.
   If plain hiccup, sends directly via d*/patch-elements!."
  [{:keys [sse-gen html]}]
  (if (component-responses? html)
    (broadcast-replies! html [sse-gen])
    (d*/patch-elements! sse-gen (h/html (hc/compile html)) {})))

;;; SSE connect handler

(defn handler-connected-component-connect [req respond _raise]
  (let [component (keyword (get-in req [:path-params :component]))
        component-id (get-in req [:path-params :component-id])
        event-name (get-in req [:path-params :event])
        logged-user (get-in req [:params :logged-user])]
    (respond
     (->sse-response
      req
      {on-open
       (fn [sse-gen]
         (log/trace
          ::connection-opened
          [:component component
           :component-id component-id
           :event event-name]

          (register-connection! component component-id sse-gen logged-user))

         (-> req
             (assoc :sse-gen sse-gen)
             (pipeline/process-component-event component component-id event-name nil)
             (assoc :sse-gen sse-gen)
             (send-html-to-sse!)))

       on-close
       (fn [sse-gen]
         (log/trace
          ::connection-closed
          [:component component
           :component-id component-id]
          (unregister-connection! component component-id sse-gen logged-user)))}))))

(defonce ui-loop-started
  (Thread/startVirtualThread
   (fn []
     (loop []
       (when-let [msg (<!! !ui-chan)]
         (try
           (handle-ui-event msg)
           (catch Exception e
             (log/trace ::ui-event-error [:error (.getMessage e)] nil)))
         (recur))))))
