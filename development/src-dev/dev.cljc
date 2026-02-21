(ns dev
  (:require
   [hyperfiddle.rcf]
   #?(:clj [server.server-jetty :as jetty])
   #?(:clj [clojure.tools.logging :as log])
   #?(:clj [com.brunobonacci.mulog :as u])
   #?(:clj [agents.interface :refer [initialize-chat-agents!]])
   #?(:clj [ds.adapters.components.todomvc.todo-chat :as todo-chat])
   #?(:clj [aero.core :refer [read-config]])
   #?(:clj [clojure.java.io :as io])
   #?(:clj [llm.interface :refer [check-openai-api-key!]])
   #?(:clj [malli.dev :as dev])))

(comment
  (-main)

  nil) ; repl entrypoint

#?(:clj ;; Server Entrypoint
   (do
     (def config
       {:host "0.0.0.0"
        :port 7777
        :resources-path "public/app"
        :manifest-path ; contains Electric compiled program's version so client and server stays in sync
        "public/app/js/manifest.edn"})

     ;;rcf/test
     (hyperfiddle.rcf/enable!)

     ;;malli dev
     (dev/start!)

;;u/log 
     (u/set-global-context! {:app-name "db", :version "0.1.0", :env "local"})

     ;; Transform function for zipkin publisher - converts non-primitive values to strings
     ;; while preserving :mulog/ namespaced values
     (defn zipkin-transform [events]
       (map (fn [event]
              (into {}
                    (map (fn [[k v]]
                           (if (or (string? v)
                                   (number? v)
                                   (boolean? v)
                                   (and (keyword? k) (= (namespace k) "mulog")))
                             [k v]
                             [k (str v)]))
                         event)))
            events))

     (def stop-publisher! (u/start-publisher!
                           {:type :multi
                            :publishers [#_{:type :custom
                                            :pretty? true
                                            :fqn-function ->publisher}
                                         #_{:type :console
                                            :pretty? true
                                            :transform (fn [events] (map
                                                                     #(dissoc %
                                                                              :mulog/parent-trace
                                                                              :mulog/namespace
                                                                              :env
                                                                              :version
                                                                              :app-name
                                                                              :mulog/root-trace
                                                                              :mulog/trace-id
                                                                              :mulog/timestamp)
                                                                     events))}
                                         {:type :zipkin
                                          :url "http://localhost:9411"
                                          :transform zipkin-transform}]}))

     (comment
       (stop-publisher!))

     (defn -main [& args]
       (check-openai-api-key!)

       (log/info "Initialize agents")
       (initialize-chat-agents!)

       (log/info "Initialized agents")

       (log/info "Initialize out listeners")
       (todo-chat/intialize-out-listener!)
       (log/info "Initialized out listeners")

       (def server (jetty/start-server!
                    (fn [ring-request] #_(e/boot-server {} ui.core/Screen ring-request)
                      ring-request)
                    (assoc config :env :dev :async? true)))

       (comment (.stop server)))))