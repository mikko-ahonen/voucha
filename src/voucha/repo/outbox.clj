(ns voucha.repo.outbox
  "Persistence for outbox and timeout_schedule.

  Both are written inside the kernel transaction and acted on afterwards, which
  is what keeps external I/O out of the transaction (design 52)."
  (:require [voucha.codec :as codec]
            [voucha.db :as db]))

(def status-pending "PENDING")
(def status-dispatched "DISPATCHED")

(defn enqueue!
  [conn {:keys [outbox-id tree-id node-id type payload]}]
  (db/execute-one!
   conn
   ["INSERT INTO outbox (outbox_id, tree_id, node_id, type, payload, status)
     VALUES (?, ?, ?, ?, ?, ?)"
    outbox-id tree-id node-id type (codec/->jsonb payload) status-pending])
  outbox-id)

(defn claim-pending
  [conn n]
  (mapv (fn [row]
          {:outbox-id (:outbox_id row)
           :tree-id (:tree_id row)
           :node-id (:node_id row)
           :type (:type row)
           :payload (codec/<-jsonb (:payload row))})
        (db/execute! conn ["SELECT * FROM outbox WHERE status = ?
                            ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED"
                           status-pending n])))

(defn mark-dispatched!
  [conn outbox-id]
  (db/execute-one! conn ["UPDATE outbox SET status = ?, dispatched_at = now()
                          WHERE outbox_id = ?" status-dispatched outbox-id]))

;; --- timeouts ----------------------------------------------------------------

(def timeout-pending "PENDING")
(def timeout-fired "FIRED")
(def timeout-cancelled "CANCELLED")

(defn schedule-timeout!
  [conn {:keys [timeout-id tree-id node-id effect-instance-id timeout-kind
                event-type payload fire-at]}]
  (db/execute-one!
   conn
   ["INSERT INTO timeout_schedule
      (timeout_id, tree_id, node_id, effect_instance_id, timeout_kind, event_type,
       payload, fire_at, status)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    timeout-id tree-id node-id effect-instance-id timeout-kind event-type
    (codec/->jsonb (or payload {})) fire-at timeout-pending])
  timeout-id)

(defn claim-due
  [conn now n]
  (mapv (fn [row]
          {:timeout-id (:timeout_id row)
           :tree-id (:tree_id row)
           :node-id (:node_id row)
           :effect-instance-id (:effect_instance_id row)
           :event-type (:event_type row)
           :payload (codec/<-jsonb (:payload row))})
        (db/execute! conn ["SELECT * FROM timeout_schedule
                            WHERE status = ? AND fire_at <= ?
                            ORDER BY fire_at LIMIT ? FOR UPDATE SKIP LOCKED"
                           timeout-pending now n])))

(defn mark-fired!
  [conn timeout-id]
  (db/execute-one! conn ["UPDATE timeout_schedule SET status = ?, fired_at = now()
                          WHERE timeout_id = ?" timeout-fired timeout-id]))

(defn cancel-for-node!
  "Cancel outstanding timeouts for a node. Called when the node leaves the state
  that armed them, so a completed effect cannot later fire a stale timeout."
  [conn tree-id node-id]
  (db/execute-one! conn ["UPDATE timeout_schedule SET status = ?
                          WHERE tree_id = ? AND node_id = ? AND status = ?"
                         timeout-cancelled tree-id node-id timeout-pending]))
