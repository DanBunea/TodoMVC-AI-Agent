(ns ds.adapters.components.todomvc.todomvc-page-controller
  "State machine controller for the TodoMVC page component.
   Minimal state machine - page-level state management only.")

(def machine
  {:id :todomvc-page
   :initial :displayed
   :states {:displayed {}}})
