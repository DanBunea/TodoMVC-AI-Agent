
(ns llm.interface
  (:require
   [clojure.string :as string]
   [llm.adapters.llm-api :as llm-api]
   [llm.adapters.config :as config]
   [logging.interface :as log]))

(defn config [] (config/all {}))

(defn complete!
  "Calls the LLM provider. Accepts an optional :trace-level key in ctx (:verbose/:debug/:info)
   which controls which callbacks emit mulog traces. Use log/with-min-level to set the level
   for a scope, or wrap this call with (log/with-min-level :verbose ...) to see all deltas."
  [ctx]
  (llm-api/complete! ctx))

(defmacro with-trace-level
  "Convenience wrapper: sets the minimum trace level for the LLM completion scope.
   Usage: (with-trace-level :verbose (complete! ctx))"
  {:style/indent 1}
  [level & body]
  `(log/with-min-level ~level ~@body))

(defn check-openai-api-key! []
  (let [config (config/all {})
        openai-key (or (get-in config [:providers :openai :key])
                       (some-> (get-in config [:providers :openai :keyEnv])
                               config/get-env)
                       (config/get-env "OPENAI_API_KEY"))]
    (when (string/blank? (str openai-key))
      (throw (ex-info
              (str "OpenAI API key not found. Set the OPENAI_API_KEY environment variable "
                   "or configure the key in your ECA config (e.g. ~/.config/eca/config.json or ECA_CONFIG env).")
              {})))))