(ns ds.adapters.components.expandable
  (:require
   [charred.api :as charred]))

;; Expandable component with datastar signals
(defn expandable
  ([title content]
   (expandable title {:id (str "expandable-" (random-uuid))} content))
  ([title opts content]
   (let [expandable-id (or (:id opts) (str "expandable-" (random-uuid)))
         is-link? (true? (:is-link? opts))
         signal-name (str "expanded_" expandable-id)]
     [:div.expandable-container.cl-12.cs-12
      {:data-signals (charred/write-json-str {signal-name false})}
     ;; Clickable header with icon
      [(if is-link?
         :a.expandable-header
         :div.secondary.expandable-header)
       {:style {:cursor "pointer"
                :display "flex"
                :align-items "center"
                :gap "8px"}
        :data-on-click (str "$['" signal-name "'] = !$['" signal-name "']")}
       (when title title)
       [:i.material-symbols-outlined.expand-icon
        {:style {:vertical-align "middle"
                 :transition "transform 0.2s ease"
                 :transform (str "$['" signal-name "'] ? 'rotate(180deg)' : 'rotate(0deg)'")}
         :data-text (str "$['" signal-name "'] ? 'expand_less' : 'expand_more'")}
        "expand_more"]]
     ;; Expandable content - always present but controlled by signal
      [:div.expandable-content
       {:data-show (str "$['" signal-name "']")
        :style {:overflow "hidden"
                :transition "max-height 0.3s ease, opacity 0.3s ease"}}
       content]])))
