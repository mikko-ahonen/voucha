(ns voucha.repo.event
  "Persistence for event_log.

  Events are the only cause of transitions (design 65). A poisonous event --
  one whose processing keeps throwing -- is retried with backoff and then PARKED
  rather than dead-lettered elsewhere (decision D7): the node keeps its state,
  and an operator resolves it with an explicit retry or skip event."
  (:require [clojure.string]
            [voucha.codec :as codec]
            [voucha.db :as db]))

(def status-received "RECEIVED")
(def status-processed "PROCESSED")
(def status-ignored "IGNORED")
(def status-rejected "REJECTED")
(def status-parked "PARKED")
(def status-skipped "SKIPPED")

(def terminal-statuses #{status-processed status-ignored status-rejected status-skipped})

(defn- ->event
  [row]
  (when row
    {:event-id (:event_id row)
     :tree-id (:tree_id row)
     :target-node-id (:target_node_id row)
     :event-type (:event_type row)
     :artifact-id (:artifact_id row)
     :correlation-key (:correlation_key row)
     :causation-id (:causation_id row)
     :payload (codec/<-jsonb (:payload row))
     :status (:status row)
     :attempt-count (:attempt_count row)
     :last-error (:last_error row)
     ;; carried by the locking reads, so the kernel transaction needs neither a tree
     ;; fetch nor a clock reading of its own (docs/design/performance.md)
     :tree-artifact-id (:tree_artifact_id row)
     :now (:now row)
     :operator-id (:operator_id row)
     :operator-reason (:operator_reason row)
     :actor (:actor row)
     :claims (codec/<-jsonb (:claims row))}))

(defn insert!
  "Insert an event. Idempotent on event_id: a dispatcher that crashes after the
  external call and retries cannot deliver the same acknowledgement twice.

  `actor`/`claims` are what Level-1 edge auth resolved (design: testing-api.md);
  the internal emits and callbacks that also call this leave them nil."
  [conn {:keys [event-id tree-id target-node-id event-type artifact-id correlation-key
                causation-id payload status operator-id operator-reason actor claims]
         :or {payload {} status status-received}}]
  (db/execute-one!
   conn
   ["SELECT voucha.event_insert(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    event-id tree-id target-node-id event-type artifact-id correlation-key
    causation-id (codec/->jsonb payload) status operator-id operator-reason
    actor (some-> claims codec/->jsonb)])
  event-id)

(defn fetch
  [conn event-id]
  (->event (db/execute-one! conn ["SELECT * FROM voucha.event_get(?)" event-id])))

(defn lock!
  "Read an event under a row lock, for the transaction that will process it,
  with its tree's artifact and the transaction's clock reading.

  `claim-pending` runs in its own short transaction and releases its locks when
  that transaction commits, so two workers can hand the same event to
  `process-event!`. Locking the row here serialises them: the second one wakes to
  find the event already processed, rather than evaluating it against a node the
  first one has since moved."
  [conn event-id]
  (->event (db/execute-one! conn ["SELECT * FROM voucha.event_lock(?)" event-id])))

(defn claim-one!
  "Claim and lock the oldest eligible event in one statement, for the kernel
  transaction that will process it: the lock is taken once and held until that
  transaction ends. The eligibility rule is `claim-pending`'s, pinned in
  `voucha.claim_event` (docs/design/kernel-procedures.md). `except` names events
  this pass has already failed and leaves for the next. Nil when nothing is
  eligible."
  [conn except]
  (->event (db/execute-one! conn ["SELECT * FROM voucha.claim_event(?)"
                                  (into-array String (map str except))])))

(defn claim-pending
  "Claim up to `n` unprocessed events for this worker, skipping rows another
  worker already holds. DB-backed claiming is what lets runtime nodes scale
  horizontally against one database (design 64).

  Events addressed to one node are claimed in receipt order: an event is not
  eligible while an older event for the same node is still pending -- including
  one another worker is processing right now, whose row SKIP LOCKED would
  otherwise simply step over. Without this, a response could be processed before
  the send it answers, find no transition, and be rejected -- consumed -- leaving
  the node waiting forever (event_ordering_test). Bubbled events carry no target
  and are order-independent by design (design 31), so they are unaffected."
  [conn n]
  (mapv ->event (db/execute! conn ["SELECT * FROM voucha.claim_events(?)" n])))

(defn mark!
  [conn event-id status]
  (db/execute-one! conn ["SELECT voucha.event_mark(?, ?)" event-id status]))

(defn mark-rejected!
  [conn event-id reason]
  (db/execute-one! conn ["SELECT voucha.event_mark(?, ?, ?)" event-id status-rejected reason]))

(defn record-failure!
  "Record a processing failure. Parks the event once `max-attempts` is reached."
  [conn event-id error max-attempts]
  (db/execute-one! conn ["SELECT voucha.event_record_failure(?, ?, ?)" event-id error (int max-attempts)]))

(defn parked
  [conn tree-id]
  (mapv ->event (db/execute! conn ["SELECT * FROM voucha.event_parked(?)" tree-id])))

(defn pending-auto?
  "Is a kernel-raised decision request already waiting for this node, other
  than the event being processed right now? One is enough: when processed it
  decides from the current state and counters."
  [conn tree-id node-id current-event-id]
  (:p (db/execute-one! conn ["SELECT voucha.event_pending_auto(?, ?, ?) AS p" tree-id node-id current-event-id])))
