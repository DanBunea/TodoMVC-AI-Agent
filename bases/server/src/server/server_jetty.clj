(ns server.server-jetty
  "Electric integrated into a sample ring + jetty app."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [muuntaja.core :as m]
   [reitit.ring.middleware.muuntaja :as rrmm]
   [reitit.ring :as reitit]

   [ring.adapter.jetty :as ring]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.cookies :as cookies]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [reitit.ring.middleware.parameters :as rmparams]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.memory :as mem]
  ;;  [auth-middleware.interface :refer [wrap-auth0-authentication]]
   [com.brunobonacci.mulog :as u]
   [ring.util.response :as res]
   [ds.interface :refer [handler-component-event
                         handler-connected-component-connect]])
  (:import
   (org.eclipse.jetty.server.handler.gzip GzipHandler)
   ;; NOTE: Jetty 11+ has different websocket config classes
   #_(org.eclipse.jetty.websocket.server.config JettyWebSocketServletContainerInitializer JettyWebSocketServletContainerInitializer$Configurator)))

;; Auth
(def !session-store (atom {}))
(def wrap-session-options {:store (mem/memory-store !session-store)})

;;; Electric integration

 ; 1. parse query params

(defn get-modules [manifest-path]
  (when-let [manifest (io/resource manifest-path)]
    (let [manifest-folder (when-let [folder-name (second (rseq (str/split manifest-path #"\/")))]
                            (str "/" folder-name "/"))]
      (->> (slurp manifest)
           (edn/read-string)
           (reduce (fn [r module] (assoc r (keyword "hyperfiddle.client.module" (name (:name module)))
                                         (str manifest-folder (:output-name module)))) {})))))

(defn template
  "In string template `<div>$:foo/bar$</div>`, replace all instances of $key$
with target specified by map `m`. Target values are coerced to string with `str`.
  E.g. (template \"<div>$:foo$</div>\" {:foo 1}) => \"<div>1</div>\" - 1 is coerced to string."
  [t m] (reduce-kv (fn [acc k v] (str/replace acc (str "$" k "$") (str v))) t m))

;;; Template and serve index.html

(defn wrap-head-root
  [handler]
  (fn wrap-head-root
    ([request]

     (if (and (= :head (:request-method request))
              (= "/" (:uri request)))
       {:status 200
        :headers {}
        :body nil}
       (handler request)))
    ([request respond raise]

     (if (and (= :head (:request-method request))
              (= "/" (:uri request)))
       (respond {:status 200
                 :headers {}
                 :body nil})
       (handler request respond raise)))))

;; User middleware
(defn wrap-logged-user
  "Middleware that extracts the logged user from session and adds it to request params"
  [handler]
  (fn wrap-logged-user
    ([request]

     (let [session-id (get-in request [:cookies "ring-session" :value])
           session-data (when session-id (get @!session-store session-id))
           user-info (get-in session-data [:user-info])
           language (get-in request [:query-params "language"])
           logged-user (when user-info
                         {:user/id (get user-info :sub)
                          :user/name (get user-info :given_name)
                          :user/groups (get user-info :groups)
                          :user/email (get user-info :email)
                          :user/language (if (= "en" language) :en
                                             :fr)
                          :logout-url (:logout-url user-info)})]
       (u/trace
        (str (str/upper-case (name (:request-method request))) " " (get request :uri))
        [:logged-user-id (str (:user/id logged-user))]
        (handler (assoc-in request [:params :logged-user] logged-user)))))

    ([request respond raise]

     (let [session-id (get-in request [:cookies "ring-session" :value])
           session-data (when session-id (get @!session-store session-id))
           user-info (get-in session-data [:user-info])
           language (get-in request [:query-params "language"])
           logged-user (when user-info
                         {:user/id (get user-info :sub)
                          :user/name (get user-info :given_name)
                          :user/groups (get user-info :groups)
                          :user/email (get user-info :email)
                          :user/language (if (= "en" language) :en
                                             :fr)
                          :logout-url (:logout-url user-info)})]
       (u/trace
        (str (str/upper-case (name (:request-method request))) " " (get request :uri))
        [:logged-user-id (str (:user/id logged-user))]
        (handler (assoc-in request [:params :logged-user] logged-user) respond raise))))))

(defn app-routes [config]
  (reitit/ring-handler
   (reitit/router
    [["/api"
      ["/ping"
       {:get {:summary "Ping"
              :handler (fn [_] {:status 200 :body {:ping :pong}})}}]]
     ["/ds"
      {:middleware [wrap-keyword-params
                    rmparams/parameters-middleware
                    wrap-logged-user
                    #(wrap-session % wrap-session-options)
                    ;; #(wrap-auth0-authentication % (:keycloak config))
                    ]}
      ["/nds"
       ["/:component/:component-id/:controller/:event" {:handler #'handler-component-event}]
       ["/:component/:component-id/:event" {:handler #'handler-component-event}]]
      ["/nds-connect"
       ["/:component/:component-id/:event" {:handler #'handler-connected-component-connect}]]]]
    {:data {:muuntaja   m/instance
            :middleware [rrmm/format-middleware]}})
   (reitit/routes
    (-> (reitit/create-default-handler
         {:not-found
          (constantly {:status 404, :body "Not found"})})
        (wrap-head-root)
        ;; (wrap-auth0-authentication (:env config)) ; 4. auth 
        (wrap-session wrap-session-options) ; 3. session
        (wrap-resource (:resources-path config))
        (wrap-content-type)))))

(defn middleware [config entrypoint]
  (-> (app-routes config)
      (wrap-content-type)
      ;; (electric-websocket-middleware config entrypoint)
      ))

(defn- add-gzip-handler!
  "Makes Jetty server compress responses. Optional but recommended."
  [server]
  (.setHandler server
               (doto (GzipHandler.)
                 #_(.setIncludedMimeTypes (into-array ["text/css" "text/plain" "text/javascript" "application/javascript" "application/json" "image/svg+xml"])) ; only compress these
                 (.setMinGzipSize 1024)
                 (.setHandler (.getHandler server)))))

(defn start-server! [entrypoint
                     {:keys [port host]
                      :or   {port 8080, host "0.0.0.0"}
                      :as   config}]
  (let [server     (ring/run-jetty (middleware config entrypoint)
                                   (merge {:port         port
                                           :join?        false
                                           :async?       true
                                           ;; WebSocket configuration for Ring 1.15+
                                           :ws-max-text-size (* 100 1024 1024)    ; 100MB
                                           :ws-max-binary-size (* 100 1024 1024)  ; 100MB
                                           :ws-idle-timeout (* 30 60 1000)        ; 30 minutes in milliseconds
                                           :configurator (fn [server]
                                                           (add-gzip-handler! server))}
                                          config))]
    (log/info "👉" (str "http://" host ":" (-> server (.getConnectors) first (.getPort))))
    server))
