(ns agents.use-cases.chat.todomvc-agent
  "TodoMVC agent - holds the shared in-memory database atom and
   an LLM-powered chat agent that can add, update, toggle, and delete todos."
  (:require
   [agents.use-cases.chat.agent-builder :as agent-builder]
   [logging.interface :as log]
   [cheshire.core :as json]))

;; Database simulation using an atom.
;; Structure: {:todos [{:id "..." :text "..." :completed false :user-id "Dan"} ...]
;;             :chats [{:id "..." :created-at ... :messages [...] :user-id "Dan"} ...]}
;; Each item carries its own :user-id; read functions filter by user-id.
(def !db (atom {:todos [] :chats []}))

(comment

  @!db

  nil)

;; ---------------------------------------------------------------------------
;; Todo storage functions
;; ---------------------------------------------------------------------------

(defn read-todos
  "Reads todos from atom for a user-id.
   Filters the flat :todos vector by :user-id.
   Always returns a vector, never nil."
  [user-id]
  (filterv #(= (:user-id %) user-id) (:todos @!db)))

(defn write-todos
  "Writes todos to atom for a user-id.
   Removes all existing todos for this user-id, then adds the new ones
   with :user-id stamped on each."
  [user-id todos]
  (swap! !db update :todos
         (fn [all-todos]
           (into (filterv #(not= (:user-id %) user-id) all-todos)
                 (map #(assoc % :user-id user-id) todos)))))

;; ---------------------------------------------------------------------------
;; Chat storage functions
;; ---------------------------------------------------------------------------

(defn read-chats
  "Reads chats from atom for a user-id.
   Filters the flat :chats vector by :user-id.
   Always returns a vector, never nil."
  [user-id]
  (filterv #(= (:user-id %) user-id) (:chats @!db)))

(defn write-chats
  "Writes chats to atom for a user-id.
   Removes all existing chats for this user-id, then adds the new ones
   with :user-id stamped on each."
  [user-id chats]
  (swap! !db update :chats
         (fn [all-chats]
           (into (filterv #(not= (:user-id %) user-id) all-chats)
                 (map #(assoc % :user-id user-id) chats)))))

(defn read-chat
  "Reads a specific chat by id for a user-id.
   Returns the chat map if found, nil otherwise."
  [user-id chat-id]
  (let [chats (read-chats user-id)]
    (first (filter #(= (str (:id %)) (str chat-id)) chats))))

(defn delete-chat
  "Removes a chat by id for a user-id.
   Returns the updated chats vector."
  [user-id chat-id]
  (let [chats (read-chats user-id)
        updated-chats (into [] (remove #(= (str (:id %)) (str chat-id))) chats)]
    (write-chats user-id updated-chats)
    updated-chats))

;; ---------------------------------------------------------------------------
;; Chat message functions (for agent-builder load/add contract)
;; ---------------------------------------------------------------------------

(defn load-chat-messages
  "Loads messages for a chat-id from !db's :chats vector.
   Finds the chat entry by :id and returns its :messages vector (or [])."
  [chat-id]
  (let [chat (first (filter #(= (str (:id %)) (str chat-id)) (:chats @!db)))]
    (or (:messages chat) [])))

(defn add-chat-message
  "Adds a message to a chat-id in !db's :chats vector.
   If no chat with that id exists yet, creates one.
   Message is appended to the chat's :messages vector."
  [chat-id msg]
  (swap! !db update :chats
         (fn [chats]
           (if (some #(= (str (:id %)) (str chat-id)) chats)
             (mapv (fn [chat]
                     (if (= (str (:id chat)) (str chat-id))
                       (update chat :messages (fnil conj []) msg)
                       chat))
                   chats)
             (conj (or chats [])
                   {:id (str chat-id)
                    :messages [msg]})))))

;; ============================================================================
;; Agent: Tool definitions, system prompt, execute-tools, flow
;; ============================================================================

;; ---------------------------------------------------------------------------
;; Tool definitions (OpenAI function-calling format)
;; ---------------------------------------------------------------------------

(defn- read-todos-tool []
  {:name "read_todos"
   :description "Read all todo items. Returns a list of todos with their id, text, and completed status."
   :parameters {:type "object"
                :properties {}
                :required []}})

(defn- update-todos-tool []
  {:name "update_todos"
   :description "Add, update, or delete todo items. Supports batch operations."
   :parameters {:type "object"
                :properties {:operations {:type "array"
                                          :items {:type "object"
                                                  :properties {:action {:type "string"
                                                                        :enum ["add" "update" "delete"]
                                                                        :description "The action to perform: 'add' to create a new todo, 'update' to modify an existing todo, 'delete' to remove a todo."}
                                                               :id {:type "string"
                                                                    :description "The todo id (required for 'update' and 'delete'; ignored for 'add'). Obtain this by calling read_todos first."}
                                                               :text {:type "string"
                                                                      :description "The todo text (required for 'add'; optional for 'update')."}
                                                               :completed {:type "boolean"
                                                                           :description "Whether the todo is completed. Defaults to false for 'add'. Use this to mark a todo as done (true) or undone (false)."}}
                                                  :required ["action"]}
                                          :description "List of operations to perform on todos."}}
                :required ["operations"]}})

(def ^:private tools-vec
  [(read-todos-tool) (update-todos-tool)])

;; ---------------------------------------------------------------------------
;; System prompt
;; ---------------------------------------------------------------------------

(def ^:private system-instructions
  "YOU ARE a todo list management assistant whose goal is to help users manage their todo items.

LANGUAGE:
- ALWAYS respond in English.

SCOPE (VERY IMPORTANT):
- ONLY handle requests related to todo items: read, add, update (including marking as done/undone), and delete.
- IF the request is NOT about todos (for example: general questions, chitchat), you MUST reject politely:
  'I can help you manage your todo list. Tell me what you need to read, add, update, or delete.'
- DO NOT invent data. If essential information is missing, ask for it.

CONTEXT:
- Users will NEVER know the id values — they will refer to todos by their TEXT.
- Each todo has three fields:
  - id: a unique identifier string (never shown to users)
  - text: the description of the todo
  - completed: boolean (true = done, false = not done)

CRITICAL WORKFLOW FOR UPDATE/DELETE:
- The user will provide the todo TEXT, NOT an id.
- For update or delete operations:
  1) First call 'read_todos' to fetch the current list.
  2) Match the user's description to find the correct 'id'.
  3) If multiple todos match or the reference is ambiguous, ask the user to clarify which one they mean.
  4) Only AFTER you have the correct 'id', call 'update_todos' with the appropriate operation.

TOOLS:

- Use 'read_todos' to list all todos.
  - Parameters: {} (no arguments)

- Use 'update_todos' to add, update, or delete todos.
  - The tool always receives:
    {
      'operations': [ OPERATION, OPERATION, ... ]
    }
  - Each OPERATION object has:
    - 'action': 'add' | 'update' | 'delete'
    - 'id': string (required for 'update' and 'delete'; ignored for 'add')
    - 'text': string (required for 'add'; optional for 'update')
    - 'completed': boolean (optional; defaults to false for 'add')

INTERACTION RULES:
1) If the user asks to read, add, update, toggle, or delete todos, you MUST call the appropriate tool.
2) When using tools, respond with ONLY the tool call and its JSON payload. Do NOT include natural language in the same turn.
3) When information is missing or ambiguous, ask ONE clarification question WITHOUT calling tools.
4) You may batch multiple operations in a single 'update_todos' call.
5) If the request is out of scope, answer WITHOUT tools:
   'I can help you manage your todo list. Tell me what you need to read, add, update, or delete.'

EXPECTED FORMAT:
- Tool call → JSON-only payload for the tool.
- Clarification or refusal → natural language only, no tool calls.

================================================================
DETAILED EXAMPLES
================================================================

Example 1 — Read todos
User: 'Show me my todos'
Assistant action:
- Call 'read_todos' with:
  {}

Example 2 — Add a todo directly
User: 'Add buy milk'
Assistant action:
- Call 'update_todos' with:
  {
    'operations': [
      {
        'action': 'add',
        'text': 'buy milk',
        'completed': false
      }
    ]
  }

Example 3 — Add multiple todos
User: 'Add buy milk and walk the dog'
Assistant action:
- Call 'update_todos' with:
  {
    'operations': [
      {
        'action': 'add',
        'text': 'buy milk',
        'completed': false
      },
      {
        'action': 'add',
        'text': 'walk the dog',
        'completed': false
      }
    ]
  }

Example 4 — Mark a todo as done (two-step, requires id)
User: 'Mark buy milk as done'
Step 1:
- Call 'read_todos' with:
  {}
- Use the result to find the todo where text matches 'buy milk'
  and retrieve its 'id', for example 'a1b2c3d4'.

Step 2:
- Call 'update_todos' with:
  {
    'operations': [
      {
        'action': 'update',
        'id': 'a1b2c3d4',
        'completed': true
      }
    ]
  }

Example 5 — Mark a todo as not done (two-step)
User: 'Mark buy milk as not done'
Step 1:
- Call 'read_todos' with:
  {}
- Find the matching todo and its 'id'.

Step 2:
- Call 'update_todos' with:
  {
    'operations': [
      {
        'action': 'update',
        'id': 'a1b2c3d4',
        'completed': false
      }
    ]
  }

Example 6 — Update todo text (two-step)
User: 'Change buy milk to buy oat milk'
Step 1:
- Call 'read_todos' with:
  {}
- Find the todo with text 'buy milk' and get its 'id'.

Step 2:
- Call 'update_todos' with:
  {
    'operations': [
      {
        'action': 'update',
        'id': 'a1b2c3d4',
        'text': 'buy oat milk'
      }
    ]
  }

Example 7 — Delete a todo (two-step)
User: 'Delete buy milk'
Step 1:
- Call 'read_todos' with:
  {}
- Find the matching todo and its 'id'.

Step 2:
- Call 'update_todos' with:
  {
    'operations': [
      {
        'action': 'delete',
        'id': 'a1b2c3d4'
      }
    ]
  }

Example 8 — Ambiguous todo (clarification)
User: 'Mark buy as done'
Assume 'read_todos' result contains:
  - 'buy milk'
  - 'buy bread'
Action:
- Do NOT call update yet.
- Ask for clarification:
  'I found multiple todos matching \"buy\": \"buy milk\" and \"buy bread\". Which one should I mark as done?'

Example 9 — Out of scope
User: 'What is the weather today?'
Assistant action:
- Do NOT call any tools.
- Respond:
  'I can help you manage your todo list. Tell me what you need to read, add, update, or delete.'
")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- get-arg [m k]
  (or (get m k) (get m (name k))))

(defn- string-keys->keyword-keys
  "Recursively convert string keys to keyword keys in nested data structures"
  [data]
  (cond
    (map? data)
    (into {} (map (fn [[k v]]
                    [(if (string? k) (keyword k) k)
                     (string-keys->keyword-keys v)])
                  data))

    (sequential? data)
    (mapv string-keys->keyword-keys data)

    :else
    data))

(defn- valid-operation? [op]
  (and (map? op)
       (contains? #{"add" "update" "delete"} (get-arg op :action))
       (or (= "add" (get-arg op :action))
           (string? (get-arg op :id)))))

;; ---------------------------------------------------------------------------
;; Todo operations on the atom
;; ---------------------------------------------------------------------------

(defn- apply-todo-operations
  "Apply a list of operations to the todos for a user-id.
   Returns the updated todos vector."
  [user-id operations]
  (let [current-todos (read-todos user-id)
        updated-todos
        (reduce
         (fn [todos op]
           (let [action (get-arg op :action)
                 id (get-arg op :id)
                 text (get-arg op :text)
                 completed (get-arg op :completed)]
             (case action
               "add"
               (conj todos {:id (str (random-uuid))
                            :text (or text "")
                            :completed (if (some? completed) completed false)})

               "update"
               (if-not id
                 todos
                 (mapv (fn [todo]
                         (if (= (str (:id todo)) (str id))
                           (cond-> todo
                             (some? text) (assoc :text text)
                             (some? completed) (assoc :completed completed))
                           todo))
                       todos))

               "delete"
               (if-not id
                 todos
                 (into [] (remove #(= (str (:id %)) (str id))) todos))

               ;; Unknown action - skip
               todos)))
         current-todos
         operations)]
    (write-todos user-id updated-todos)
    updated-todos))

;; ---------------------------------------------------------------------------
;; Execute tools
;; ---------------------------------------------------------------------------

(defn- execute-tools
  "Execute tool calls and return the appropriate messages"
  [{:keys [event-trace tool-calls]}]
  (let [user-id (get-in event-trace [:user :user/id])]
    (->> tool-calls
         (mapv (fn [tool-call]
                 (let [{:keys [id name arguments]} tool-call
                       arguments (string-keys->keyword-keys arguments)]
                   (case name
                     "read_todos"
                     (log/trace
                      ::read-todos
                      [:tool-call tool-call]
                      (let [todos (read-todos user-id)]
                        {:tool-call-msg {:role "tool_call"
                                         :content (select-keys tool-call [:id :name :arguments])}
                         :tool-call-output-msg {:role "tool_call_output"
                                                :content {:id id
                                                          :name name
                                                          :output {:error false
                                                                   :contents [{:type :text
                                                                               :text (json/generate-string {:todos todos})}]}}}
                         :result todos}))

                     "update_todos"
                     (log/trace
                      ::update-todos
                      [:tool-call tool-call]
                      (let [operations (get-arg arguments :operations)]
                        (if-not (and (sequential? operations)
                                     (every? valid-operation? operations))
                          {:error "Invalid operations format. Each operation must have an 'action' (add/update/delete) and an 'id' for update/delete."}
                          (let [updated-todos (apply-todo-operations user-id operations)]
                            {:tool-call-msg {:role "tool_call"
                                             :content {:id id
                                                       :name name
                                                       :arguments {:operations operations}}}
                             :tool-call-output-msg {:role "tool_call_output"
                                                    :content {:id id
                                                              :name name
                                                              :output {:error false
                                                                       :contents [{:type :text
                                                                                   :text (json/generate-string {:success true
                                                                                                                :todos updated-todos})}]}}}
                             :result updated-todos
                             :todos-updated true}))))

                     ;; Unknown tool
                     {:error (str "Unknown tool: " name)})))))))

;; ---------------------------------------------------------------------------
;; Flow creation (delegates to agent-builder)
;; ---------------------------------------------------------------------------

(defn create-and-start-flow!
  "Creates and starts a TodoMVC chat agent flow.
   Delegates to agent-builder with this agent's system-instructions,
   tool definitions, and execute-tools logic.
   Chat messages are stored in !db (no external chat store needed).
   
   Config keys:
   - :in-chan, :out-chan           -- external channels"
  [config]
  (agent-builder/create-and-start!
   (merge config
          {:load-chat-messages-fn load-chat-messages
           :add-chat-message-fn add-chat-message
           :system-instructions-fn (constantly system-instructions)
           :tool-descriptions-fn (constantly tools-vec)
           :execute-tools-fn (fn [event-trace tool-calls _locale]
                               (execute-tools {:event-trace event-trace
                                               :tool-calls tool-calls}))})))

;; ---------------------------------------------------------------------------
;; REPL examples
;; ---------------------------------------------------------------------------

(comment

  ;; Seed some initial todos for testing
  (write-todos "Dan" [{:id "1" :text "buy milk" :completed false}
                      {:id "2" :text "walk the dog" :completed false}
                      {:id "3" :text "clean the house" :completed true}])
  (read-todos "Dan")

  ;; Create the flow with external channels
  (require '[clojure.core.async :as a])
  (def in-chan (a/chan 100))
  (def out-chan (a/chan 100))
  (def f (create-and-start-flow! {:in-chan in-chan :out-chan out-chan}))

  ;; Listen to output events from out-chan
  (a/go-loop []
    (when-let [[event-name event-payload] (a/<! out-chan)]
      (clojure.pprint/pprint ["\n" event-name
                              "\n" (dissoc event-payload :mulog/ctx)])
      (recur)))

  ;; User connects to a chat
  (log/trace
   ::user-connected
   []
   (a/>!! in-chan [:agents.domain.chat/user-connected
                   {:id "01"
                    :mulog/ctx (log/local-context)
                    :event-trace {:ai-chat/id "demo-chat-1"
                                  :user {:user/id "Dan" :user/language :en}}}]))

  ;; 1. Read all todos
  (log/trace
   ::send-user-msg
   []
   (a/>!! in-chan [:agents.domain.chat/user-message-sent
                   {:id "02"
                    :mulog/ctx (log/local-context)
                    :content "Show me all my todos"
                    :event-trace {:ai-chat/id "demo-chat-1"
                                  :user {:user/id "Dan" :user/language :en}}}]))

  ;; 2. Add a new todo
  (log/trace
   ::send-user-msg
   []
   (a/>!! in-chan [:agents.domain.chat/user-message-sent
                   {:id "03"
                    :mulog/ctx (log/local-context)
                    :content "Add buy groceries"
                    :event-trace {:ai-chat/id "demo-chat-1"
                                  :user {:user/id "Dan" :user/language :en}}}]))

  ;; 3. Mark a todo as done
  (log/trace
   ::send-user-msg
   []
   (a/>!! in-chan [:agents.domain.chat/user-message-sent
                   {:id "04"
                    :mulog/ctx (log/local-context)
                    :content "Mark buy milk as done"
                    :event-trace {:ai-chat/id "demo-chat-1"
                                  :user {:user/id "Dan" :user/language :en}}}]))

  ;; 4. Mark a todo as not done
  (log/trace
   ::send-user-msg
   []
   (a/>!! in-chan [:agents.domain.chat/user-message-sent
                   {:id "05"
                    :mulog/ctx (log/local-context)
                    :content "Mark clean the house as not done"
                    :event-trace {:ai-chat/id "demo-chat-1"
                                  :user {:user/id "Dan" :user/language :en}}}]))

  ;; 5. Delete a todo by text
  (log/trace
   ::send-user-msg
   []
   (a/>!! in-chan [:agents.domain.chat/user-message-sent
                   {:id "06"
                    :mulog/ctx (log/local-context)
                    :content "Delete walk the dog"
                    :event-trace {:ai-chat/id "demo-chat-1"
                                  :user {:user/id "Dan" :user/language :en}}}]))

  ;; 6. Update todo text
  (log/trace
   ::send-user-msg
   []
   (a/>!! in-chan [:agents.domain.chat/user-message-sent
                   {:id "07"
                    :mulog/ctx (log/local-context)
                    :content "Change buy milk to buy oat milk"
                    :event-trace {:ai-chat/id "demo-chat-1"
                                  :user {:user/id "Dan" :user/language :en}}}]))

  ;; Inspect the current state of the atom
  (read-todos "Dan")

  ;; Inspect the flow's tools proc state
  (-> (flow/ping f)
      :tools
      :clojure.core.async.flow/state
      :chats
      (get "demo-chat-1"))

  ;; Pause/stop the flow
  (flow/pause f)
  (flow/stop f)

  nil)
