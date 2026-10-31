(ns voucha.dispatcher
  "Effect and store-effect dispatchers, and the timeout scheduler.

  A dispatcher is the mirror image of the kernel. The kernel performs no I/O and
  commits atomically; the dispatcher performs I/O and never touches node state.
  Between them sits the effect lifecycle (design 27):

      CREATED -> DISPATCHING -> DISPATCHED -> ACKNOWLEDGED -> COMPLETED

  Every outcome an adapter reports becomes an event, and the kernel decides
  whether it advances the node. An adapter that throws has produced an *unknown*
  outcome, which is resolved by the effect's declared uncertainty policy
  (decision D8), never by blindly calling the endpoint again."
  (:require [voucha.adapter :as adapter]
            [voucha.codec :as codec]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.intercept :as intercept]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]
            [voucha.repo.outbox :as outbox]
            [voucha.repo.tree :as tree])
  (:import (java.sql Timestamp)
           (java.time Instant)))

(def ^:private default-backoff-ms 500)
(def ^:private max-dispatch-attempts 5)

;; How long an effect may sit in DISPATCHING before it is presumed to belong to a
;; dead dispatcher. Must exceed any legitimate adapter call: an effect reclaimed
;; while its call is still in progress is resolved by policy regardless -- safe
;; for retry-safe and query, but a manual effect would be parked needlessly.
(def ^:private default-lease-ms (* 5 60 1000))

(defn- now [] (Instant/now))

(defn- backoff-at
  [retry-count backoff-ms]
  (Timestamp/from (.plusMillis (now) (* (long backoff-ms) (bit-shift-left 1 (min retry-count 6))))))

(defn- specs-for
  [conn effect]
  (let [t (tree/fetch conn (:tree-id effect))]
    (:specs (artifact/fetch conn (:artifact-id t)))))

(defn- emit-effect-event!
  "Turn an adapter outcome into an event addressed at the effect's owner node.
  This is the only way an external system can influence the tree."
  [conn effect event-type payload]
  (when event-type
    (event/insert! conn {:event-id (codec/sha256 (str (:effect-instance-id effect) ":" event-type))
                         :tree-id (:tree-id effect)
                         :target-node-id (:owner-node-id effect)
                         :event-type event-type
                         :correlation-key (:correlation-key effect)
                         :causation-id (:effect-instance-id effect)
                         :payload (merge payload
                                         {:effect-instance-id (:effect-instance-id effect)
                                          :request-id (:external-request-id effect)})})))

;; --- outcome application -----------------------------------------------------

(def ^:private outcome->status
  "Adapter outcome -> [effect status, the event it implies, where its payload lands].

  A `:dispatched` outcome carries no payload column: a fire-and-forget queue send
  tells us the message left and nothing more, and the consumer answers later on a
  different channel."
  {:dispatched [effect/status-dispatched :dispatch-event :dispatch-payload]
   :acknowledged [effect/status-acknowledged :ack-event :ack-payload]
   :completed [effect/status-completed :completion-event :completion-payload]
   :failed [effect/status-failed :failure-event :failure-payload]})

(defn- apply-outcome!
  "Record an adapter outcome durably and emit the event it implies.

  Runs in its own transaction, after the external call. If the process dies
  between the call and this commit, the effect stays DISPATCHING and is later
  resolved as uncertain -- which is exactly right, because we do not know.

  The effect may have gone terminal while the adapter was out: its owner node
  failed, and cancellation resolved it. `set-status!` refuses to move a terminal
  effect, and we emit nothing, because no transition remains that could consume
  the event. The answer is not lost -- the attempt is recorded, and the
  cancellation already flagged the effect for an operator."
  [conn spec effect outcome-map]
  (let [;; After-step seam: may rewrite what the external world reported before it
        ;; is recorded and turned into an event. It re-enters the machine as that
        ;; event, so it lands in the log as the outcome that actually drove the node.
        {:keys [outcome external-request-id payload]} (intercept/hit! :effect/before-apply outcome-map)
        effect (cond-> effect external-request-id (assoc :external-request-id external-request-id))
        [status event-key payload-key] (outcome->status outcome)]
    (if (pos? (effect/set-status! conn (:effect-instance-id effect) status
                                  {:external-request-id external-request-id
                                   payload-key payload}))
      (do (emit-effect-event! conn effect (event-key spec) payload)
          outcome)
      :already-terminal)))

(defn- resolve-uncertain!
  "The adapter threw. We do not know whether the external system received the
  request, so the effect's declared policy decides (design 29, decision D8)."
  [ds runtime spec effect error]
  (let [policy (keyword (:uncertainty-policy effect))
        backoff (get-in runtime [:dispatcher :backoff-ms] default-backoff-ms)
        max-attempts (get-in runtime [:dispatcher :max-attempts] max-dispatch-attempts)]
    (case policy
      ;; Safe to send again: the idempotency key protects the external system.
      :retry-safe
      (db/in-tx [tx ds]
        (if (>= (:retry-count effect) max-attempts)
          (if (pos? (effect/set-status! tx (:effect-instance-id effect) effect/status-failed
                                        {:failure-payload {:error error
                                                           :attempts (:retry-count effect)}}))
            (do (emit-effect-event! tx effect (:failure-event spec) {:error error})
                :failed)
            :already-terminal)
          (if (pos? (effect/schedule-retry! tx (:effect-instance-id effect)
                                            (backoff-at (:retry-count effect) backoff) error))
            :retry-scheduled
            :already-terminal)))

      ;; Ask, never resubmit. Resubmitting a request the mainframe already holds
      ;; would double-post. An inconclusive answer leaves the effect in
      ;; DISPATCHED, which only the reconciliation path can claim.
      :query
      (let [adapter* (adapter/adapter-for runtime effect)
            result (try (adapter/query-status adapter* effect)
                        (catch Exception e {:outcome :unknown :error (ex-message e)}))]
        (if (= :unknown (:outcome result))
          (db/in-tx [tx ds]
            (if (pos? (effect/schedule-reconciliation!
                       tx (:effect-instance-id effect)
                       (backoff-at (:retry-count effect) backoff)
                       (str "outcome unknown: " error)))
              :query-inconclusive
              :already-terminal))
          (db/in-tx [tx ds] (apply-outcome! tx spec effect result))))

      ;; Neither safe to retry nor answerable: a human decides.
      :manual
      (db/in-tx [tx ds]
        (if (pos? (effect/park-uncertain! tx (:effect-instance-id effect) error))
          :parked
          :already-terminal)))))

;; --- external effects ---------------------------------------------------------

(defn- dispatch-external!
  [ds runtime effect spec]
  ;; Mark DISPATCHED before the call would be a lie; mark after, and a crash
  ;; leaves DISPATCHING. DISPATCHING is the honest state for "we may have sent it".
  (let [;; Before-step seam: may rewrite the request before it leaves (annotate,
        ;; redact, route) or inject a fault. Match on :effect-kind to scope to
        ;; external vs store.
        effect (intercept/hit! :effect/before-dispatch effect)
        adapter* (adapter/adapter-for runtime effect)
        attempt-no (inc (:retry-count effect))
        result (try {:ok (adapter/dispatch adapter* effect)}
                    (catch Exception e {:error (ex-message e)}))
        ;; After the external call, before we record it: crash here is "sent but
        ;; not recorded", the failure the uncertainty policies exist to survive.
        _ (intercept/hit! :effect/after-call {:effect effect :result result})]
    (if-let [r (:ok result)]
      (db/in-tx [tx ds]
        ;; `apply-outcome!` first: it takes the effect row, so a concurrent
        ;; cancellation is decided before the attempt is numbered against it.
        (let [applied (apply-outcome! tx spec effect r)]
          (when-not (= :already-terminal applied)
            (effect/record-attempt! tx {:attempt-id (codec/sha256 (str (:effect-instance-id effect) ":" attempt-no))
                                        :effect-instance-id (:effect-instance-id effect)
                                        :attempt-no attempt-no
                                        :status (name (:outcome r))
                                        :external-request-id (:external-request-id r)
                                        :response-payload (:payload r)}))
          applied))
      (do (db/in-tx [tx ds]
            (effect/record-attempt! tx {:attempt-id (codec/sha256 (str (:effect-instance-id effect) ":" attempt-no))
                                        :effect-instance-id (:effect-instance-id effect)
                                        :attempt-no attempt-no
                                        :status "ERROR"
                                        :error-detail (:error result)}))
          (resolve-uncertain! ds runtime spec effect (:error result))))))

;; --- store effects -------------------------------------------------------------

(defn- dispatch-store!
  [ds runtime effect spec]
  (let [effect (intercept/hit! :effect/before-dispatch effect)
        adapter* (adapter/adapter-for runtime effect)
        attempt-no (inc (:retry-count effect))
        result (try {:ok (adapter/write adapter* effect)}
                    (catch Exception e {:error (ex-message e)}))
        _ (intercept/hit! :effect/after-call {:effect effect :result result})]
    (if-let [{:keys [target record-key payload]} (:ok result)]
      (db/in-tx [tx ds]
        (effect/write-logged! tx {:write-id (codec/sha256 (str (:effect-instance-id effect) ":" target ":" record-key))
                                  :effect-instance-id (:effect-instance-id effect)
                                  :target target :record-key record-key :payload payload})
        (let [applied (apply-outcome! tx spec effect {:outcome :completed
                                                      :payload {:target target :record-key record-key}})]
          (when-not (= :already-terminal applied)
            (effect/record-attempt! tx {:attempt-id (codec/sha256 (str (:effect-instance-id effect) ":" attempt-no))
                                        :effect-instance-id (:effect-instance-id effect)
                                        :attempt-no attempt-no :status "completed"}))
          applied))
      (do (db/in-tx [tx ds]
            (effect/record-attempt! tx {:attempt-id (codec/sha256 (str (:effect-instance-id effect) ":" attempt-no))
                                        :effect-instance-id (:effect-instance-id effect)
                                        :attempt-no attempt-no :status "ERROR"
                                        :error-detail (:error result)}))
          (resolve-uncertain! ds runtime spec effect (:error result))))))

(defn reclaim-stranded!
  "Resolve effects a dead dispatcher left in DISPATCHING (test plan T0 §6.2).

  DISPATCHING is the honest 'we may have sent it' state, and before this nothing
  ever re-claimed it: not dispatch (CREATED/DISPATCH_FAILED), not reconciliation
  (DISPATCHED), not completion timeouts (DISPATCHED/ACKNOWLEDGED). A dispatcher
  that died mid-call stranded its effect until an operator noticed.

  Reclaiming never re-dispatches blindly. The lost call is recorded as an ERROR
  attempt, and the effect goes through exactly the policy resolution an adapter
  throw gets (design 29, D8): retry-safe schedules a retry under the idempotency
  key, query asks the external system, manual parks it for an operator."
  ([ds runtime] (reclaim-stranded! ds runtime (get-in runtime [:dispatcher :batch-size] 50)))
  ([ds runtime n]
   (let [lease (get-in runtime [:dispatcher :lease-ms] default-lease-ms)
         cutoff (Timestamp/from (.minusMillis (now) (long lease)))
         stranded (db/in-tx [tx ds] (effect/claim-stranded! tx cutoff n))]
     (doseq [e stranded]
       (let [spec (db/in-tx [tx ds] (compiler/effect-spec (specs-for tx e) (:effect-name e)))
             attempt-no (inc (:retry-count e))
             error "dispatcher lost mid-call: outcome never recorded"]
         (db/in-tx [tx ds]
           (effect/record-attempt! tx {:attempt-id (codec/sha256 (str (:effect-instance-id e) ":" attempt-no))
                                       :effect-instance-id (:effect-instance-id e)
                                       :attempt-no attempt-no
                                       :status "ERROR"
                                       :error-detail error}))
         (resolve-uncertain! ds runtime spec e error)))
     (count stranded))))

;; --- the dispatcher loop --------------------------------------------------------

(defn- reconcile-uncertain!
  "Resolve a possibly-sent request by asking the external system, keyed by the
  identifier it returned. This path never calls `dispatch`."
  [ds runtime effect spec]
  (let [adapter* (adapter/adapter-for runtime effect)
        backoff (get-in runtime [:dispatcher :backoff-ms] default-backoff-ms)
        result (try (adapter/query-status adapter* effect)
                    (catch Exception e {:outcome :unknown :error (ex-message e)}))]
    (if (= :unknown (:outcome result))
      (db/in-tx [tx ds]
        (effect/schedule-reconciliation! tx (:effect-instance-id effect)
                                         (backoff-at (:retry-count effect) backoff)
                                         "outcome still unknown")
        :query-inconclusive)
      (db/in-tx [tx ds] (apply-outcome! tx spec effect result)))))

(defn dispatch-once!
  "Claim and dispatch up to `n` ready effects, then reconcile up to `n` effects
  whose outcome is unknown. Returns their outcomes.

  The claim commits before any adapter runs, so the external call never happens
  inside a transaction (design 52)."
  ([ds runtime] (dispatch-once! ds runtime (get-in runtime [:dispatcher :batch-size] 50)))
  ([ds runtime n]
   (let [at (Timestamp/from (now))
         [claimed reconcilable]
         (db/in-tx [tx ds] [(effect/claim-dispatchable! tx at n)
                            (effect/claim-reconcilable! tx at n)])
         all (concat claimed reconcilable)
         specs (when (seq all) (db/in-tx [tx ds] (specs-for tx (first all))))
         run (fn [reconcile?]
               (fn [e]
                 (let [spec (compiler/effect-spec specs (:effect-name e))]
                   {:effect-instance-id (:effect-instance-id e)
                    :effect-name (:effect-name e)
                    :outcome (cond
                               reconcile? (reconcile-uncertain! ds runtime e spec)
                               (= "store" (:effect-kind e)) (dispatch-store! ds runtime e spec)
                               :else (dispatch-external! ds runtime e spec))})))]
     (into (mapv (run false) claimed) (mapv (run true) reconcilable)))))

(defn- correlate
  "Find the effect an inbound outcome belongs to.

  A request id is the strongest correlation, but a queue send returns none: the
  consumer knows only the correlation key that travelled with the message. In
  that case the effect name disambiguates, since one node may own several
  effects sharing a correlation key (design 28, 30)."
  [conn {:keys [effect-instance-id external-request-id tree-id correlation-key effect-name]}]
  (cond
    effect-instance-id
    (effect/fetch conn effect-instance-id)

    external-request-id
    (effect/by-external-request-id conn external-request-id)

    (and tree-id correlation-key)
    (let [candidates (cond->> (effect/by-correlation-key conn tree-id correlation-key)
                       effect-name (filter #(= effect-name (:effect-name %))))
          live (remove #(contains? effect/terminal-statuses (:status %)) candidates)]
      (when (> (count live) 1)
        (throw (ex-info "Correlation key matches more than one live effect"
                        {:type ::ambiguous-correlation
                         :correlation-key correlation-key
                         :effects (mapv :effect-name live)})))
      ;; Fall back to a settled effect when nothing is live, so a duplicate
      ;; delivery is reported as "already terminal" rather than as an
      ;; uncorrelated callback. They are different problems, and an operator
      ;; chasing a missing message must not be told the key is unknown.
      (or (first live) (first candidates)))))

(defn deliver-callback!
  "Deliver an asynchronous outcome from an external system -- a callback, a queue
  response, a reconciliation result -- to the effect that requested it.

  This is how an ACKNOWLEDGED effect reaches COMPLETED: the mainframe answers
  later, correlated by the request id it handed back, or a queue consumer answers
  correlated by the key that travelled with the message (design 28, 30). The
  resulting event drives the owner node like any other."
  [ds {:keys [outcome payload] :as delivery}]
  (db/in-tx [tx ds]
    (let [e (correlate tx delivery)]
      (when-not e
        (throw (ex-info "Callback could not be correlated to any effect"
                        {:type ::uncorrelated-callback
                         :delivery (dissoc delivery :payload)})))
      (when (contains? effect/terminal-statuses (:status e))
        (throw (ex-info "Effect is already terminal; refusing to re-apply an outcome"
                        {:type ::effect-already-terminal
                         :effect-instance-id (:effect-instance-id e)
                         :status (:status e)})))
      (let [spec (compiler/effect-spec (specs-for tx e) (:effect-name e))]
        (apply-outcome! tx spec e {:outcome outcome
                                   :external-request-id (:external-request-id e)
                                   :payload payload})))))

(defn apply-reports!
  "Apply the outcomes participants reported through the SQL interface (design:
  sql-interface.md §3). A report takes the path a callback takes: the effect
  moves to its outcome status and the owner node receives the event the
  definition names. A report on an effect that has since gone terminal -- its
  owner failed and the cancellation resolved it -- is kept and marked
  superseded; the cancellation already flagged the effect."
  ([ds runtime] (apply-reports! ds runtime (get-in runtime [:dispatcher :batch-size] 50)))
  ([ds runtime n]
   (db/in-tx [tx ds]
     (let [reports (effect/claim-reports! tx n)]
       (doseq [{:keys [report-id effect-instance-id outcome payload external-request-id]} reports]
         (let [e (effect/fetch tx effect-instance-id)
               spec (compiler/effect-spec (specs-for tx e) (:effect-name e))
               applied (apply-outcome! tx spec e {:outcome outcome
                                                  :external-request-id external-request-id
                                                  :payload payload})]
           (if (= :already-terminal applied)
             (effect/settle-report! tx report-id "SUPERSEDED" (str "effect was already " (:status e)))
             (effect/settle-report! tx report-id "APPLIED" nil))))
       (count reports)))))

(defn drain-outbox!
  "Mark outbox rows dispatched. The outbox exists so that 'an effect was created'
  is observable outside the kernel transaction without polling effect_instance.

  Telemetry rows (the Observe tier) carry their own payload rather than pointing
  at an effect, so they are delivered to the runtime's `:telemetry-sink` here —
  after the transition that wrote them has committed, which is the whole point."
  ([ds] (drain-outbox! ds nil))
  ([ds runtime]
   (db/in-tx [tx ds]
     (let [rows (outbox/claim-pending tx 100)
           sink (:telemetry-sink runtime)]
       (intercept/hit! :outbox/before-drain {:rows rows})
       (doseq [r rows]
         (when (and sink (= "telemetry" (:type r)))
           (sink (:payload r)))
         (outbox/mark-dispatched! tx (:outbox-id r)))
       (count rows)))))

;; --- timeouts --------------------------------------------------------------------

(defn fire-due-timeouts!
  "Turn elapsed timeouts into events. A timeout is not a failure; it is an event
  the artifact may or may not have a transition for."
  ([ds] (fire-due-timeouts! ds (Timestamp/from (now))))
  ([ds at]
   (db/in-tx [tx ds]
     (let [due (outbox/claim-due tx at 100)]
       (doseq [{:keys [timeout-id tree-id node-id event-type payload effect-instance-id] :as timeout} due]
         (intercept/hit! :timeout/before-fire timeout)
         (outbox/mark-fired! tx timeout-id)
         (event/insert! tx {:event-id (codec/sha256 (str "timeout:" timeout-id))
                            :tree-id tree-id
                            :target-node-id node-id
                            :event-type event-type
                            :causation-id timeout-id
                            :payload (merge payload
                                            (when effect-instance-id
                                              {:effect-instance-id effect-instance-id}))}))
       (count due)))))

(defn fire-effect-timeouts!
  "Time out effects the external system accepted and never finished (design 27).

  This is not `fire-due-timeouts!`. That one times out a *transition*, and is
  disarmed the moment the node leaves the state that armed it -- which
  acknowledgement does. An acknowledged effect that never completes is its own
  failure mode, and it outlives the state that created it.

  The event id is derived from the effect, so a dispatcher that dies before
  committing cannot deliver the timeout twice."
  ([ds] (fire-effect-timeouts! ds (Timestamp/from (now))))
  ([ds at]
   (db/in-tx [tx ds]
     (let [timed-out (effect/claim-timed-out! tx at 100)]
       (doseq [e timed-out]
         (let [spec (compiler/effect-spec (specs-for tx e) (:effect-name e))]
           (effect/record-resolution! tx {:attempt-id (codec/sha256 (str (:effect-instance-id e) ":timed-out"))
                                          :effect-instance-id (:effect-instance-id e)
                                          :status effect/status-timed-out
                                          :error-detail (str "no completion within the declared timeout, from "
                                                             (get-in e [:failure-payload :prior-status]))})
           (emit-effect-event! tx e (:timeout-event spec) {:timed-out true})))
       (count timed-out)))))

(defn run-once!
  "One full turn of the outside world: reclaim effects a dead dispatcher left
  mid-call, dispatch ready effects, fire due transition and effect timeouts,
  drain the outbox."
  [ds runtime]
  {:reclaimed (reclaim-stranded! ds runtime)
   :reports (apply-reports! ds runtime)
   :dispatched (dispatch-once! ds runtime)
   :timeouts (fire-due-timeouts! ds)
   :effect-timeouts (fire-effect-timeouts! ds)
   :outbox (drain-outbox! ds runtime)})
