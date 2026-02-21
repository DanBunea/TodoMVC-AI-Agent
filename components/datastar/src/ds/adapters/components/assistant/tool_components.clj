(ns ds.adapters.components.assistant.tool-components
  (:require
   [ds.adapters.components.expandable :as exp]
   [clojure.string :as str]))

(defn partial-tools-div-id [id tool-name]
  (str "partial-tools-" id "-" tool-name))

(defn tools-started [{:keys [id tool-name]}]
  [:div.cl-12.cs-12.tools
   {:id (partial-tools-div-id id tool-name)}
   "Using " tool-name])

(defn partial-tools-reply [{:keys [reply]}]
  (let [parts (str/split reply #"\n")
                      ;; starts-with-newline? (str/starts-with? "\n")
        ends-with-newline? (str/ends-with? reply "\n")]
    (cond-> (->> parts
                 (map (fn [s] [:span.partial-tools (str s " ")]))
                 (interpose [:br])
                 (into []))
      ends-with-newline? (conj [:br]))))

(defn tools-called [{:keys [id content]}]
  [:div.cl-12.cs-12
   {:id (partial-tools-div-id id (:name content)) #_(str "partial-tools-" id)}
   (exp/expandable
    (str #_role #_": " (:name content))
    [:div.cs-12.cl-12
     {:id (str "tool-" id)}
     #_[:span {:style {:font-weight "600"}} (str role ": ")]
     [:pre.secondary.tool-call-payload
      {:id (str "tool-call-" id)
       :style {:font-size "var(--typography-small-8-regular-font-size)"
               :white-space "pre-wrap"}}
      (with-out-str (clojure.pprint/pprint (select-keys content [:name :arguments :result])))]])])

(defn message-tool-call [id content]
  [:broadcast-elements!
   (tools-called {:id id :content content})
   {:d*/selector "#messages"
    :d*/patch-mode :d*/pm-append}])

(defn partial-llm-tools-reply [event-payload]
  (let [{:keys [id reply status tool-name event-trace]} event-payload
        chat-id (:ai-chat/id event-trace)]
    (cond
      (= :started status)
      [[:broadcast-hide-loading chat-id]
       [:broadcast-elements!
        (tools-started {:id id
                        :tool-name tool-name})
        {:d*/selector (str "#messages")
         :d*/patch-mode :d*/pm-append}]]

      (= :delta status)
      [[:broadcast-elements!
        (partial-tools-reply {:reply reply})
        {:d*/selector (str "#" (partial-tools-div-id id tool-name))

         :d*/patch-mode :d*/pm-append}]]

      :else [])))

(defn llm-tools-reply [event-payload]
  [])

(defn tool-applied [event-payload]
  (let [{:keys [id tool-call-msg tool-call-output-msg result]} event-payload]
    [[:broadcast-elements!
      (tools-called {:id id
                     :content {:name (-> tool-call-msg
                                         :content
                                         :name)
                               :arguments (-> tool-call-msg
                                              :content
                                              :arguments)
                               :result result}})]]))

