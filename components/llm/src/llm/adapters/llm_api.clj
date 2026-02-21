(ns llm.adapters.llm-api
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [llm.adapters.config :as config]
;;    [llm.adapters.llm-providers.anthropic :as llm-providers.anthropic]
;;    [llm.adapters.llm-providers.copilot]
;;    [llm.adapters.llm-providers.ollama :as llm-providers.ollama]
   [llm.adapters.llm-providers.openai :as llm-providers.openai]
;;    [llm.adapters.llm-providers.openai-chat :as llm-providers.openai-chat]
   [logging.interface :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[LLM-API]")

;; TODO ask LLM for the most relevant parts of the path
(defn refine-file-context [path lines-range]
  (cond
    (not (fs/exists? path))
    "File not found"

    (not (fs/readable? path))
    "File not readable"

    :else
    (let [content (slurp path)]
      (if lines-range
        (let [lines (string/split-lines content)
              start (dec (:start lines-range))
              end (min (count lines) (:end lines-range))]
          (string/join "\n" (subvec lines start end)))
        content))))

(defn ^:private provider-api-key [provider provider-auth config]
  (or (get-in config [:providers (name provider) :key])
      (:api-key provider-auth)
      (some-> (get-in config [:providers (name provider) :keyEnv]) config/get-env)))

(defn ^:private provider-api-url [provider config]
  (or (get-in config [:providers (name provider) :url])
      (some-> (get-in config [:providers (name provider) :urlEnv]) config/get-env)))

(defn default-model
  "Returns the default LLM model checking this waterfall:
  - defaultModel set
  - Anthropic api key set
  - Openai api key set
;;   - Github copilot login done
;;   - Ollama first model if running
  - Anthropic default model."
  [db config]
  (let [[decision model]
        (or (when-let [config-default-model (:defaultModel config)]
              [:config-default-model config-default-model])

            (when (provider-api-key "openai" (get-in db [:auth "openai"]) config)
              [:api-key-found "openai/gpt-5"])

            [:default "openai/gpt-5"])]
    (logger/debug logger-tag (format "Default LLM model '%s' decision '%s'" model decision))
    model))

(defn ^:private tool->llm-tool [tool]
  (assoc (select-keys tool [:name :description :parameters])
         :type "function"))

(defn complete!
  [{:keys [provider model model-capabilities instructions user-messages config on-first-response-received
           on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated
           past-messages tools provider-auth]}]
  (let [first-response-received* (atom false)
        emit-first-message-fn (fn [& args]
                                (when-not @first-response-received*
                                  (reset! first-response-received* true)
                                  (apply on-first-response-received args)))
        on-message-received-wrapper (fn [{:keys [status] :as msg}]
                                      (emit-first-message-fn msg)
                                      (logger/trace-at (if (= :delta status) :verbose :info)
                                                       ::on-message-received
                                                       [:status status]
                                                       (on-message-received msg)))
        on-reason-wrapper (fn [{:keys [status] :as msg}]
                            (emit-first-message-fn msg)
                            (logger/trace-at (if (= :thinking status) :verbose :info)
                                             ::on-reason
                                             [:status status]
                                             (on-reason msg)))
        on-prepare-tool-call-wrapper (fn [{:keys [status] :as msg}]
                                       (emit-first-message-fn msg)
                                       (logger/trace-at (if (= :delta status) :verbose :info)
                                                        ::on-prepare-tool-call
                                                        [:status status :name (:name msg)]
                                                        (on-prepare-tool-call msg)))
        on-error-wrapper (fn [{:keys [exception] :as args}]
                           (when-not (:silent? (ex-data exception))
                             (logger/debug :error args)
                             (on-error args)))
        tools (when (:tools model-capabilities)
                (mapv tool->llm-tool tools))
        reason? (:reason? model-capabilities)
        web-search (:web-search model-capabilities)
        max-output-tokens (:max-output-tokens model-capabilities)
        provider-config (get-in config [:providers provider])
        model-config (get-in provider-config [:models model])
        extra-payload (:extraPayload model-config)
        api-key (provider-api-key provider provider-auth config)
        api-url (provider-api-url provider config)
        provider-auth-type (:type provider-auth)
        on-tools-called-wrapper (fn [tool-calls]
                                  (logger/trace-at :info
                                                   ::on-tools-called
                                                   [:tool-count (count tool-calls)]
                                                   (on-tools-called tool-calls)))
        callbacks {:on-message-received on-message-received-wrapper
                   :on-error on-error-wrapper
                   :on-prepare-tool-call on-prepare-tool-call-wrapper
                   :on-tools-called on-tools-called-wrapper
                   :on-reason on-reason-wrapper
                   :on-usage-updated on-usage-updated}]
    (try
      (when-not api-url (throw (ex-info (format "API url not found.\nMake sure you have provider '%s' configured properly." provider) {})))
      (cond
        (= "openai" provider)
        (llm-providers.openai/completion!
         {:model model
          :instructions instructions
          :user-messages user-messages
          :max-output-tokens max-output-tokens
          :reason? reason?
          :past-messages past-messages
          :tools tools
          :web-search web-search
          :extra-payload extra-payload
          :api-url api-url
          :api-key api-key}
         callbacks)

        model-config
        (let [provider-fn (case (:api provider-config)
                            ("openai-responses"
                             "openai") llm-providers.openai/completion!

                            (on-error-wrapper {:message (format "Unknown model %s for provider %s" (:api provider-config) provider)}))
              url-relative-path (:completionUrlRelativePath provider-config)]
          (provider-fn
           {:model model
            :instructions instructions
            :user-messages user-messages
            :max-output-tokens max-output-tokens
            :reason? reason?
            :past-messages past-messages
            :tools tools
            :extra-payload extra-payload
            :url-relative-path url-relative-path
            :api-url api-url
            :api-key api-key}
           callbacks))

        :else
        (on-error-wrapper {:message (format "ECA Unsupported model %s for provider %s" model provider)}))
      (catch Exception e
        (on-error-wrapper {:exception e})))))

