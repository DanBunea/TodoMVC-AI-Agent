(ns ds.adapters.handlers.discovery
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]))

(defn- try-require-ns
  "Tries to require a namespace, returns the namespace symbol if successful, nil otherwise."
  [ns-sym]
  (try
    (require ns-sym)
    (the-ns ns-sym)
    (catch Exception _ nil)))

(defn- find-ns-in-components
  "Finds a namespace in ds.adapters.components.
   Takes a namespace path like 'counter.counter' or 'counter.counter-page'.
   Returns the namespace symbol if found, nil otherwise."
  [ns-path]
  (let [base-ns "ds.adapters.components"
        ns-str (str base-ns "." ns-path)
        ns-sym (symbol ns-str)]
    (when-let [_ (try-require-ns ns-sym)]
      ns-sym)))

(defn- find-component-ns
  "Finds the component namespace.
   Takes a namespace path like 'counter.counter' or 'counter.counter-page'."
  [ns-path]
  (find-ns-in-components ns-path))

(defn- find-controller-ns
  "Finds the controller namespace.
   Takes a namespace path like 'counter.counter' or 'counter.counter-page'
   and optionally a controller-name (which should already include '-controller' suffix).
   If controller-name is provided, uses it; otherwise appends '-controller' to the last segment.
   Controller-name can be either:
   - Just the last segment (e.g., 'counter-page-controller') - will be combined with parent parts
   - Full path (e.g., 'counter.counter-page-controller') - will be used as-is"
  [ns-path controller-name]
  (let [path-parts (str/split ns-path #"\.")
        parent-parts (butlast path-parts)
        controller-name-to-use (or controller-name
                                   (str (last path-parts) "-controller"))
        ;; If controller-name contains dots, it's a full path - use as-is
        ;; Otherwise, combine with parent parts if they exist
        controller-path (if (str/includes? controller-name-to-use ".")
                          controller-name-to-use
                          (if (seq parent-parts)
                            (str (str/join "." parent-parts) "." controller-name-to-use)
                            controller-name-to-use))]
    (find-ns-in-components controller-path)))

(defn- get-machine-var
  "Gets the machine var from a controller namespace.
   The machine is always at {controller-ns}/machine"
  [controller-ns]
  (when controller-ns
    (try
      (require controller-ns)
      (let [machine-sym (symbol (str controller-ns) "machine")]
        (when-let [machine-var (resolve machine-sym)]
          machine-var))
      (catch Exception _
        nil))))

(defn- get-component-fn-var
  "Gets the main component function from a component namespace.
   Takes the function name (last part of the namespace path)."
  [component-ns fn-name]
  (when component-ns
    (try
      (require component-ns)
      (let [fn-sym (symbol (str component-ns) fn-name)
            fn-var (resolve fn-sym)]
        (when fn-var
          fn-var))
      (catch Exception _
        nil))))

(defn discover-component-in-ds-adapters-component
  "Discovers a component by namespace path.

   Takes a namespace path like:
   - \"counter.counter\" -> looks for ds.adapters.components.counter.counter
   - \"counter.counter-page\" -> looks for ds.adapters.components.counter.counter-page

   Optional controller-name parameter (should include '-controller' suffix):
   - If provided, uses that controller name instead of default
   - If nil, uses default behavior (appends '-controller' to component name)

   Returns map with:
   - :machine - the machine var from controller namespace
   - :component-fn - the component function var
   
   Throws exception if component cannot be found."
  ([ns-path-keyword]
   (discover-component-in-ds-adapters-component ns-path-keyword nil))
  ([ns-path-keyword controller-name]
   (let [ns-path (name ns-path-keyword)
         path-parts (str/split ns-path #"\.")
         fn-name (last path-parts)
         controller-ns (find-controller-ns ns-path controller-name)
         component-ns (find-component-ns ns-path)
         machine-var (get-machine-var controller-ns)
         component-fn-var (get-component-fn-var component-ns fn-name)]
    ;; (when-not controller-ns
    ;;   (throw (ex-info (str "Controller namespace not found for: " ns-path)
    ;;                   {:ns-path ns-path})))
     (when-not machine-var
       (throw (ex-info (str "Machine not found in controller namespace: " controller-ns)
                       {:ns-path ns-path
                        :controller-ns controller-ns})))
     (when-not component-ns
       (throw (ex-info (str "Component namespace not found: " ns-path)
                       {:ns-path ns-path})))
     (when-not component-fn-var
       (throw (ex-info (str "Component function not found in namespace: " component-ns)
                       {:ns-path ns-path
                        :component-ns component-ns})))
     {:machine machine-var
      :component-fn component-fn-var})))

(tests
 ;; Test 1: successful discovery
 (let [result (discover-component-in-ds-adapters-component "counter.counter")
       machine (deref (:machine result))]
   (var? (:machine result)) := true
   (fn? (deref (:component-fn result))) := true)

 ;; Test 2: successful discovery with counter-page
 (let [result (discover-component-in-ds-adapters-component "counter.counter-page")
       machine (deref (:machine result))]
   (var? (:machine result)) := true
   (fn? (deref (:component-fn result))) := true)

 ;; Test 3: throws when namespace not found
 (discover-component-in-ds-adapters-component "nonexistent.component") :throws clojure.lang.ExceptionInfo)

(comment

  (discover-component-in-ds-adapters-component "counter.counter")
  (discover-component-in-ds-adapters-component "counter.counter-page")
  (discover-component-in-ds-adapters-component "counterx"))

