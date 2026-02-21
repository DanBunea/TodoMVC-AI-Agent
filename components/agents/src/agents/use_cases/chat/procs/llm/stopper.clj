(ns agents.use-cases.chat.procs.llm.stopper
  (:require
   [hyperfiddle.rcf :refer [tests]]))

(def ^:private one-day-ms (* 24 60 60 1000))

(defn- now-ms [] (System/currentTimeMillis))

(defn- purge-old [stopped]
  (let [cutoff (- (now-ms) one-day-ms)]
    (into {} (filter (fn [[_ ts]] (> ts cutoff)) stopped))))

(defn record-stop
  "Adds message-id to the stopped map with the current timestamp.
   Also removes entries older than one day. Returns the updated map."
  [stopped message-id]
  (-> stopped
      (assoc message-id (now-ms))
      purge-old))

(defn is-stopped?
  "Returns true if message-id is present in the stopped map."
  [stopped message-id]
  (contains? stopped message-id))

(tests
 "is-stopped? returns false for empty stopped map"
 (is-stopped? {} "msg-1") := false)

(tests
 "record-stop adds message-id; is-stopped? returns true"
 (let [stopped (record-stop {} "msg-1")]
   (is-stopped? stopped "msg-1") := true))

(tests
 "is-stopped? returns false for an unrecorded message-id"
 (let [stopped (record-stop {} "msg-1")]
   (is-stopped? stopped "msg-2") := false))

(tests
 "record-stop with multiple message-ids tracks each independently"
 (let [stopped (-> {}
                   (record-stop "msg-1")
                   (record-stop "msg-2"))]
   (is-stopped? stopped "msg-1") := true
   (is-stopped? stopped "msg-2") := true
   (is-stopped? stopped "msg-3") := false))

(tests
 "record-stop purges entries older than one day"
 (let [two-days-ago (- (System/currentTimeMillis) (* 2 24 60 60 1000))
       stopped-with-old {"old-msg" two-days-ago}
       stopped (record-stop stopped-with-old "new-msg")]
   (is-stopped? stopped "old-msg") := false
   (is-stopped? stopped "new-msg") := true))

(tests
 "record-stop keeps entries younger than one day"
 (let [half-day-ago (- (System/currentTimeMillis) (* 12 60 60 1000))
       stopped-with-recent {"recent-msg" half-day-ago}
       stopped (record-stop stopped-with-recent "another-msg")]
   (is-stopped? stopped "recent-msg") := true
   (is-stopped? stopped "another-msg") := true))
