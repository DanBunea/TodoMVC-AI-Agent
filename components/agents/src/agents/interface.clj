(ns agents.interface
  (:require
   [clojure.core.async :as a :refer [chan]]
   [agents.domain.chat :as chat-domain]
   [agents.use-cases.chat.todomvc-agent :as tda]))

(def !internal-agents (atom {}))

(def verbose-trace-event-names chat-domain/verbose-trace-event-names)

(defn initialize-chat-agents! []
  (when-not (:todomvc @!internal-agents)
    (let [!in-chan-todomvc (chan 100)
          !out-chan-todomvc (chan 100)
          flow-todomvc (tda/create-and-start-flow! {:in-chan !in-chan-todomvc
                                                    :out-chan !out-chan-todomvc})]

      (reset! !internal-agents {:todomvc {:!in-chan !in-chan-todomvc
                                          :!out-chan !out-chan-todomvc
                                          :flow flow-todomvc}}))))

(defn agents []
  @!internal-agents)


