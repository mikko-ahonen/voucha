(ns voucha.repo.effect
  "Persistence for effect_instance.

  External effects and store effects share one table, discriminated by
  effect_kind (decision D5): the lifecycle, idempotency, retry, timeout and
  claiming machinery is identical, and 'which effects does this node still owe?'
  stays a single query.

  Effects are created inside the kernel transaction and executed by dispatchers
  strictly after it commits (design 26)."
  (:require [voucha.codec :as codec]
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
  existing row's id is returned instead."
  [conn {:keys [effect-instance-id tree-id owner-node-id effect-kind effect-name
                effect-version artifact-id idempotency-key correlation-key
                uncertainty-policy participant request-payload timeout-at]
         :or {request-payload {} uncertainty-policy "manual"}}]
  (:id (db/execute-one!
        conn
        ["SELECT voucha.effect_insert(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS id"
         effect-instance-id tree-id owner-node-id (name effect-kind) effect-name
         effect-version artifact-id (name uncertainty-policy) participant
         idempotency-key correlation-key
         (codec/->jsonb request-payload) (codec/content-hash request-payload)
         timeout-at])))

(defn fetch
  [conn effect-instance-id]
  (->effect (db/execute-one! conn ["SELECT * FROM voucha.effect_get(?)" effect-instance-id])))

(defn by-external-request-id
  "Correlate an inbound callback to the effect that produced the request id
  (design 28). Without this, a completion arriving hours later has nowhere to go."
  [conn external-request-id]
  (->effect (db/execute-one! conn ["SELECT * FROM voucha.effect_by_external_request_id(?)" external-request-id])))

(defn by-correlation-key
  [conn tree-id correlation-key]
  (mapv ->effect (db/execute! conn ["SELECT * FROM voucha.effect_by_correlation_key(?, ?)" tree-id correlation-key])))

(defn by-owner
  [conn tree-id owner-node-id]
  (mapv ->effect (db/execute! conn ["SELECT * FROM voucha.effect_by_owner(?, ?)" tree-id owner-node-id])))

(defn outstanding-count
  "Effects owned by one node that have not reached a terminal status."
  [conn tree-id node-id]
  (:n (db/execute-one! conn ["SELECT voucha.effect_outstanding_count(?, ?) AS n" tree-id node-id])))

(defn outstanding-count-subtree
  "Effects owned by a node or any of its descendants."
  [conn tree-id node-id]
  (:n (db/execute-one! conn ["SELECT voucha.effect_outstanding_count(?, ?, true) AS n" tree-id node-id])))

(defn- claim!
  [conn mode at n]
  (mapv ->effect (db/execute! conn ["SELECT * FROM voucha.claim_effects_for_dispatch(?, ?, ?)" mode at n])))

(defn claim-dispatchable!
  "Claim effects ready for dispatch and move them to DISPATCHING in the same
  statement, so two workers cannot claim the same effect (design 64).

  A DISPATCH_FAILED effect becomes dispatchable again once its backoff expires.
  An effect with a participant is never claimed here: it waits for
  `voucha.claim_effects` (docs/design/sql-interface.md §3)."
  [conn now n]
  (claim! conn "dispatchable" now n))

(defn claim-reconcilable!
  "Claim effects whose outcome is unknown and whose policy says to ask.

  These sit in DISPATCHED -- the honest state for 'we may have sent it' -- and
  must never re-enter the dispatch path, because the external system may already
  be holding the request (design 29)."
  [conn now n]
  (claim! conn "reconcilable" now n))

(defn claim-stranded!
  "Claim effects left in DISPATCHING past the lease: a dispatcher died between
  the adapter call and recording its outcome, so nothing else will ever look at
  them (design 27, decision D8).

  `updated_at` is the lease. A claim renews it, so two reclaimers cannot take
  the same effect, and a call still in progress is never mistaken for a dead one
  within the lease. The status stays DISPATCHING: it is the honest state until
  the uncertainty policy decides what the lost call means."
  [conn cutoff n]
  (claim! conn "stranded" cutoff n))

(defn claim-timed-out!
  "Claim effects the external system acknowledged, or accepted, and then never
  finished (design 27).

  Only DISPATCHED and ACKNOWLEDGED effects are eligible. A DISPATCHING effect is
  inside an adapter call right now; its outcome is about to be known, and the
  uncertainty policy owns it. A terminal effect is already resolved."
  [conn now n]
  (claim! conn "timed_out" now n))

;; Every write that moves an effect refuses a terminal one, in the database. An
;; adapter is called outside the transaction, so an effect can be cancelled --
;; its owner node having failed -- while its adapter is still talking to the
;; external system. Applying that late outcome would move a terminal effect back
;; to a live status, leaving a non-terminal effect owned by a terminal node. The
;; external system's answer is not lost: it is recorded in `effect_attempt`, and
;; the cancellation already flagged the effect for an operator. Each such write
;; returns the number of rows changed: zero means the write was refused.

(defn- defer!
  [conn effect-instance-id how next-attempt-at error]
  (:n (db/execute-one! conn ["SELECT voucha.effect_defer(?, ?, ?, ?) AS n" effect-instance-id how next-attempt-at error])))

(defn schedule-reconciliation!
  "The request may or may not have landed. Record that, arm a backoff, and leave
  the effect in DISPATCHED so only the query path can pick it up."
  [conn effect-instance-id next-attempt-at error]
  (defer! conn effect-instance-id "reconcile" next-attempt-at error))

(defn schedule-retry!
  "Mark a dispatch failure and arm a backoff. Retry is only ever safe before
  acknowledgement, or under an idempotency key (design 29)."
  [conn effect-instance-id next-attempt-at error]
  (defer! conn effect-instance-id "retry" next-attempt-at error))

(defn park-uncertain!
  "An effect whose outcome is unknown and whose policy forbids both blind retry
  and status query. It stops here until an operator resolves it: resubmitting
  could double-post, and giving up could lose the work."
  [conn effect-instance-id error]
  (defer! conn effect-instance-id "park" nil error))

(defn record-attempt!
  [conn {:keys [attempt-id effect-instance-id attempt-no status external-request-id
                response-payload error-detail]}]
  (db/execute-one! conn ["SELECT voucha.effect_record_attempt(?, ?, ?, ?, ?, ?, ?)"
                         attempt-id effect-instance-id (int attempt-no) status external-request-id
                         (some-> response-payload codec/->jsonb) error-detail]))

(defn record-resolution!
  "Record a non-dispatch resolution -- a cancellation, a completion timeout -- as
  the effect's next attempt.

  The attempt number is computed in the database rather than from `retry_count`,
  because an effect can be resolved while a dispatcher is mid-call and about to
  claim that number for itself."
  [conn {:keys [attempt-id effect-instance-id status error-detail]}]
  (db/execute-one! conn ["SELECT voucha.effect_record_attempt(?, ?, NULL, ?, NULL, NULL, ?)"
                         attempt-id effect-instance-id status error-detail]))

(defn attempts
  [conn effect-instance-id]
  (mapv (fn [row] {:attempt-id (:attempt_id row)
                   :attempt-no (:attempt_no row)
                   :status (:status row)
                   :error-detail (:error_detail row)})
        (db/execute! conn ["SELECT * FROM voucha.effect_attempts(?)" effect-instance-id])))

(defn write-logged!
  "Record a store-effect write. The unique index makes a repeated write of the
  same (effect, target, record-key) a no-op, which is what makes store effects
  safe to retry (design 25)."
  [conn {:keys [write-id effect-instance-id target record-key payload]}]
  (db/execute-one! conn ["SELECT voucha.effect_write_logged(?, ?, ?, ?, ?)"
                         write-id effect-instance-id target record-key (codec/content-hash payload)]))

(defn writes
  [conn effect-instance-id]
  (db/execute! conn ["SELECT * FROM voucha.effect_writes(?)" effect-instance-id]))

(defn set-status!
  "Move a live effect to `status`. Returns the number of rows changed: zero means
  the effect had already reached a terminal status and refused the outcome."
  ([conn effect-instance-id status] (set-status! conn effect-instance-id status nil))
  ([conn effect-instance-id status extra]
   (:n (db/execute-one! conn ["SELECT voucha.effect_set_status(?, ?, ?, ?, ?, ?) AS n"
                              effect-instance-id status
                              (:external-request-id extra)
                              (some-> (:ack-payload extra) codec/->jsonb)
                              (some-> (:completion-payload extra) codec/->jsonb)
                              (some-> (:failure-payload extra) codec/->jsonb)]))))

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
        (db/execute! conn ["SELECT * FROM voucha.report_claim(?)" n])))

(defn settle-report!
  "Mark a report applied, or superseded when the effect had already gone
  terminal and the report could not move it; the note says which."
  [conn report-id status note]
  (db/execute-one! conn ["SELECT voucha.report_settle(?, ?, ?)" report-id status note]))
