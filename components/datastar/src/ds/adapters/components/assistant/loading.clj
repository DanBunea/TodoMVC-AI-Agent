(ns ds.adapters.components.assistant.loading
  "The \"Preparing next steps\" indicator shown while the agent works, and the
   taia.ds effects that show and hide it on the chat's SSE stream.")

(defn loading-container [chat-id]
  [:div.cs-12.cl-12.ac
   {:id (str "loading-container-" chat-id)
    :style {:order 9999}}]) ;; CSS order ensures it's always last in flex container

(defn- shimmer-effect [text]
  (map-indexed
   (fn [idx char]
     [:span.shimmer-effect
      {:key idx
       :style {:animation-delay (str (* idx 0.05) "s")}}
      (if (= char \space) " " char)])
   text))

;; Loading content - the spinner and text shown when loading
(defn loading-content []
  [:div
   {:style {:display "flex"
            :align-items "center"}}
   [:span.secondary
    (shimmer-effect "Preparing next steps")]])

(defn show-loading
  "Effect: fills the chat's loading container with the indicator (morphed by id)."
  [chat-id]
  [:patch (conj (loading-container chat-id) (loading-content))])

(defn hide-loading
  "Effect: morphs the chat's loading container back to empty."
  [chat-id]
  [:patch (loading-container chat-id)])
