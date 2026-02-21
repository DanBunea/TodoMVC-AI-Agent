(ns agents.domain.chat)

(def verbose-trace-event-names
  "Event names emitted per streamed chunk; traced at :verbose so they are omitted when *min-level* is :info."
  #{:agents.domain.chat/partial-llm-reply-received
    :agents.domain.chat/partial-llm-tools-reply-received
    :agents.domain.chat/partial-reasoning-reply-received})
