(ns ds.adapters.components.todomvc.todomvc-page-controller
  "State machine controller for the TodoMVC page component.
   Minimal state machine - page-level state management only.")

(def machine
  {:id :todomvc-page
   :initial :displayed
   ;; taia.ds drops unhandled events with a warning; the page GET sends INIT
   :states {:displayed {:on {:INIT {}}}}})
