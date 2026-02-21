(ns logging.interface
  (:require
   [clojure.pprint :refer [pprint]]
   #?(:clj [clojure.core :as core])
   #?(:clj [com.brunobonacci.mulog :as u])))

(defn debug [prefix & args]
  (prn prefix args))

(defn log-> [prefix ctx & args]
  (tap> (into [] (concat [prefix ctx] args)))
  #_(prn (into [] (concat [prefix ctx] args)))
  ctx)

(defn log->>  [ctx prefix]
  (tap> [prefix ctx])
  (prn prefix ctx)
  ctx)

(defn dbg
  " (dbg {:a 1 :b 2} 101 #(select-keys % [:a]))
    =>
    ; 101 {:a 1}
    {:a 1, :b 2}
   
   "
  ([ctx prefix]
   (dbg ctx prefix identity))
  ([ctx prefix func]
   (println prefix)
   (pprint (func ctx))
   ctx))

#?(:clj (def levels {:verbose 0 :debug 1 :info 2}))

#?(:clj (def ^:dynamic *min-level* :info))

#?(:clj
   (defmacro with-context
     "Macro wrapper for mulog/with-context.
      Usage: (with-context ctx-map & body)
      Example: (with-context {:mulog/trace-id \"123\"} (trace ...))"
     {:style/indent 1}
     [ctx & body]
     `(u/with-context ~ctx ~@body)))

#?(:clj
   (defmacro with-min-level
     "Temporarily sets the minimum trace level within body.
      Usage: (with-min-level :verbose (trace-at :verbose ...))"
     {:style/indent 1}
     [level & body]
     `(binding [*min-level* ~level]
        ~@body)))

#?(:clj
   (defmacro trace-at
     "Level-aware trace macro. Only emits a mulog/trace when `level` >= `*min-level*`.
      The body is always executed; only the tracing is conditional.
      Levels (ascending): :verbose < :debug < :info
      Usage: (trace-at :verbose event-name [k1 v1 ...] body)"
     {:style/indent 3}
     [level event-name pairs & body]
     `(if (>= (get levels ~level 0) (get levels *min-level* 2))
        (do
          (prn ~event-name ~pairs)
          (u/trace ~event-name ~pairs ~@body))
        (do ~@body))))

#?(:clj
   (defmacro trace
     "Macro wrapper for mulog/trace. Traces at :info level (always emitted at default min-level).
      Usage: (trace event-name [k1 v1 k2 v2 ...] body)
      Example: (trace \"llm/event\" [:in in :event-name :test] (do-something))"
     {:style/indent 2}
     [event-name pairs & body]
     `(trace-at :info ~event-name ~pairs ~@body)))

#?(:clj
   (defmacro debug-trace
     "Trace at :debug level — emitted only when *min-level* is :debug or :verbose.
      Usage: (debug-trace event-name [k1 v1 k2 v2 ...] body)"
     {:style/indent 2}
     [event-name pairs & body]
     `(trace-at :debug ~event-name ~pairs ~@body)))

#?(:clj
   (defn local-context
     "Function wrapper for mulog/local-context.
      Returns the current mulog context map."
     []
     (u/local-context)))

(comment
  (log-> :a {:a 1} 23)
  (log->> {:a 1} :here)

  (-> {:a 1 :b 2}
      (dbg 101 #(select-keys % [:a]))
      ;; (update :a inc)
      ))