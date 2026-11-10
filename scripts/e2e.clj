;; bb e2e-test [spec] [--headed]        UI suite (e2e/tests), no LLM calls
;; bb agent-e2e-test [spec] [--headed]  agent suite (e2e/tests-agent), real OpenAI calls
;;
;; Installs the Playwright deps, starts the app on E2E_PORT (default 7799) in
;; its own JVM so it can run next to `bb dev` on 7777, runs the specs, stops
;; the JVM. Exit code = Playwright's. Server output: e2e/server.log
(ns e2e
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]))

(def port (or (some-> (System/getenv "E2E_PORT") parse-long) 7799))
(def base-url (str "http://localhost:" port))
(def log-file "e2e/server.log") ; not under test-results/: Playwright empties that on start

(def suites
  {:ui    {:config "playwright.config.ts"}
   :agent {:config "playwright-agent.config.ts" :needs-key? true}})

(defn- port-open? []
  (try (with-open [_ (java.net.Socket. "localhost" (int port))] true)
       (catch Exception _ false)))

(defn- wait-for-port! [timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (port-open?) true
        (> (System/currentTimeMillis) deadline) (throw (ex-info (str "server did not open " base-url " — see " log-file) {}))
        :else (do (Thread/sleep 500) (recur))))))

(defn- install! []
  (println "▶ npm install (e2e)")
  (p/shell {:dir "e2e"} (if (fs/exists? "e2e/package-lock.json") "npm ci" "npm install"))
  (println "▶ playwright install chromium")
  (p/shell {:dir "e2e"} "npx playwright install chromium"))

(defn- api-key [] (not-empty (System/getenv "OPENAI_API_KEY")))

(defn- start-server! [{:keys [needs-key?]}]
  (when (port-open?)
    (throw (ex-info (str "port " port " is already in use — stop that server first") {})))
  (when (and needs-key? (not (api-key)))
    (throw (ex-info "the agent suite calls OpenAI: set OPENAI_API_KEY" {})))
  (fs/create-dirs "e2e/test-results")
  (println (str "▶ starting the app on " base-url " (log: " log-file ")"))
  (let [proc (p/process {:out :write :out-file (fs/file log-file) :err :out
                         :extra-env (cond-> {"PORT" (str port)}
                                      ;; the UI suite never reaches the LLM, but startup checks a key exists
                                      (not (api-key)) (assoc "OPENAI_API_KEY" "e2e-ui-suite-no-llm"))}
                        "clojure" "-A:dev" "-X" "dev/-main")]
    (wait-for-port! 240000)
    proc))

(defn- run-specs! [{:keys [config]} spec headed?]
  (println (str "▶ playwright test --config " config (when spec (str " " spec)) (when headed? " --headed")))
  (let [cmd (cond-> ["npx" "playwright" "test" "--config" config]
              spec    (conj spec)
              headed? (conj "--headed"))
        res (p/shell {:dir "e2e" :continue true
                      :extra-env {"E2E_BASE_URL" base-url}}
                     (str/join " " cmd))]
    (:exit res)))

(defn main
  "Entry point for the bb tasks: `suite` is :ui or :agent, `args` the task's
   command-line args ([spec] [--headed])."
  [suite args]
  (let [opts    (suites suite)
        headed? (boolean (some #{"--headed"} args))
        spec    (first (remove #{"--headed"} args))
        _       (install!)
        server  (start-server! opts)
        exit    (try (run-specs! opts spec headed?)
                     (finally
                       (println "▶ stopping the app")
                       (p/destroy-tree server)
                       @server))]
    (println (if (zero? exit) "✅ e2e passed" (str "❌ e2e failed (exit " exit ") — server log: " log-file)))
    (System/exit exit)))
