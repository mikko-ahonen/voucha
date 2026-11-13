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
  (db/execute-one! conn ["SELECT voucha.outbox_enqueue(?, ?, ?, ?, ?)"
                         outbox-id tree-id node-id type (codec/->jsonb payload)])
  outbox-id)

(defn claim-pending
  [conn n]
  (mapv (fn [row]
          {:outbox-id (:outbox_id row)
           :tree-id (:tree_id row)
           :node-id (:node_id row)
           :type (:type row)
           :payload (codec/<-jsonb (:payload row))})
        (db/execute! conn ["SELECT * FROM voucha.outbox_claim(?)" n])))

(defn mark-dispatched!
  [conn outbox-id]
  (db/execute-one! conn ["SELECT voucha.outbox_mark_dispatched(?)" outbox-id]))

;; --- timeouts ----------------------------------------------------------------

(def timeout-pending "PENDING")
(def timeout-fired "FIRED")
(def timeout-cancelled "CANCELLED")

(defn schedule-timeout!
  [conn {:keys [timeout-id tree-id node-id effect-instance-id timeout-kind
                event-type payload fire-at]}]
  (db/execute-one! conn ["SELECT voucha.timeout_schedule(?, ?, ?, ?, ?, ?, ?, ?)"
                         timeout-id tree-id node-id effect-instance-id timeout-kind event-type
                         (codec/->jsonb (or payload {})) fire-at])
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
        (db/execute! conn ["SELECT * FROM voucha.timeout_claim_due(?, ?)" now n])))

(defn mark-fired!
  [conn timeout-id]
  (db/execute-one! conn ["SELECT voucha.timeout_mark_fired(?)" timeout-id]))

(defn cancel-for-node!
  "Cancel outstanding timeouts for a node. Called when the node leaves the state
  that armed them, so a completed effect cannot later fire a stale timeout."
  [conn tree-id node-id]
  (db/execute-one! conn ["SELECT voucha.timeout_cancel_for_node(?, ?)" tree-id node-id]))
