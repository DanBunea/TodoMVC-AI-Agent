;; Generates .docs/media/agent-flow.svg (a SMIL animation): one chat message,
;; from the browser through the chat's taia.ds flow into the agent's flow,
;; out to the model and back, and the streamed reply pushed down the chat's
;; open SSE stream.
;;
;;   bb agent-flow-gif     ; writes the SVG, then renders .docs/media/agent-flow.gif
;;
;; The picture puts together taia.ds's "connected component" diagram
;; (docs/img/repl-event-flow.svg: the browser, the component's flow) and
;; multi-agent's architecture diagram (the agent's procs, the model outside the
;; flow, callback-chan): where the REPL's send-event! was, the agent's flow is.
;; A message travels the wires step by step: it moves, the box it reaches
;; glows, the caption under the picture explains, next step.
;; Keep the code shown in sync with
;;   components/datastar/src/ds/adapters/components/todomvc/todo_chat_controller.clj
;;   components/datastar/src/ds/adapters/components/todomvc/todo_chat.clj
;;   components/datastar/src/ds/adapters/handlers/agent_output_handler.clj
;;   components/agents/src/agents/use_cases/chat/{agent_builder,procs/*}.clj

(require '[clojure.string :as str])

;;; Palette (the taia.ds diagrams': ramon.css) and fonts

(def PINK "#e92063")
(def BODY-BG "#f8f6f6")
(def TEXT "#111827")
(def MUTED "#707683")
(def ACCENT-TEXT "#8a4a56")
(def ACCENT-BG "#fdeef0")
(def ACCENT-BORDER "#e8b4bc")
(def SESSION-BG "#fdf2f6")
(def BOX-BORDER "#d9d2d5")
(def WIRE "#cfc6c9")
(def SUCCESS "#16a34a")
(def AGENT-BG "#f4f6fb")
(def AGENT-BORDER "#c9d3e6")
(def EVENT-C PINK)        ; a browser event (a POST)
(def CTX-C "#7c3aed")     ; the new ctx, controller → view
(def HTML-C "#059669")    ; the render, view → browser
(def SERVER-C "#f59e0b")  ; a server event (send-event!)
(def AGENT-C "#2563eb")   ; an agent event, between the agent's procs
(def MODEL-C "#0f766e")   ; to and from the model
(def SANS "Poppins, system-ui, -apple-system, 'Segoe UI', sans-serif")
(def MONO "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")

(def W 1600)
(def H 900)

;;; The scene: its length and its steps (bound when written)

(def ^:dynamic *T* 52.0)
(def ^:dynamic *steps* [])

;;; SVG helpers (as in taia.ds's scripts/component_flow_svg.clj)

(defn esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
(defn fx [n] (format "%.4f" (double n)))
(defn kt [t] (fx (/ (max 0.0 (min *T* t)) *T*)))
(defn dur [] (str "dur=\"" *T* "s\" repeatCount=\"indefinite\""))

(defn anim-opacity
  "Opacity keyframes: `pts` is [[t v] …] (seconds, value), held flat before
   the first and after the last."
  [pts]
  (let [pts (concat [[0 (second (first pts))]] pts [[*T* (second (last pts))]])]
    (str "<animate attributeName=\"opacity\" values=\"" (str/join ";" (map (comp fx second) pts))
         "\" keyTimes=\"" (str/join ";" (map (comp kt first) pts)) "\" " (dur) "/>")))

(defn window
  "Visible (at `peak`) from t0 to t1, with short fades."
  ([t0 t1] (window t0 t1 1))
  ([t0 t1 peak]
   (let [f 0.25]
     (anim-opacity [[t0 0] [(+ t0 f) peak] [(- t1 f) peak] [t1 0]]))))

(defn motion
  "Moves the element along `path` between t0 and t1, at constant speed."
  [path t0 t1]
  (str "<animateMotion path=\"M " (str/join " L " (map #(str/join " " %) path))
       "\" keyPoints=\"0;0;1;1\" keyTimes=\"0;" (kt t0) ";" (kt t1) ";1\" calcMode=\"linear\" " (dur) "/>"))

(defn attrs [m] (str/join " " (for [[k v] m] (str (name k) "=\"" v "\""))))

(defn rect [{:keys [x y w h]} & {:as as}]
  (str "<rect x=\"" x "\" y=\"" y "\" width=\"" w "\" height=\"" h "\" " (attrs as) "/>"))

(defn text [x y s & {:as as}]
  (str "<text x=\"" x "\" y=\"" y "\" " (attrs as) ">" s "</text>"))

(defn grow [{:keys [x y w h]} d] {:x (- x d) :y (- y d) :w (+ w (* 2 d)) :h (+ h (* 2 d))})

(def pill {:w 112 :h 40})
(defn pill-box [[cx cy]] {:x (- cx (/ (:w pill) 2)) :y (- cy (/ (:h pill) 2)) :w (:w pill) :h (:h pill)})

(defn phases
  "One content after another in the same place: `ps` is [[t content] …], each
   shown from its t until the next one's (the first from the start)."
  [ps]
  (str/join
   (for [[[t0 c] [t1 _]] (map vector ps (concat (rest ps) [[(+ *T* 1) nil]]))]
     (str "<g" (when (pos? t0) " opacity=\"0\"") ">"
          (anim-opacity (cond-> []
                          (pos? t0) (conj [t0 0] [(+ t0 0.01) 1])
                          (zero? t0) (conj [0 1])
                          (< t1 *T*) (conj [t1 1] [(+ t1 0.01) 0])))
          c "</g>"))))

(defn appear
  "`content`, hidden until t."
  [t content]
  (str "<g opacity=\"0\">" (anim-opacity [[t 0] [(+ t 0.15) 1]]) content "</g>"))

;;; Code, as coloured spans

(def kw-c ACCENT-TEXT)
(def str-c "#16a34a")
(def num-c "#b45309")
(def cmt-c "#9aa1ad")

(defn code-line
  "A mono line from [[text colour] …] segments (a bare string is plain text)."
  ([x y segs] (code-line x y segs {}))
  ([x y segs {:keys [size fill weight] :or {size 13 fill TEXT}}]
   (str "<text x=\"" x "\" y=\"" y "\" font-family=\"" MONO "\" font-size=\"" size "\" fill=\"" fill "\""
        (when weight (str " font-weight=\"" weight "\"")) " xml:space=\"preserve\">"
        (str/join (for [s segs]
                    (if (string? s)
                      (esc s)
                      (str "<tspan fill=\"" (second s) "\">" (esc (first s)) "</tspan>"))))
        "</text>")))

(defn code-block
  "Code lines [[y group segs] …] at x, each group lit (a soft band of width
   `w`) during the steps whose :code names it."
  [code x w size]
  (str
   (str/join
    (for [[y group _] code
          :when group
          :let [ws (for [{[t0 t1] :t c :code} *steps* :when (some #{group} c)] [t0 t1])]
          :when (seq ws)]
      (str "<rect x=\"" (- x 8) "\" y=\"" (- y (+ size 2)) "\" width=\"" w "\" height=\"" (+ size 7) "\" rx=\"5\" fill=\"" ACCENT-BG
           "\" stroke=\"" ACCENT-BORDER "\" opacity=\"0\">"
           (anim-opacity (concat [[0 0]] (mapcat (fn [[t0 t1]] [[t0 0] [(+ t0 0.3) 1] [(- t1 0.3) 1] [t1 0]]) ws)))
           "</rect>")))
   (str/join (for [[y _ segs] code] (code-line x y segs {:size size})))))

;;; Drawing

(defn proc-box [{:keys [x y w] :as b} title subtitle & [{:keys [size fill] :or {size 22 fill "#ffffff"}}]]
  (str (rect b :rx 14 :fill fill :stroke BOX-BORDER :stroke-width 1.5)
       (text (+ x 20) (+ y 32) (esc title) :font-size size :font-weight 600 :fill TEXT)
       (text (+ x w -20) (+ y 32) (esc subtitle) :font-size 12 :font-family MONO :fill MUTED :text-anchor "end")
       "<line x1=\"" (+ x 14) "\" y1=\"" (+ y 46) "\" x2=\"" (+ x w -14) "\" y2=\"" (+ y 46) "\" stroke=\"#eee8ea\" stroke-width=\"1.5\"/>"))

(defn chan-pill [at label]
  (let [b (pill-box at)]
    (str (rect b :rx (/ (:h pill) 2) :fill ACCENT-BG :stroke ACCENT-BORDER :stroke-width 1.5)
         (text (first at) (+ (second at) 5) (esc label) :text-anchor "middle" :font-size 14 :font-weight 500 :fill ACCENT-TEXT))))

(defn wire [pts & [dashed?]]
  (str "<polyline points=\"" (str/join " " (map #(str/join "," %) pts)) "\" fill=\"none\" stroke=\"" WIRE
       "\" stroke-width=\"2.5\" marker-end=\"url(#arrow)\"" (when dashed? " stroke-dasharray=\"8 6\"") "/>"))

(defn small [x y s & {:as as}]
  (text x y (esc s) :font-size 12 :font-family MONO :fill MUTED
        :text-anchor (get as :text-anchor "start")))

(defn glows
  "One pink outline per target ({key [box rx]}); it pulses during every step
   whose :glow names it."
  [targets]
  (str/join
   (for [[k [b rx]] targets
         :let [ws (for [{[t0 t1] :t g :glow} *steps* :when (some #{k} g)] [t0 t1])]
         :when (seq ws)]
     (str "<rect " (attrs (let [{:keys [x y w h]} (grow b 7)] {:x x :y y :width w :height h}))
          " rx=\"" (+ rx 6) "\" fill=\"none\" stroke=\"" PINK "\" stroke-width=\"4\" opacity=\"0\">"
          (anim-opacity
           (concat [[0 0]]
                   (mapcat (fn [[t0 t1]]
                             (concat [[t0 0]]
                                     (for [t (range (+ t0 0.2) (- t1 0.2) 0.25)]
                                       [t (+ 0.3 (* 0.4 (Math/pow (Math/sin (* 2.2 (- t t0))) 2)))])
                                     [[(- t1 0.01) 0]]))
                           ws)))
          "</rect>"))))

(defn packet [{:keys [path move label color]}]
  (let [[t0 t1] move
        w (+ 26 (* 7.6 (count label)))]
    (str "<g opacity=\"0\">"
         (anim-opacity [[(- t0 0.2) 0] [t0 1] [(+ t1 0.25) 1] [(+ t1 0.5) 0]])
         (motion path t0 t1)
         "<rect x=\"" (- (/ w 2)) "\" y=\"-15\" width=\"" w "\" height=\"30\" rx=\"15\" fill=\"" color "\"/>"
         (text 0 5 (esc label) :text-anchor "middle" :font-size 12.5 :font-weight 600 :font-family MONO :fill "#ffffff")
         "</g>")))

(defn caption [i {[t0 t1] :t :keys [title] lines :text}]
  (str "<g opacity=\"0\">" (window t0 t1)
       "<circle cx=\"68\" cy=\"810\" r=\"15\" fill=\"" PINK "\"/>"
       (text 68 815 (inc i) :text-anchor "middle" :font-size 14 :font-weight 700 :fill "#ffffff")
       (text 96 816 (esc title) :font-size 20 :font-weight 600 :fill TEXT)
       (str/join (map-indexed (fn [j l] (text 96 (+ 844 (* 22 j)) (esc l) :font-size 15 :fill MUTED)) lines))
       "</g>"))

(defn cursor-click
  "A pointer that moves onto (x, y) and clicks at t."
  [t [x y]]
  (str "<g opacity=\"0\">" (anim-opacity [[(- t 0.8) 0] [(- t 0.6) 1] [(+ t 0.6) 1] [(+ t 0.9) 0]])
       "<animateTransform attributeName=\"transform\" type=\"translate\" values=\""
       (- x 60) " " (+ y 46) ";" (- x 60) " " (+ y 46) ";" x " " y ";" x " " y "\" keyTimes=\"0;"
       (kt (- t 0.6)) ";" (kt (- t 0.05)) ";1\" " (dur) "/>"
       "<path d=\"M0 0 L0 18 L5 14 L9 22 L12.5 20.5 L8.5 12.5 L14.5 12.5 Z\" fill=\"" TEXT "\" stroke=\"#ffffff\" stroke-width=\"1.4\"/></g>"))

;;; ---------------------------------------------------------------------------
;;; Geometry
;;; ---------------------------------------------------------------------------

(def bus-y 118)

;; the browser (left)
(def browser {:x 32 :y 160 :w 250 :h 590})
(def card {:x 46 :y 202 :w 222 :h 400})
(def input-box {:x 58 :y 548 :w 164 :h 34})
(def send-at [246 565])
(def stream-badge {:x 50 :y 612 :w 150 :h 24})
(def browser-x 157)

;; the chat's taia.ds flow (middle)
(def ds-flow {:x 340 :y 160 :w 560 :h 590})
(def controller {:x 420 :y 214 :w 458 :h 302})
(def view {:x 420 :y 560 :w 458 :h 170})
(def in-chan [640 bus-y])
(def out-chan [340 620])

;; the agent's flow (right), and the model outside it
(def agent-flow {:x 990 :y 160 :w 578 :h 590})
(def model {:x 1300 :y 66 :w 250 :h 62})
(def a-in-chan [990 300])
(def a-out-chan [1150 160])
(def callback-chan [1470 160])
(def ui {:x 1070 :y 250 :w 190 :h 110})
(def llm {:x 1330 :y 230 :w 214 :h 152})
(def tools {:x 1110 :y 500 :w 360 :h 172})

;; wire ends used by the packets
(def ui->llm [[1262 285] [1328 285]])
(def llm->ui [[1328 330] [1262 330]])
(def ui->tools [[1140 362] [1140 498]])
(def tools->ui [[1200 498] [1200 362]])
(def llm->tools [[1400 384] [1400 498]])
(def tools->llm [[1450 498] [1450 384]])
(def llm->model [[1380 228] [1380 130]])
(def model->llm [[1470 130] [1470 228]])
(def ui->out [[1150 248] [1150 182]])
(def post-path [[browser-x 160] [browser-x bus-y] [640 bus-y] [640 212]])
(def send-event-path [[1150 140] [1150 bus-y] [640 bus-y] [640 212]])
(def handoff-path [[880 300] [990 300] [1068 300]])
(def to-view [[640 518] [640 558]])
(def render-path [[418 620] [340 620] [250 624]])

;;; ---------------------------------------------------------------------------
;;; The story
;;; ---------------------------------------------------------------------------

(def steps
  [{:t [0.0 4.6]
    :glow [:ds-flow :agent-flow]
    :title "Two flows behind one chat"
    :text ["The chat is a taia.ds connected component: a flow (controller → view) and an SSE stream the page keeps open."
           "The agent is a core.async.flow of its own: ui, llm and tools, with the model outside it."]}
   {:t [4.6 9.4]
    :type 4.9
    :click 6.7
    :packet {:path post-path :move [7.0 8.6] :label "[:SEND_MESSAGE] + {chatInputText \"Add buy milk\"}" :color EVENT-C}
    :glow [:browser :in-chan]
    :code [:send]
    :title "Send: a one-shot POST"
    :text ["@post('/ds/nds/todomvc.todo-chat/c1/send_message') carries the chat's signals, the typed text among them,"
           "into a flow of its own, built from the chat's controller and view."]}
   {:t [9.4 14.2]
    :packets [{:path handoff-path :move [9.6 10.8] :label "[:user-message-sent …]" :color AGENT-C}
              {:path to-view :move [11.4 11.9] :label "ctx {:messages […]}" :color CTX-C}
              {:path render-path :move [12.3 13.3] :label "<div#c1> user: …" :color HTML-C}]
    :user-msg 13.3
    :glow [:controller :a-in-chan :view :out-chan :card]
    :code [:send]
    :title "The controller hands the message to the agent"
    :text ["SEND_MESSAGE → :sending-message: it stores the user's message and puts [:user-message-sent …] on the agent's in-chan."
           "The POST is answered with the chat (the message, the thinking dots, a stop button), and its flow stops."]}
   {:t [14.2 19.0]
    :packets [{:path ui->llm :move [14.4 15.0] :label "user-message-sent" :color AGENT-C}
              {:path llm->tools :move [15.4 16.1] :label "add-user-message-requested" :color AGENT-C}
              {:path tools->llm :move [16.9 17.6] :label "llm-complete-requested" :color AGENT-C}]
    :glow [:ui :llm :tools]
    :code [:history]
    :title "Inside the agent: ui → llm → tools → llm"
    :text ["ui routes the message to llm; llm asks tools for the chat's history (load-chat-messages-fn) and gets back"
           ":llm-complete-requested: the messages, the system instructions and the tool descriptions."]}
   {:t [19.0 23.4]
    :packets [{:path llm->model :move [19.2 20.1] :label "messages + tools" :color MODEL-C}
              {:path llm->ui :move [20.6 21.2] :label "llm-reply-requested" :color AGENT-C}]
    :glow [:llm :model]
    :title "llm calls the model, and returns at once"
    :text ["li/complete! streams on the HTTP client's thread (gpt-5-mini); the step's own output is :llm-reply-requested"
           "for the UI's thinking state. The proc is free for other turns while the model works."]}
   {:t [23.4 28.6]
    :packets [{:path model->llm :move [23.6 24.5] :label "tool call: update_todos" :color MODEL-C}
              {:path llm->tools :move [25.0 25.7] :label ":execute-tools" :color AGENT-C}
              {:path tools->llm :move [26.8 27.5] :label "tools-applied" :color AGENT-C}
              {:path tools->ui :move [26.8 27.5] :label "tool-applied" :color AGENT-C}]
    :stored 26.2
    :glow [:callback :llm :tools]
    :code [:completion :execute]
    :title "The model asks for a tool"
    :text ["Every callback comes back through llm's own :from-completion in-port (callback-chan), and llm re-emits it:"
           ":execute-tools to tools, which runs update_todos (buy milk is in the list now), then tool-applied to ui and tools-applied to llm."]}
   {:t [28.6 33.4]
    :packets [{:path llm->model :move [28.8 29.6] :label "+ the tool's output" :color MODEL-C}
              {:path model->llm :move [30.0 30.7] :label "\"Added\"" :color MODEL-C}
              {:path model->llm :move [30.5 31.2] :label "\"buy milk\"" :color MODEL-C}
              {:path llm->ui :move [31.2 31.8] :label "partial-llm-reply-received" :color AGENT-C}
              {:path ui->out :move [32.2 32.8] :label "partial-llm-reply-received" :color AGENT-C}]
    :glow [:model :callback :llm :ui :a-out-chan]
    :code [:completion]
    :title "The answer streams back, chunk by chunk"
    :text ["With the tool's output in the history, the model answers in text. Each chunk becomes a :partial-llm-reply-received:"
           "llm sends it to ui, and ui puts it on the agent's out-chan, as it did with llm-reply-requested and tool-applied."]}
   {:t [33.4 37.8]
    :packet {:path send-event-path :move [33.6 35.4] :label "send-event! \"partial_llm_reply_received\"" :color SERVER-C}
    :glow [:a-out-chan :in-chan :bridge]
    :title "From the agent's out-chan into the chat's flow"
    :text ["agent-output-handler's go-loop takes every event off the out-chan: (ds/send-event! chat-id user-id \"partial_llm_reply_received\" {…})."
           "The chat has an open connection, so the event goes into that connection's flow: the same controller and view."]}
   {:t [37.8 42.6]
    :packets [{:path to-view :move [38.4 38.9] :label "ctx {:agent-reply …}" :color CTX-C}
              {:path render-path :move [39.6 40.6] :label "[:patch … :append]" :color HTML-C}]
    :stream-at 40.6
    :glow [:controller :view :out-chan :stream :card]
    :code [:agent :render-agent]
    :title "Pushed down the open SSE stream"
    :text ["A server event has no request, so the machine-level :on takes it in any state: agent-reply puts it in the ctx and"
           "todo-chat renders it as effects. Nobody asked, so they go down the stream: the tool card, then the words as they stream."]}
   {:t [42.6 47.4]
    :packets [{:path send-event-path :move [42.8 44.0] :label "send-event! \"llm_reply_received\"" :color SERVER-C}
              {:path render-path :move [44.3 45.0] :label "<div#message-…>" :color HTML-C}
              {:path post-path :move [45.5 46.7] :label "[:COMPLETION_DONE]" :color EVENT-C}]
    :final 45.0
    :done 46.9
    :glow [:in-chan :controller :card]
    :code [:done :render-agent]
    :title "The final reply closes the turn"
    :text ["llm-reply-received takes the same road and swaps the partial reply for the message. Its data-init POSTs completion_done,"
           "and the controller goes back to :displayed: the messages reload and the input is enabled again."]}
   {:t [47.4 52.0]
    :glow [:bridge-in :bridge]
    :title "Two flows, joined by two puts"
    :text ["In: the chat controller's (>! in-chan [:user-message-sent …]). Out: agent-output-handler's ds/send-event! into the chat's connection."
           "Neither flow knows the other's procs, and only llm ever talks to the model."]}])

;;; ---------------------------------------------------------------------------
;;; The picture
;;; ---------------------------------------------------------------------------

(defn step-time [k] (some k *steps*))

(defn browser-part []
  (let [type-t (step-time :type)
        click-t (step-time :click)
        user-t (step-time :user-msg)
        stream-t (step-time :stream-at)
        final-t (step-time :final)
        done-t (step-time :done)
        typing (fn [s] (text 72 570 (esc s) :font-size 13 :fill TEXT))
        send-btn (str "<circle cx=\"" (first send-at) "\" cy=\"" (second send-at) "\" r=\"16\" fill=\"#4a90d9\"/>"
                      (text (first send-at) (+ (second send-at) 5) "➤" :text-anchor "middle" :font-size 14 :fill "#ffffff"))
        stop-btn (str "<circle cx=\"" (first send-at) "\" cy=\"" (second send-at) "\" r=\"16\" fill=\"#e74c3c\"/>"
                      (text (first send-at) (+ (second send-at) 5) "■" :text-anchor "middle" :font-size 12 :fill "#ffffff"))
        dot (fn [i] (str "<circle cx=\"" (+ 66 (* 12 i)) "\" cy=\"298\" r=\"4\" fill=\"" MUTED "\">"
                         (anim-opacity (for [t (range 0 (+ *T* 0.01) 0.3)]
                                         [t (if (= i (mod (int (/ t 0.3)) 3)) 1 0.3)]))
                         "</circle>"))
        who (fn [x y role] (text x y role :font-size 13 :font-weight 600 :fill TEXT))]
    (str
     (rect browser :rx 16 :fill "#ffffff" :stroke BOX-BORDER :stroke-width 1.5)
     (text 50 186 "BROWSER" :font-size 13 :font-weight 600 :fill MUTED :letter-spacing "0.08em")
     (rect card :rx 12 :fill "#ffffff" :stroke "#eee8ea" :stroke-width 1.5)
     (text 62 232 "Todo chat" :font-size 17 :font-weight 600 :fill TEXT)
     (small 252 231 "#c1" :text-anchor "end")
     "<line x1=\"58\" y1=\"246\" x2=\"256\" y2=\"246\" stroke=\"#eee8ea\" stroke-width=\"1.2\"/>"
     ;; the messages
     (appear user-t (str (who 62 274 "user:") (text 104 274 "Add buy milk" :font-size 13 :fill TEXT)))
     (phases [[0 ""] [user-t (str (dot 0) (dot 1) (dot 2))] [stream-t ""]])
     (appear stream-t
             (str (rect {:x 60 :y 290 :w 194 :h 44} :rx 8 :fill SESSION-BG :stroke ACCENT-BORDER)
                  (code-line 70 308 [["update_todos" ACCENT-TEXT]] {:size 12 :weight 600})
                  (code-line 70 325 ["add · " ["\"buy milk\"" str-c]] {:size 11})))
     (appear stream-t (who 62 360 "assistant:"))
     (phases [[0 ""]
              [stream-t (text 140 360 "Added" :font-size 13 :fill MUTED)]
              [(+ stream-t 0.5) (text 140 360 "Added “buy milk”" :font-size 13 :fill MUTED)]
              [(+ stream-t 1.0) (str (text 140 360 "Added “buy milk”" :font-size 13 :fill MUTED)
                                     (text 62 378 "to your list." :font-size 13 :fill MUTED))]
              [final-t (str (text 140 360 "Added “buy milk”" :font-size 13 :fill TEXT)
                            (text 62 378 "to your list." :font-size 13 :fill TEXT))]])
     ;; the input and its button
     (phases [[0 (rect input-box :rx 17 :fill "#ffffff" :stroke "#dddddd")]
              [user-t (rect input-box :rx 17 :fill "#f3f3f3" :stroke "#e6e6e6")]
              [done-t (rect input-box :rx 17 :fill "#ffffff" :stroke "#dddddd")]])
     (phases [[0 (text 72 570 "Type a message..." :font-size 13 :fill "#aaaaaa")]
              [type-t (typing "Add")]
              [(+ type-t 0.4) (typing "Add buy")]
              [(+ type-t 0.8) (typing "Add buy milk")]
              [(+ click-t 0.1) ""]
              [done-t (text 72 570 "Type a message..." :font-size 13 :fill "#aaaaaa")]])
     (phases [[0 send-btn] [user-t stop-btn] [done-t send-btn]])
     (cursor-click click-t send-at)
     ;; the open stream
     (rect stream-badge :rx 12 :fill "#f0fdf4" :stroke "#bbf7d0" :stroke-width 1.2)
     "<circle cx=\"64\" cy=\"624\" r=\"4.5\" fill=\"" SUCCESS "\">"
     (anim-opacity (for [t (range 0 (+ *T* 0.01) 0.5)] [t (if (even? (int (* 2 t))) 1 0.35)])) "</circle>"
     (text 75 629 "SSE stream open" :font-size 12 :fill SUCCESS :font-weight 500)
     (text 50 668 "opened by @get on" :font-size 12 :fill MUTED)
     (text 50 684 "/ds/nds-connect/…/c1/init" :font-size 12 :fill MUTED)
     (text 50 712 "send: a one-shot POST" :font-size 12 :fill MUTED)
     (text 50 728 "replies: down the stream" :font-size 12 :fill MUTED))))

(defn ds-flow-part []
  (let [x (+ (:x controller) 22)
        vx (+ (:x view) 22)
        [post0 post1] [(first (:t (nth *steps* 1))) (second (:t (nth *steps* 2)))]
        done-post (first (:move (last (:packets (nth *steps* 9)))))
        flow-label (fn [s] (text 880 196 s :font-size 12 :font-family MONO :fill ACCENT-TEXT :text-anchor "end"))]
    (str
     (rect ds-flow :rx 16 :fill "#ffffff" :fill-opacity "0.6" :stroke BOX-BORDER :stroke-width 1.5)
     (text 358 198 "chat flow" :font-size 22 :font-weight 600 :fill TEXT)
     (text 358 222 "taia.ds" :font-size 12 :font-weight 600 :fill MUTED :letter-spacing "0.08em")
     (phases [[0 (flow-label "the connection's flow")]
              [post0 (flow-label "this POST's own flow")]
              [post1 (flow-label "the connection's flow")]
              [done-post (flow-label "this POST's own flow")]
              [(+ done-post 1.6) (flow-label "the connection's flow")]])
     (proc-box controller "controller" "todo-chat-controller")
     (code-block
      [[284 :agent ["(defn- " ["agent-reply" kw-c] " [ctx p] (assoc ctx " [":agent-reply" kw-c] " p))"]]
       [308 nil ["(def " ["machine" kw-c]]]
       [326 :agent [" {" [":on" kw-c] " {" [":TOOL_APPLIED" kw-c] " {" [":actions" kw-c] " agent-reply}"]]
       [344 :agent ["      " [":PARTIAL_LLM_REPLY_RECEIVED" kw-c] " {" [":actions" kw-c] " agent-reply} …}"]]
       [362 :send ["  " [":states" kw-c] " {" [":displayed" kw-c] " {" [":on" kw-c] " {" [":SEND_MESSAGE" kw-c] " …}}"]]
       [380 :send ["   " [":sending-message" kw-c]]]
       [398 :send ["   {" [":invoke" kw-c] " {" [":src" kw-c] " (fn [ctx] …"]]
       [416 :send ["      (go (>! (" [":!in-chan" kw-c] " (" [":todomvc" kw-c] " (agents)))"]]
       [434 :send ["              [" [":user-message-sent" kw-c] " {" [":content" kw-c] " …}])))"]]
       [452 :send ["     " [":on-done" kw-c] " {" [":target" kw-c] " " [":completion-ongoing" kw-c] "}}}"]]
       [470 :done ["   " [":completion-ongoing" kw-c]]]
       [488 :done ["   {" [":on" kw-c] " {" [":COMPLETION_DONE" kw-c] " {" [":target" kw-c] " " [":loading-messages" kw-c] "}}}}})"]]]
      x 426 12)
     (proc-box view "view" "todo-chat")
     (code-block
      [[630 nil ["(defn " ["todo-chat" kw-c] " [{" [":keys" kw-c] " [agent-reply] " [":as" kw-c] " ctx}]"]]
       [650 :render-agent ["  (if agent-reply"]]
       [668 :render-agent ["    (agent-reply-effects agent-reply) " [";; [[:patch …] …]" cmt-c]]]
       [686 nil ["    (chat-view ctx)))               " [";; the whole chat" cmt-c]]]
       [712 nil [[";; agent events are patches pushed down the stream" cmt-c]]]]
      vx 426 12)
     (wire [[640 138] [640 210]])
     (wire [[640 518] [640 556]])
     (small 654 543 ":to-view → :from-controller")
     (wire [[418 620] [398 620]])
     (chan-pill in-chan "in-chan")
     (chan-pill out-chan "out-chan"))))

(defn agent-flow-part []
  (let [stored-t (step-time :stored)
        line (fn [x y segs] (code-line x y segs {:size 11.5}))
        lx (+ (:x llm) 18)
        tx (+ (:x tools) 18)
        ux (+ (:x ui) 18)]
    (str
     ;; the model, outside the flow
     (rect model :rx 31 :fill "#ffffff" :stroke AGENT-BORDER :stroke-width 1.5)
     (text (+ (:x model) 125) 92 "model" :font-size 18 :font-weight 600 :fill TEXT :text-anchor "middle")
     (text (+ (:x model) 125) 113 "OpenAI gpt-5-mini · outside the flow" :font-size 11.5 :fill MUTED :text-anchor "middle")
     ;; the flow
     (rect agent-flow :rx 16 :fill AGENT-BG :stroke AGENT-BORDER :stroke-width 1.5)
     ;; llm ⇄ model: the request on the HTTP client's thread, the stream back
     ;; through callback-chan
     (wire [[1380 228] [1380 132]] true)
     (small 1372 200 "request" :text-anchor "end")
     (small 1372 216 "(HTTP thread)" :text-anchor "end")
     (wire [[1470 130] [1470 138]])
     (wire [[1470 182] [1470 226]])
     (text 1010 210 "agent flow" :font-size 22 :font-weight 600 :fill TEXT)
     (text 1010 712 "core.async.flow · agent-builder/create-chat-agent-flow" :font-size 11 :font-family MONO :fill MUTED)
     (text 1010 730 "procs talk only through their ports: 6 conns" :font-size 11 :font-family MONO :fill MUTED)
     ;; ui
     (proc-box ui "ui" "procs.ui" {:size 20})
     (line ux 318 [["in-chan" kw-c] " → :to-llm"])
     (line ux 338 [":from-* → " ["out-chan" kw-c]])
     ;; llm
     (proc-box llm "llm" "procs.llm" {:size 20})
     (code-block [[296 :completion [[":from-completion" kw-c] " callbacks"]]
                  [316 nil [[":from-tools" kw-c]]]
                  [336 nil [[":from-ui" kw-c]]]
                  [364 nil [["read priority, top first" cmt-c]]]]
                 lx 190 11.5)
     ;; tools
     (proc-box tools "tools" "procs.tools" {:size 20})
     (code-block [[566 :history [[":add-user-message-requested" kw-c] " → history"]]
                  [586 :execute [[":execute-tools" kw-c] " → update_todos"]]
                  [606 nil [[":add-assistant-message" kw-c] " → store reply"]]]
                 tx 334 11.5)
     (phases [[0 (line tx 644 [["!db" kw-c] " todos: [" ["\"walk the dog\"" str-c] "]"])]
              [stored-t (line tx 644 [["!db" kw-c] " todos: [" ["\"walk the dog\"" str-c] " " ["\"buy milk\"" str-c] "]"])]])
     ;; the six conns
     (wire ui->llm) (wire llm->ui)
     (wire ui->tools) (wire tools->ui)
     (wire llm->tools) (wire tools->llm)
     (small 1295 276 ":to-llm" :text-anchor "middle")
     (small 1295 350 ":to-ui" :text-anchor "middle")
     ;; the external chans
     (wire [[1046 300] [1068 300]])
     (wire [[1150 248] [1150 184]])
     (chan-pill a-in-chan "in-chan")
     (chan-pill a-out-chan "out-chan")
     (chan-pill callback-chan "callback-chan"))))

(defn bridges []
  (str
   ;; the browser's POSTs, along the top into the chat's in-chan
   (wire [[browser-x 160] [browser-x bus-y] [582 bus-y]])
   (small 176 110 "POST /ds/nds/todomvc.todo-chat/c1/…")
   ;; the agent's output, along the top into the chat's in-chan
   (wire [[1150 140] [1150 bus-y] [698 bus-y]])
   (small 1134 110 "agent-output-handler: <! out-chan → ds/send-event!" :text-anchor "end")
   ;; the controller's hand-off to the agent's in-chan
   (wire [[880 300] [932 300]])))

(defn page []
  (str
   "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " W " " H "\" width=\"" W "\" height=\"" H "\" font-family=\"" SANS "\">\n"
   "<!-- GENERATED by scripts/agent_flow_svg.clj — edit that, not this file. " *T* " s loop. `bb agent-flow-gif` renders the GIF. -->\n"
   "<defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">"
   "<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"" WIRE "\"/></marker></defs>\n"
   (rect {:x 0 :y 0 :w W :h H} :fill BODY-BG)
   (text 32 46 "A chat message: the browser, the chat's taia.ds flow, the agent's flow" :font-size 24 :font-weight 600 :fill TEXT)
   (text 32 72 "in through a one-shot POST and the controller's put; out through send-event! and the open SSE stream" :font-size 14 :fill MUTED)
   (browser-part)
   (ds-flow-part)
   (agent-flow-part)
   (bridges)
   (glows {:browser    [browser 16]
           :card       [card 12]
           :stream     [stream-badge 12]
           :ds-flow    [ds-flow 16]
           :controller [controller 14]
           :view       [view 14]
           :in-chan    [(pill-box in-chan) 20]
           :out-chan   [(pill-box out-chan) 20]
           :agent-flow [agent-flow 16]
           :a-in-chan  [(pill-box a-in-chan) 20]
           :a-out-chan [(pill-box a-out-chan) 20]
           :callback   [(pill-box callback-chan) 20]
           :model      [model 31]
           :ui         [ui 14]
           :llm        [llm 14]
           :tools      [tools 14]
           :bridge     [{:x 700 :y 96 :w 448 :h 32} 16]
           :bridge-in  [{:x 878 :y 286 :w 168 :h 28} 14]})
   (str/join (map packet (mapcat #(cond-> (vec (:packets %)) (:packet %) (conj (:packet %))) *steps*)))
   ;; caption strip
   (rect {:x 32 :y 772 :w 1536 :h 112} :rx 16 :fill "#ffffff" :stroke ACCENT-BORDER :stroke-width 1.5)
   (str/join (map-indexed caption *steps*))
   "\n</svg>\n"))

(let [file ".docs/media/agent-flow.svg"]
  (binding [*T* 52.0 *steps* steps]
    (spit file (page)))
  (println "wrote" file))
