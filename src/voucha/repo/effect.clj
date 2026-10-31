(ns voucha.repo.effect
  "Persistence for effect_instance.

  External effects and store effects share one table, discriminated by
  effect_kind (decision D5): the lifecycle, idempotency, retry, timeout and
  claiming machinery is identical, and 'which effects does this node still owe?'
  stays a single query.

  Effects are created inside the kernel transaction and executed by dispatchers
  strictly after it commits (design 26)."
  (:require [next.jdbc :as jdbc]
            [voucha.codec :as codec]
            [voucha.db :as db]))

(def status-created "CREATED")
(def status-dispatching "DISPATCHING")
(def status-dispatched "DISPATCHED")
(def status-acknowledged "ACKNOWLEDGED")
(def status-completed "COMPLETED")
(def status-dispatch-failed "DISPATCH_FAILED")
(def status-failed "FAILED")
(def status-timed-out "TIMED_OUT")
(def status-cancelled "CANCELLED")

(def terminal-statuses
  #{status-completed status-failed status-timed-out status-cancelled})

(def outstanding-statuses
  (into #{} (remove terminal-statuses)
        [status-created status-dispatching status-dispatched status-acknowledged]))

(defn- ->effect
  [row]
  (when row
    {:effect-instance-id (:effect_instance_id row)
     :tree-id (:tree_id row)
     :owner-node-id (:owner_node_id row)
     :effect-kind (:effect_kind row)
     :effect-name (:effect_name row)
     :effect-version (:effect_version row)
     :artifact-id (:artifact_id row)
     :status (:status row)
     :uncertainty-policy (:uncertainty_policy row)
     :participant (:participant row)
     :claimed-by (:claimed_by row)
     :idempotency-key (:idempotency_key row)
     :correlation-key (:correlation_key row)
     :external-request-id (:external_request_id row)
     :request-payload (codec/<-jsonb (:request_payload row))
     :ack-payload (codec/<-jsonb (:ack_payload row))
     :completion-payload (codec/<-jsonb (:completion_payload row))
     :failure-payload (codec/<-jsonb (:failure_payload row))
     :retry-count (:retry_count row)
     :timeout-at (:timeout_at row)}))

(defn insert!
  "Create a durable effect intent. Returns the effect instance id.

  The unique index on (effect_name, effect_version, idempotency_key) means a
  replayed transition cannot create a second request for the same work; the
  existing row is returned instead."
  [conn {:keys [effect-instance-id tree-id owner-node-id effect-kind effect-name
                effect-version artifact-id idempotency-key correlation-key
                uncertainty-policy participant request-payload timeout-at]
         :or {request-payload {} uncertainty-policy "manual"}}]
  (let [res (db/execute-one!
             conn
             ["INSERT INTO effect_instance
                (effect_instance_id, tree_id, owner_node_id, effect_kind, effect_name,
                 effect_version, artifact_id, status, uncertainty_policy, participant,
                 idempotency_key, correlation_key, request_payload, request_payload_hash, timeout_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (effect_name, effect_version, idempotency_key) DO NOTHING
               RETURNING effect_instance_id"
              effect-instance-id tree-id owner-node-id (name effect-kind) effect-name
              effect-version artifact-id status-created (name uncertainty-policy) participant
              idempotency-key correlation-key
              (codec/->jsonb request-payload) (codec/content-hash request-payload)
              timeout-at])]
    (or (:effect_instance_id res)
        ;; Idempotent re-creation: the original intent already exists.
        (:effect_instance_id
         (db/execute-one! conn
                          ["SELECT effect_instance_id FROM effect_instance
                             WHERE effect_name = ? AND effect_version = ? AND idempotency_key = ?"
                           effect-name effect-version idempotency-key])))))

(defn fetch
  [conn effect-instance-id]
  (->effect (db/execute-one! conn ["SELECT * FROM effect_instance WHERE effect_instance_id = ?"
                                   effect-instance-id])))

(defn by-external-request-id
  "Correlate an inbound callback to the effect that produced the request id
  (design 28). Without this, a completion arriving hours later has nowhere to go."
  [conn external-request-id]
  (->effect (db/execute-one! conn ["SELECT * FROM effect_instance WHERE external_request_id = ?"
                                   external-request-id])))

(defn by-correlation-key
  [conn tree-id correlation-key]
  (mapv ->effect (db/execute! conn ["SELECT * FROM effect_instance
                                      WHERE tree_id = ? AND correlation_key = ?
                                      ORDER BY created_at" tree-id correlation-key])))

(defn by-owner
  [conn tree-id owner-node-id]
  (mapv ->effect (db/execute! conn ["SELECT * FROM effect_instance
                                      WHERE tree_id = ? AND owner_node_id = ?
                                      ORDER BY created_at" tree-id owner-node-id])))

(defn outstanding-count
  "Effects owned by one node that have not reached a terminal status."
  [conn tree-id node-id]
  (:n (db/execute-one!
       conn
       (into ["SELECT COUNT(*) AS n FROM effect_instance
                WHERE tree_id = ? AND owner_node_id = ? AND status NOT IN (?,?,?,?)"
              tree-id node-id]
             (vec terminal-statuses)))))

(defn outstanding-count-subtree
  "Effects owned by a node or any of its descendants."
  [conn tree-id node-id]
  (:n (db/execute-one!
       conn
       (into ["WITH RECURSIVE down AS (
                 SELECT node_id FROM state_node WHERE tree_id = ? AND node_id = ?
                 UNION ALL
                 SELECT c.node_id FROM state_node c
                   JOIN down ON c.parent_node_id = down.node_id
                  WHERE c.tree_id = ?
               )
               SELECT COUNT(*) AS n FROM effect_instance e
                JOIN down ON e.owner_node_id = down.node_id
                WHERE e.tree_id = ? AND e.status NOT IN (?,?,?,?)"
              tree-id node-id tree-id tree-id]
             (vec terminal-statuses)))))

(defn claim-dispatchable!
  "Claim effects ready for dispatch and move them to DISPATCHING in the same
  statement, so two workers cannot claim the same effect (design 64).

  A DISPATCH_FAILED effect becomes dispatchable again once its backoff expires.
  An effect with a participant is never claimed here: it waits for
  `voucha.claim_effects` (docs/design/sql-interface.md §3)."
  [conn now n]
  (mapv ->effect
        (db/execute!
         conn
         ["UPDATE effect_instance SET status = ?, updated_at = now()
            WHERE effect_instance_id IN (
              SELECT effect_instance_id FROM effect_instance
               WHERE participant IS NULL
                 AND (status = ?
                      OR (status = ? AND next_attempt_at IS NOT NULL AND next_attempt_at <= ?))
               ORDER BY created_at
               LIMIT ?
               FOR UPDATE SKIP LOCKED)
            RETURNING *"
          status-dispatching status-created status-dispatch-failed now n])))

(defn claim-reconcilable!
  "Claim effects whose outcome is unknown and whose policy says to ask.

  These sit in DISPATCHED -- the honest state for 'we may have sent it' -- and
  must never re-enter the dispatch path, because the external system may already
  be holding the request (design 29)."
  [conn now n]
  (mapv ->effect
        (db/execute!
         conn
         ["SELECT * FROM effect_instance
            WHERE status = ? AND uncertainty_policy = 'query'
              AND next_attempt_at IS NOT NULL AND next_attempt_at <= ?
            ORDER BY next_attempt_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED" status-dispatched now n])))

(defn claim-stranded!
  "Claim effects left in DISPATCHING past the lease: a dispatcher died between
  the adapter call and recording its outcome, so nothing else will ever look at
  them (design 27, decision D8).

  `updated_at` is the lease. A claim renews it, so two reclaimers cannot take
  the same effect, and a call still in progress is never mistaken for a dead one
  within the lease. The status stays DISPATCHING: it is the honest state until
  the uncertainty policy decides what the lost call means."
  [conn cutoff n]
  (mapv ->effect
        (db/execute!
         conn
         ["UPDATE effect_instance SET updated_at = now()
            WHERE effect_instance_id IN (
              SELECT effect_instance_id FROM effect_instance
               WHERE status = ? AND updated_at < ?
               ORDER BY updated_at
               LIMIT ?
               FOR UPDATE SKIP LOCKED)
            RETURNING *"
          status-dispatching cutoff n])))

(def ^:private not-terminal-clause
  "Every write that moves an effect must refuse a terminal one.

  An adapter is called outside the transaction, so an effect can be cancelled --
  its owner node having failed -- while its adapter is still talking to the
  external system. Applying that late outcome would move a terminal effect back
  to a live status, leaving a non-terminal effect owned by a terminal node. The
  external system's answer is not lost: it is recorded in `effect_attempt`, and
  the cancellation already flagged the effect for an operator."
  " AND status NOT IN ('COMPLETED','FAILED','TIMED_OUT','CANCELLED')")

(defn- update-live!
  "Apply an update to an effect only while it is non-terminal. Returns the number
  of rows changed: zero means a terminal effect refused the write."
  [conn sql params]
  (-> (db/execute-one! conn (into [(str sql not-terminal-clause)] params))
      :next.jdbc/update-count))

(defn schedule-reconciliation!
  "The request may or may not have landed. Record that, arm a backoff, and leave
  the effect in DISPATCHED so only the query path can pick it up."
  [conn effect-instance-id next-attempt-at error]
  (update-live!
   conn
   "UPDATE effect_instance
       SET status = ?, retry_count = retry_count + 1,
           next_attempt_at = ?, failure_payload = ?, updated_at = now()
     WHERE effect_instance_id = ?"
   [status-dispatched next-attempt-at
    (codec/->jsonb {:error error :uncertain true}) effect-instance-id]))

(defn record-attempt!
  [conn {:keys [attempt-id effect-instance-id attempt-no status external-request-id
                response-payload error-detail]}]
  (db/execute-one!
   conn
   ["INSERT INTO effect_attempt
      (attempt_id, effect_instance_id, attempt_no, status, external_request_id,
       response_payload, error_detail, finished_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, now())
     ON CONFLICT (effect_instance_id, attempt_no) DO NOTHING"
    attempt-id effect-instance-id attempt-no status external-request-id
    (some-> response-payload codec/->jsonb) error-detail]))

(defn record-resolution!
  "Record a non-dispatch resolution -- a cancellation, a completion timeout -- as
  the effect's next attempt.

  The attempt number is computed in the database rather than from `retry_count`,
  because an effect can be resolved while a dispatcher is mid-call and about to
  claim that number for itself."
  [conn {:keys [attempt-id effect-instance-id status error-detail]}]
  (db/execute-one!
   conn
   ["INSERT INTO effect_attempt
      (attempt_id, effect_instance_id, attempt_no, status, error_detail, finished_at)
     SELECT ?, ?, COALESCE(MAX(attempt_no), 0) + 1, ?, ?, now()
       FROM effect_attempt WHERE effect_instance_id = ?
     ON CONFLICT (effect_instance_id, attempt_no) DO NOTHING"
    attempt-id effect-instance-id status error-detail effect-instance-id]))

(defn attempts
  [conn effect-instance-id]
  (mapv (fn [row] {:attempt-id (:attempt_id row)
                   :attempt-no (:attempt_no row)
                   :status (:status row)
                   :error-detail (:error_detail row)})
        (db/execute! conn ["SELECT * FROM effect_attempt WHERE effect_instance_id = ?
                            ORDER BY attempt_no" effect-instance-id])))

(defn schedule-retry!
  "Mark a dispatch failure and arm a backoff. Retry is only ever safe before
  acknowledgement, or under an idempotency key (design 29)."
  [conn effect-instance-id next-attempt-at error]
  (update-live!
   conn
   "UPDATE effect_instance
       SET status = ?, retry_count = retry_count + 1,
           next_attempt_at = ?, failure_payload = ?, updated_at = now()
     WHERE effect_instance_id = ?"
   [status-dispatch-failed next-attempt-at
    (codec/->jsonb {:error error}) effect-instance-id]))

(defn park-uncertain!
  "An effect whose outcome is unknown and whose policy forbids both blind retry
  and status query. It stops here until an operator resolves it: resubmitting
  could double-post, and giving up could lose the work."
  [conn effect-instance-id error]
  (update-live!
   conn
   "UPDATE effect_instance
       SET status = ?, next_attempt_at = NULL, failure_payload = ?, updated_at = now()
     WHERE effect_instance_id = ?"
   [status-dispatch-failed
    (codec/->jsonb {:error error :uncertain true :requires-operator true})
    effect-instance-id]))

(defn write-logged!
  "Record a store-effect write. The unique index makes a repeated write of the
  same (effect, target, record-key) a no-op, which is what makes store effects
  safe to retry (design 25)."
  [conn {:keys [write-id effect-instance-id target record-key payload]}]
  (db/execute-one!
   conn
   ["INSERT INTO effect_write_log (write_id, effect_instance_id, target, record_key, payload_hash)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (effect_instance_id, target, record_key) DO NOTHING"
    write-id effect-instance-id target record-key (codec/content-hash payload)]))

(defn writes
  [conn effect-instance-id]
  (db/execute! conn ["SELECT * FROM effect_write_log WHERE effect_instance_id = ?"
                     effect-instance-id]))

(defn claim-timed-out!
  "Claim effects the external system acknowledged, or accepted, and then never
  finished (design 27).

  Only DISPATCHED and ACKNOWLEDGED effects are eligible. A DISPATCHING effect is
  inside an adapter call right now; its outcome is about to be known, and the
  uncertainty policy owns it. A terminal effect is already resolved."
  [conn now n]
  (mapv ->effect
        (db/execute!
         conn
         [;; `status` on the right-hand side is the row's value before this update,
          ;; which is the only place the status it timed out of survives.
          "UPDATE effect_instance
             SET status = ?, next_attempt_at = NULL, updated_at = now(),
                 failure_payload = jsonb_build_object('error', 'completion timeout elapsed',
                                                      'prior-status', status)
            WHERE effect_instance_id IN (
              SELECT effect_instance_id FROM effect_instance
               WHERE status IN (?, ?)
                 AND timeout_at IS NOT NULL AND timeout_at <= ?
               ORDER BY timeout_at
               LIMIT ?
               FOR UPDATE SKIP LOCKED)
            RETURNING *"
          status-timed-out status-dispatched status-acknowledged now n])))

(defn- uncertain-when-cancelled?
  "Was the request possibly already handed to the external system?"
  [status]
  (contains? #{status-dispatching status-dispatched status-acknowledged} status))

(defn cancel-outstanding-for-node!
  "Resolve the effects a node still owes, because the node has reached a terminal
  state and nothing will ever consume their outcomes (design 16, 27).

  Cancelling an effect does not undo it. An effect that never left the runtime is
  simply closed. One that may have reached the external system is closed too --
  no transition remains to receive its completion -- but flagged
  `requires-operator`, because the work may still be sitting out there. Each
  cancellation is recorded as an attempt, so the effect's history says why it
  stopped."
  [conn tree-id node-id]
  (let [outstanding (remove #(contains? terminal-statuses (:status %))
                            (by-owner conn tree-id node-id))]
    (into []
          (comp
           (filter
            (fn [{:keys [effect-instance-id status]}]
              ;; A dispatcher may have settled the effect since `by-owner` read it.
              (pos? (update-live!
                     conn
                     "UPDATE effect_instance
                        SET status = ?, next_attempt_at = NULL, timeout_at = NULL,
                            failure_payload = ?, updated_at = now()
                      WHERE effect_instance_id = ?"
                     [status-cancelled
                      (codec/->jsonb (cond-> {:cancelled-by "owner node reached a terminal state"
                                              :prior-status status}
                                       (uncertain-when-cancelled? status)
                                       (assoc :requires-operator true)))
                      effect-instance-id]))))
           (map (fn [{:keys [effect-instance-id status]}]
                  (record-resolution! conn
                                      {:attempt-id (codec/sha256 (str effect-instance-id ":cancelled"))
                                       :effect-instance-id effect-instance-id
                                       :status status-cancelled
                                       :error-detail (str "cancelled from " status
                                                          ": owner node reached a terminal state")})
                  effect-instance-id)))
          outstanding)))

(defn set-status!
  "Move a live effect to `status`. Returns the number of rows changed: zero means
  the effect had already reached a terminal status and refused the outcome."
  ([conn effect-instance-id status] (set-status! conn effect-instance-id status nil))
  ([conn effect-instance-id status extra]
   (update-live!
    conn
    "UPDATE effect_instance
        SET status = ?,
            external_request_id = COALESCE(?, external_request_id),
            ack_payload = COALESCE(?, ack_payload),
            completion_payload = COALESCE(?, completion_payload),
            failure_payload = COALESCE(?, failure_payload),
            updated_at = now()
      WHERE effect_instance_id = ?"
    [status
     (:external-request-id extra)
     (some-> (:ack-payload extra) codec/->jsonb)
     (some-> (:completion-payload extra) codec/->jsonb)
     (some-> (:failure-payload extra) codec/->jsonb)
     effect-instance-id])))

;; --- participant reports (docs/design/sql-interface.md §3) ------------------------------
;;
;; A participant records its outcome with `voucha.report_outcome`, in the
;; transaction that did the work. The dispatcher applies pending reports on its
;; turn, so the lifecycle has one implementation.

(defn claim-reports!
  "Pending participant reports, oldest first, locked for this dispatcher's
  transaction; another dispatcher skips them."
  [conn n]
  (mapv (fn [row]
          {:report-id (:report_id row)
           :effect-instance-id (:effect_instance_id row)
           :participant (:participant row)
           :outcome (keyword (:outcome row))
           :payload (codec/<-jsonb (:payload row))
           :external-request-id (:external_request_id row)})
        (db/execute! conn ["SELECT * FROM effect_report WHERE status = 'PENDING'
                             ORDER BY reported_at, report_id LIMIT ? FOR UPDATE SKIP LOCKED" n])))

(defn settle-report!
  "Mark a report applied, or superseded when the effect had already gone
  terminal and the report could not move it; the note says which."
  [conn report-id status note]
  (db/execute-one! conn ["UPDATE effect_report SET status = ?, note = ?, applied_at = now() WHERE report_id = ?"
                         status note report-id]))
