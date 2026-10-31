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
   ["INSERT INTO event_log
      (event_id, tree_id, target_node_id, event_type, artifact_id, correlation_key,
       causation_id, payload, status, operator_id, operator_reason, actor, claims)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (event_id) DO NOTHING"
    event-id tree-id target-node-id event-type artifact-id correlation-key
    causation-id (codec/->jsonb payload) status operator-id operator-reason
    actor (some-> claims codec/->jsonb)])
  event-id)

(defn fetch
  [conn event-id]
  (->event (db/execute-one! conn ["SELECT * FROM event_log WHERE event_id = ?" event-id])))

(defn lock!
  "Read an event under a row lock, for the transaction that will process it.

  `claim-pending` runs in its own short transaction and releases its locks when
  that transaction commits, so two workers can hand the same event to
  `process-event!`. Locking the row here serialises them: the second one wakes to
  find the event already processed, rather than evaluating it against a node the
  first one has since moved."
  [conn event-id]
  (->event (db/execute-one! conn ["SELECT e.*, t.artifact_id AS tree_artifact_id, now() AS now
                                     FROM event_log e LEFT JOIN execution_tree t ON t.tree_id = e.tree_id
                                    WHERE e.event_id = ? FOR UPDATE OF e"
                                  event-id])))

(defn claim-one!
  "Claim and lock the oldest eligible event in one statement, for the kernel
  transaction that will process it: the lock is taken once and held until that
  transaction ends, not taken by a claim, released, and taken again. The
  eligibility rule is `claim-pending`'s. `except` names events this pass has
  already failed and leaves for the next. Nil when nothing is eligible."
  [conn except]
  (let [except (vec except)
        not-in (when (seq except) (str " AND e.event_id NOT IN (" (clojure.string/join ", " (repeat (count except) "?")) ")"))]
    (->event
     (db/execute-one!
      conn
      (into [(str "SELECT e.*, t.artifact_id AS tree_artifact_id, now() AS now
                     FROM event_log e LEFT JOIN execution_tree t ON t.tree_id = e.tree_id
                    WHERE e.status = ?" not-in "
                      AND NOT EXISTS (SELECT 1 FROM event_log o
                                       WHERE o.tree_id = e.tree_id
                                         AND o.target_node_id = e.target_node_id
                                         AND o.status = ?
                                         AND (o.received_at, o.event_id) < (e.received_at, e.event_id))
                    ORDER BY e.received_at, e.event_id
                    LIMIT 1
                    FOR UPDATE OF e SKIP LOCKED")
             status-received]
            (concat except [status-received]))))))

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
  (mapv ->event
        (db/execute!
         conn
         ["SELECT * FROM event_log e
            WHERE e.status = ?
              AND NOT EXISTS (SELECT 1 FROM event_log o
                               WHERE o.tree_id = e.tree_id
                                 AND o.target_node_id = e.target_node_id
                                 AND o.status = ?
                                 AND (o.received_at, o.event_id) < (e.received_at, e.event_id))
            ORDER BY e.received_at, e.event_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED" status-received status-received n])))

(defn mark!
  [conn event-id status]
  (db/execute-one!
   conn
   ["UPDATE event_log SET status = ?, processed_at = now() WHERE event_id = ?"
    status event-id]))

(defn mark-rejected!
  [conn event-id reason]
  (db/execute-one!
   conn
   ["UPDATE event_log SET status = ?, last_error = ?, processed_at = now()
      WHERE event_id = ?" status-rejected reason event-id]))

(defn record-failure!
  "Record a processing failure. Parks the event once `max-attempts` is reached."
  [conn event-id error max-attempts]
  (db/execute-one!
   conn
   ["UPDATE event_log
       SET attempt_count = attempt_count + 1,
           last_error = ?,
           status = CASE WHEN attempt_count + 1 >= ? THEN ? ELSE status END,
           processed_at = CASE WHEN attempt_count + 1 >= ? THEN now() ELSE processed_at END
     WHERE event_id = ?"
    error max-attempts status-parked max-attempts event-id]))

(defn parked
  [conn tree-id]
  (mapv ->event
        (db/execute! conn ["SELECT * FROM event_log WHERE status = ? AND tree_id = ?
                            ORDER BY received_at" status-parked tree-id])))

(defn pending-auto?
  "Is a kernel-raised decision request already waiting for this node, other
  than the event being processed right now? One is enough: when processed it
  decides from the current state and counters."
  [conn tree-id node-id current-event-id]
  (some? (db/execute-one! conn ["SELECT 1 AS one FROM event_log
                                  WHERE tree_id = ? AND target_node_id = ? AND status = ?
                                    AND event_id <> ?
                                    AND (payload->>'auto') = 'true'
                                  LIMIT 1" tree-id node-id status-received current-event-id])))
