(ns ds.adapters.components.assistant.reasoning
  (:require
   [clojure.string :as str]
   [charred.api :as charred]))

(defn partial-reasoning [{:keys [reply]}]
  (let [parts (str/split reply #"\n")
        ends-with-newline? (str/ends-with? reply "\n")]
    (cond-> (->> parts
                 (map (fn [s] [:span.partial-reasoning (str s " ")]))
                 (interpose [:br])
                 (into []))
      ends-with-newline? (conj [:br]))))

;; Reasoning expandable - expanded by default, collapses on finish
;; Uses signals for: expanded state, start timestamp, status, cached duration
;; start-time-ms is passed from handler (server timestamp)
(defn reasoning-expandable [{:keys [id start-time-ms]}]
  (let [expanded-signal (str "expanded_reasoning_" id)
        start-signal (str "reasoning_start_" id)
        state-signal (str "reasoning_state_" id)
        duration-signal (str "reasoning_duration_" id)
        ;; Title: if finished, use cached duration; otherwise show "Thinking..."
        title-expr (str "$" state-signal " === 'finished' ? 'Thought for ' + $" duration-signal " + ' seconds' : 'Thinking...'")]
    [:div.expandable-container.cl-12.cs-12
     {:id (str "reasoning-expandable-" id)
      ;; Initialize: expanded=true, state="started", start timestamp, duration=0
      :data-signals (charred/write-json-str {expanded-signal true
                                             start-signal start-time-ms
                                             state-signal "started"
                                             duration-signal 0})}
     ;; Clickable header
     [:div.expandable-header.secondary
      {:style {:cursor "pointer"
               :display "flex"
               :align-items "center"
               :gap "8px"}
       :data-on-click (str "$['" expanded-signal "'] = !$['" expanded-signal "']")}
      ;; Dynamic title
      [:span {:data-text title-expr} "Thinking..."]
      [:i.material-symbols-outlined.expand-icon
       {:style {:vertical-align "middle"
                :transition "transform 0.2s ease"}
        :data-text (str "$['" expanded-signal "'] ? 'expand_less' : 'expand_more'")}
       "expand_less"]] ;; Start with expand_less since expanded
     ;; Content area - partial reasoning gets appended here
     [:div.expandable-content.reasoning
      {:id (str "reasoning-" id)
       :data-show (str "$['" expanded-signal "']")
       :style {:overflow "hidden"
               :transition "max-height 0.3s ease, opacity 0.3s ease"}}]]))

(defn partial-reasoning-reply [event-payload]
  (let [{:keys [id reply status event-trace]} event-payload
        chat-id (:ai-chat/id event-trace)
        completion-step (:completion-step event-trace)
        reasoning-id (str id "-" completion-step)]
    (cond
      (= :started status)
      [[:broadcast-hide-loading chat-id]
       [:broadcast-elements!
        (reasoning-expandable {:id reasoning-id
                               :start-time-ms (System/currentTimeMillis)})
        {:d*/selector (str "#messages")
         :d*/patch-mode :d*/pm-append}]]

      (= :thinking status)
      [[:broadcast-elements!
        (partial-reasoning {:reply reply})
        {:d*/selector (str "#reasoning-" reasoning-id)
         :d*/patch-mode :d*/pm-append}]]

      (= :finished status)
      [[:broadcast-loading chat-id]
       [:broadcast-elements!
        [:div {:style {:display "none"}
               :data-on-load (str "$reasoning_duration_" reasoning-id " = Math.round((Date.now() - $reasoning_start_" reasoning-id ") / 1000); "
                                  "$reasoning_state_" reasoning-id " = 'finished'; "
                                  "$expanded_reasoning_" reasoning-id " = false")}]
        {:d*/selector "#messages"
         :d*/patch-mode :d*/pm-append}]]

      :else [])))
