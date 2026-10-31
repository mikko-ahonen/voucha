(ns voucha.kernel-test
  "The transactional kernel.

  The last deftest in this namespace walks the core invariant list from design
  65 explicitly. The rest of the tests exercise the mechanisms those invariants
  depend on.

  The domain vocabulary is the SEPA instant payment example (File -> Batch ->
  Transaction -> Attempt). The handlers are re-exported from the
  reference application `voucha.example.batch`, because several sibling test
  namespaces import `voucha.kernel-test/handlers`. Where a test needs to probe a
  *kernel* property that no well-behaved handler would trigger -- a poison event,
  an effect the artifact forbids, a state the artifact does not define, an atomic
  rollback -- it installs a purpose-built handler over that set."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.codec :as codec]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.handler :as handler]
            [voucha.kernel :as kernel]
            [voucha.plan :as plan]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

;; --- handlers for the standard profile ---------------------------------------
;;
;; Handlers are deterministic: they receive context and an event, and return a
;; plan. None of them performs I/O. We re-use the reference application's set
;; rather than a bespoke one, so the artifact these tests pin is the same one the
;; end-to-end example runs.

(def handlers app/handlers)

(def runtime {:handlers handlers :event {:max-attempts 3}})

;; --- fixtures ----------------------------------------------------------------

(defn- install-artifact!
  "Compile and store `fbt/definition`, returning its artifact id."
  []
  (db/in-tx [tx *ds*]
    (let [a (compiler/compile-definition fbt/definition)]
      (artifact/insert! tx a)
      (:artifact-id a))))

(defn- install-definition!
  "Compile and store an arbitrary definition, returning its artifact id."
  [d]
  (db/in-tx [tx *ds*]
    (let [a (compiler/compile-definition d)]
      (artifact/insert! tx a)
      (:artifact-id a))))

(defn- as-code
  "The profile with the named transitions' plans dropped, so that a test can
  register a code handler for them and see it run."
  [& syms]
  (update fbt/definition :transitions
          (fn [ts] (mapv #(if ((set syms) (:handler %)) (dissoc % :plan) %) ts))))

(defn- planned
  "The profile's plan for a transition, as a function a test can wrap or replace."
  [nt from on]
  (let [specs (:specs (compiler/compile-definition fbt/definition))]
    (handler/plan-fn (compiler/transition-for specs nt from on) {})))

(defn- new-tree!
  "A file with one batch and `n` transactions, all in their initial states;
  with `d`, under that definition rather than the profile."
  ([n] (new-tree! n nil))
  ([n d]
  (let [aid (if d (install-definition! d) (install-artifact!))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"
                               :profile-name fbt/profile-name
                               :profile-version fbt/profile-version})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
      (doseq [i (range n)]
        (kernel/add-child! tx {:tree-id "T" :parent-node-id "B1"
                               :node-id (str "TX" i) :node-type "Transaction"})))
    aid)))

(defn- add!
  [parent-id node-id node-type]
  (db/in-tx [tx *ds*]
    (kernel/add-child! tx {:tree-id "T" :parent-node-id parent-id
                           :node-id node-id :node-type node-type})))

(defn- send!
  ([node-id event-type] (send! node-id event-type {}))
  ([node-id event-type payload]
   (db/in-tx [tx *ds*]
     (kernel/submit-event! tx {:event-id (str (gensym "ev"))
                               :tree-id "T" :target-node-id node-id
                               :event-type event-type :payload payload}))
   (kernel/drain! *ds* runtime)))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- data-of [node-id]
  (:local-data (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- set-state!
  "Force a node's local-state directly, leaving its runtime-owned data intact.
  Test scaffolding: it establishes a hierarchical precondition (a parent in a
  particular state) without driving the parent's own multi-step orchestration,
  which under this profile would fan out and create its own children."
  [node-id state]
  (db/in-tx [tx *ds*]
    (let [n (node/fetch tx "T" node-id)]
      (node/update-state! tx (assoc n :local-state state)))))

(defn- ready!
  "Drive a freshly created Transaction from TX_NEW up to TX_SCREENED: validate it
  (a positive amount cascades straight through), then clear its screening
  manually (no dispatcher is running to complete the `screening.check` effect).
  Reaching TX_SCREENED emits `TransactionReady`, which bubbles to the batch.
  Returns the drain outcomes of that final, bubbling step."
  [tx-id]
  (send! tx-id "TransactionStartCommanded" {:amount 100 :payment-type "SCT"})
  (send! tx-id "TransactionScreeningCleared"))

;; --- tree construction --------------------------------------------------------

(deftest tree-starts-in-artifact-declared-initial-states
  (new-tree! 2)
  (is (= "FILE_NEW" (state-of "F1")))
  (is (= "BATCH_NEW" (state-of "B1")))
  (is (= "TX_NEW" (state-of "TX0"))))

(deftest adding-a-child-updates-the-parent-child-count-and-logs-it
  (new-tree! 2)
  (is (= 2 (:child-count (data-of "B1"))))
  (is (= 1 (:child-count (data-of "F1"))))
  (testing "the parent's data change is itself a checkpoint"
    (let [h (db/in-tx [tx *ds*] (tlog/history tx "T" "B1"))]
      (is (= 2 (count h)))
      (is (every? #(= "kernel/child-added" (:handler-name %)) h))
      (is (every? #(= (:from-state %) (:to-state %)) h)))))

(deftest illegal-child-types-are-refused
  (install-artifact!)
  (db/in-tx [tx *ds*]
    (kernel/create-tree! tx {:tree-id "T" :artifact-id (:artifact-id (compiler/compile-definition fbt/definition))
                             :root-node-id "F1" :root-node-type "File"}))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"File may not contain Transaction"
       (db/in-tx [tx *ds*]
         (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "X"
                                :node-type "Transaction"})))))

;; --- basic transitions --------------------------------------------------------

(deftest an-event-drives-a-transition-and-writes-a-checkpoint
  (new-tree! 1)
  (let [[outcome] (send! "F1" "FileReceived" {:name "f" :batches []})]
    (is (= :transitioned (:outcome outcome)))
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1"))))
  (let [[entry] (filter #(= "file/received" (:handler-name %))
                        (db/in-tx [tx *ds*] (tlog/history tx "T" "F1")))]
    (is (= "FILE_NEW" (:from-state entry)))
    (is (= "WRITING_RECEIVE_FILE" (:to-state entry)))
    (is (some? (:triggering-event-id entry)))))

(deftest an-event-with-no-matching-transition-is-rejected-with-a-reason
  (new-tree! 1)
  (let [[outcome] (send! "F1" "ReceiveFileWritten")]
    (is (= :rejected (:outcome outcome)))
    (is (re-find #"File has no transition from FILE_NEW on ReceiveFileWritten" (:reason outcome))))
  (is (= "FILE_NEW" (state-of "F1")) "a rejected event leaves state untouched")
  (is (= event/status-rejected
         (:status (db/in-tx [tx *ds*] (first (db/execute! tx ["SELECT * FROM event_log"])))))))

(deftest a-patch-mutates-runtime-owned-node-data
  (new-tree! 0)
  ;; A handler reads a value off the event payload and writes it into the node's
  ;; runtime-owned data. Here the file's batch manifest travels on FileReceived
  ;; and is patched into the File node.
  (let [batches [{:batch-id "B1" :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "INST"}]}]]
    (send! "F1" "FileReceived" {:name "reference-file" :batches batches})
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (= batches (:batches (data-of "F1"))))))

;; --- hierarchical validation ---------------------------------------------------

(deftest a-transaction-cannot-pay-while-its-batch-is-not-yet-paying
  ;; Design 14's headline example, in the payment vocabulary: the transaction is
  ;; locally ready (screened, TX_SCREENED), but a transaction may pay only
  ;; while its batch is processing. A pay command that reaches it while the
  ;; batch has not started is forbidden by the hierarchy.
  (new-tree! 2)
  (set-state! "B1" "BATCH_PROCESSING")
  (ready! "TX0")
  (is (= "TX_SCREENED" (state-of "TX0")))
  (is (= "BATCH_PROCESSING" (state-of "B1")) "one of two transactions screened: the batch waits")
  (set-state! "B1" "BATCH_NEW")
  (let [[outcome] (send! "TX0" "TransactionPayCommanded")]
    (is (= :rejected (:outcome outcome)))
    (is (re-find #"parent is BATCH_NEW, must be one of BATCH_PAY_TRANSACTIONS, BATCH_PROCESSING, BATCH_SCREEN_TRANSACTIONS" (:reason outcome)))
    (is (= "TX_SCREENED" (state-of "TX0")) "the transaction did not move"))

  (testing "once the batch is processing, the same event succeeds"
    (set-state! "B1" "BATCH_PROCESSING")
    (let [[outcome] (send! "TX0" "TransactionPayCommanded")]
      (is (= :transitioned (:outcome outcome)))
      (is (= "TX_PAYING" (state-of "TX0"))))))

(deftest a-rejected-transition-reports-every-blocking-rule
  ;; The profile as shipped declares at most one constraint per transition. To
  ;; exercise the kernel property that an operator sees *every* failing rule --
  ;; not just the first -- we pin a variant artifact whose pay transition also
  ;; requires the File to have completed. With the batch not yet processing AND the
  ;; file still processing, both rules fail at once.
  (let [def+ (update fbt/definition :transitions
                     (fn [ts]
                       (mapv (fn [t]
                               (if (and (= "Transaction" (:node-type t))
                                        (= "TransactionPayCommanded" (:on t)))
                                 (update t :requires conj
                                         {:constraint :ancestor-state :node-type "File"
                                          :allowed ["FILE_COMPLETED"]})
                                 t))
                             ts)))
        aid (install-definition! def+)]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "B1" :node-id "TX0" :node-type "Transaction"}))
    (ready! "TX0")
    (is (= "TX_SCREENED" (state-of "TX0")))
    ;; TX0 is ready, but its batch has not started and the file is not complete.
    (let [[outcome] (send! "TX0" "TransactionPayCommanded")]
      (is (= :rejected (:outcome outcome)))
      (is (= 2 (count (:blocked-by outcome)))
          "an operator asking why a node is stuck sees all blocking rules, not just the first")
      (is (re-find #"parent is BATCH_NEW, must be one of BATCH_PAY_TRANSACTIONS, BATCH_PROCESSING, BATCH_SCREEN_TRANSACTIONS" (:reason outcome)))
      (is (re-find #"File ancestor is FILE_NEW, must be one of FILE_COMPLETED" (:reason outcome))))))

(deftest an-attempt-cannot-run-under-a-failed-transaction
  ;; Design 14: "an attempt cannot be retried if its parent is finished." Here an
  ;; attempt may run only while its transaction is paying (TX_PAYING). A
  ;; transaction that has failed forbids it -- the constraint that stops a late
  ;; callback moving a node under a dead parent (design 16).
  (install-artifact!)
  (db/in-tx [tx *ds*]
    (let [aid (:artifact-id (compiler/compile-definition fbt/definition))]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "B1" :node-id "TX0" :node-type "Transaction"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "TX0" :node-id "A0" :node-type "Attempt"})))
  (set-state! "TX0" "TX_FAILED")
  (is (= "TX_FAILED" (state-of "TX0")))
  (let [[outcome] (send! "A0" "AttemptCommanded")]
    (is (= :rejected (:outcome outcome)))
    (is (re-find #"parent is TX_FAILED, must be one of TX_PAYING" (:reason outcome)))
    (is (= "ATTEMPT_NEW" (state-of "A0")))))

;; --- aggregation and bubbling --------------------------------------------------

(deftest child-completion-bubbles-and-updates-parent-counters
  (new-tree! 3)
  (set-state! "B1" "BATCH_PROCESSING")

  (ready! "TX0")
  (is (= 1 (get-in (data-of "B1") [:counters :tx-ready])))
  (is (= "BATCH_PROCESSING" (state-of "B1"))
      "the batch waits: one of three transactions is screened")

  (ready! "TX1")
  (is (= 2 (get-in (data-of "B1") [:counters :tx-ready])))
  (is (= "BATCH_PROCESSING" (state-of "B1")))

  (ready! "TX2")
  (is (= 3 (get-in (data-of "B1") [:counters :tx-ready])))
  (is (= "BATCH_PROCESSING" (state-of "B1"))
      "the third screening satisfies the aggregate: the batch decides to pay and waits for the payments")
  (is (some #(= "AllTransactionsScreened" (:event_type %))
            (db/in-tx [tx *ds*] (db/execute! tx ["SELECT event_type FROM event_log WHERE target_node_id = 'B1'"])))
      "the decision was raised by the kernel from the rules"))

(deftest counters-track-current-membership-not-cumulative-visits
  ;; Regression guard: a child that enters a counted state and later leaves it
  ;; must decrement. An increment-only counter would let a batch "reconcile"
  ;; with more accounted children than it has.
  (new-tree! 1)
  (set-state! "B1" "BATCH_PROCESSING")
  (ready! "TX0")
  (is (= 1 (get-in (data-of "B1") [:counters :tx-ready])))

  (send! "TX0" "TransactionPayCommanded")   ; leaves TX_SCREENED for TX_PAYING
  ;; ... and then fails: every attempt is rejected until the transaction gives up.
  (dotimes [_ 4] (send! "TX0" "AttemptFailed"))
  (is (= "TX_FAILED" (state-of "TX0")))

  (let [counters (:counters (data-of "B1"))]
    (is (= 1 (:tx-failed counters)))
    (is (= 1 (:child-count (data-of "B1"))))
    (is (= 1 (+ (:tx-ready counters 0) (:tx-paid counters 0) (:tx-failed counters 0)))
        "exactly one child is accounted for, not two")))

(deftest a-child-settling-before-its-siblings-makes-the-parent-decide-and-wait-again
  ;; The batch is a decision state and a waiting state: one of two transactions
  ;; screened brings it back to decide, and the declared rule, not handler code,
  ;; sends it back to wait. Nothing is rejected and nothing is ignored.
  (new-tree! 2)
  (set-state! "B1" "BATCH_PROCESSING")
  (let [outcomes (ready! "TX0")]
    (is (every? #(= :transitioned (:outcome %)) outcomes) (pr-str (map :outcome outcomes)))
    (is (= "BATCH_PROCESSING" (state-of "B1")) "decided: still pending, so it waits again")
    (is (= 1 (get-in (data-of "B1") [:counters :tx-ready])))
    (is (some #(= "ScreeningPending" (:event_type %))
              (db/in-tx [tx *ds*] (db/execute! tx ["SELECT event_type FROM event_log WHERE target_node_id = 'B1'"])))
        "the decision was raised by the kernel from the rules")))

(deftest a-file-completes-only-when-every-batch-is-terminal
  (install-artifact!)
  (let [aid (:artifact-id (compiler/compile-definition fbt/definition))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B2" :node-type "Batch"})))
  (set-state! "F1" "FILE_PROCESS_BATCH")

  ;; An empty batch validates, has nothing to prepare, and settles straight to
  ;; BATCH_COMPLETED, bubbling BatchSettled up to the file.
  (send! "B1" "BatchStartCommanded" {:transactions []})
  (is (= "BATCH_COMPLETED" (state-of "B1")))
  (is (= "FILE_PROCESS_BATCH" (state-of "F1")) "one batch terminal, one still open")
  (is (= 1 (get-in (data-of "F1") [:counters :batches-completed])))

  (send! "B2" "BatchStartCommanded" {:transactions []})
  (is (= "FILE_BATCHES_PROCESSED" (state-of "F1"))
      "both batches terminal, so the file leaves FILE_PROCESS_BATCH")
  (send! "F1" "ResultFileWritten")
  (is (= "FILE_COMPLETED" (state-of "F1")) "and then finalizes"))

;; --- effects are intents -------------------------------------------------------

(deftest a-transition-creates-effect-intents-not-io
  (new-tree! 0)
  (let [[outcome] (send! "F1" "FileReceived" {:name "f" :batches []})]
    (is (= :transitioned (:outcome outcome)))
    (is (= 1 (count (:created-store-effects outcome)))))

  (let [effects (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1"))
        e (first effects)]
    (is (= 1 (count effects)))
    (is (= "CREATED" (:status e)) "the effect is an intent; no dispatcher has run")
    (is (= "store" (:effect-kind e)))
    (is (= "retry-safe" (:uncertainty-policy e)))
    (is (= "file.writeReceive:v1:T:F1" (:idempotency-key e))))

  (testing "an outbox row accompanies the effect, inside the same transaction"
    (is (= 1 (:n (db/in-tx [tx *ds*]
                   (db/execute-one! tx ["SELECT COUNT(*) AS n FROM outbox WHERE status='PENDING'"]))))))

  (testing "the external rail effect carries the query uncertainty policy (D8)"
    ;; Build an attempt that has debited and send it over the instant rail; the
    ;; rail effect is :query.
    (add! "F1" "PB" "Batch")
    (add! "PB" "PT" "Transaction")
    (add! "PT" "PA" "Attempt")
    (set-state! "PT" "TX_PAYING")
    (set-state! "PA" "ATTEMPT_DEBITED")
    (send! "PA" "AttemptSendInst")
    (let [e (first (filter #(= "rail.inst.send" (:effect-name %))
                           (db/in-tx [tx *ds*] (effect/by-owner tx "T" "PA"))))]
      (is (= "query" (:uncertainty-policy e)))
      (is (= "external" (:effect-kind e))))))

(deftest a-timeout-is-scheduled-and-disarmed-on-leaving-the-state
  ;; No transition in the shipped profile arms a timeout, so we pin a variant
  ;; whose receive step's plan arms one. Entering the awaiting state schedules
  ;; the timeout; leaving it disarms whatever that state armed.
  (let [pending-timeouts (fn [] (:n (db/in-tx [tx *ds*]
                                      (db/execute-one!
                                       tx ["SELECT COUNT(*) AS n FROM timeout_schedule WHERE status='PENDING'"]))))
        timed (update fbt/definition :transitions
                      (fn [ts] (mapv #(if (= 'file/received (:handler %))
                                        (update % :plan conj [:timeout 900000 "ReceiveFileWriteFailed"])
                                        %)
                                     ts)))
        aid (install-definition! timed)]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/submit-event! tx {:event-id "fr" :tree-id "T" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:name "f" :batches []}}))
    (kernel/drain! *ds* runtime)
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (= 1 (pending-timeouts)))

    (send! "F1" "ReceiveFileWritten")
    (is (not= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (= 0 (pending-timeouts))
        "leaving WRITING_RECEIVE_FILE disarms the timeout that state armed")))

;; --- determinism ---------------------------------------------------------------

(deftest identifiers-are-derived-from-the-triggering-event
  (new-tree! 0)
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id "fixed-event-id" :tree-id "T" :target-node-id "F1"
                              :event-type "FileReceived" :payload {:name "f" :batches []}}))
  (kernel/drain! *ds* runtime)
  (let [e (first (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1")))]
    (is (= (subs (codec/sha256 "fixed-event-id:1") 0 32)
           (:effect-instance-id e))
        "effect ids are a pure function of the event, so replay mints the same ids")))

;; --- poison events --------------------------------------------------------------

(deftest a-poison-event-is-retried-then-parked
  ;; Decision D7: bounded retry, then PARK. The node keeps its state; no
  ;; dead-letter table, no cascading failure into the tree.
  (new-tree! 0 (as-code 'file/received))
  (let [exploding (assoc runtime :handlers
                         (assoc handlers 'file/received
                                (fn [_ _] (throw (RuntimeException. "handler exploded")))))]
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "poison" :tree-id "T" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:batches []}}))
    (dotimes [_ 3] (kernel/drain! *ds* exploding))

    (let [ev (db/in-tx [tx *ds*] (event/fetch tx "poison"))]
      (is (= event/status-parked (:status ev)))
      (is (= 3 (:attempt-count ev)))
      (is (re-find #"handler exploded" (:last-error ev))))
    (is (= "FILE_NEW" (state-of "F1")) "the node never moved")
    (is (= 1 (count (db/in-tx [tx *ds*] (event/parked tx "T"))))))

  (testing "an operator resolves it with an explicit event carrying identity and reason"
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "manual" :tree-id "T" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:batches []}
                                :operator-id "ops@example.com"
                                :operator-reason "restarted intake after fix"}))
    (kernel/drain! *ds* (assoc-in runtime [:handlers 'file/received] (planned "File" "FILE_NEW" "FileReceived")))
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (let [ev (db/in-tx [tx *ds*] (event/fetch tx "manual"))]
      (is (= "ops@example.com" (:operator-id ev)))
      (is (= "restarted intake after fix" (:operator-reason ev))))))

;; --- artifact pinning ------------------------------------------------------------

(deftest execution-is-pinned-to-the-trees-artifact
  (let [aid (new-tree! 0)
        ;; A newer artifact exists, but this tree does not use it.
        newer (install-definition! (assoc-in fbt/definition [:profile :version] "v4"))]
    (is (not= aid newer))
    (send! "F1" "FileReceived" {:name "f" :batches []})
    (let [entry (first (filter #(= "file/received" (:handler-name %))
                               (db/in-tx [tx *ds*] (tlog/history tx "T" "F1"))))]
      (is (= aid (:artifact-id entry))
          "the checkpoint records the pinned artifact, not the newest one"))))

;; --- the invariant list (design 65) ------------------------------------------------

(deftest core-invariants
  ;; The file's start-batches step runs as a registered function here, so the
  ;; last two checks can replace it with an illegal one.
  (new-tree! 1 (as-code 'file/start-batches))
  (let [runtime (assoc-in runtime [:handlers 'file/start-batches]
                          (planned "File" "WRITING_RECEIVE_FILE" "ReceiveFileWritten"))
        handlers (:handlers runtime)]

  (testing "no node transition without a triggering event"
    (let [before (db/in-tx [tx *ds*] (count (tlog/tree-history tx "T")))]
      (kernel/drain! *ds* runtime)
      (is (= before (db/in-tx [tx *ds*] (count (tlog/tree-history tx "T"))))
          "draining an empty queue transitions nothing")))

  (testing "no runtime-owned state change without a node_transition_log entry"
    (send! "F1" "FileReceived" {:name "f" :batches []})
    (let [h (db/in-tx [tx *ds*] (tlog/history tx "T" "F1"))]
      (is (= "WRITING_RECEIVE_FILE" (:to-state (last h))))))

  (testing "no node transition without local state validation"
    (is (= :rejected (:outcome (first (send! "F1" "FileReceived" {:batches []}))))
        "the same event a second time finds no transition from WRITING_RECEIVE_FILE"))

  (testing "no node transition without hierarchical context validation"
    (ready! "TX0")
    (is (= :rejected (:outcome (first (send! "TX0" "TransactionPayCommanded"))))
        "TX0 is screened, but its batch has not started"))

  (testing "no effect without an owner node and an idempotency key"
    (let [e (first (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1")))]
      (is (= "F1" (:owner-node-id e)))
      (is (seq (:idempotency-key e)))
      (is (seq (:correlation-key e)))))

  (testing "no external I/O inside the kernel transaction: effects stay CREATED"
    (is (every? #(= "CREATED" (:status %))
                (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1")))))

  (testing "no effect emitted where the artifact does not permit it"
    ;; A File may emit only its two store effects. A handler that calls a
    ;; Transaction effect is aborted, and nothing it planned lands.
    (let [bad (assoc runtime :handlers
                     (assoc handlers 'file/start-batches
                            (fn [_ _] (-> (plan/plan) (plan/state "FILE_PROCESS_BATCH")
                                          (plan/call "screening.check" {})))))]
      (db/in-tx [tx *ds*]
        (kernel/submit-event! tx {:event-id "illegal-effect" :tree-id "T" :target-node-id "F1"
                                  :event-type "ReceiveFileWritten"}))
      (kernel/drain! *ds* bad)
      (is (= "WRITING_RECEIVE_FILE" (state-of "F1")) "the transition was aborted")
      (is (empty? (filter #(= "screening.check" (:effect-name %))
                          (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1")))))))

  (testing "no transition to a state the artifact does not define"
    (let [bad (assoc runtime :handlers
                     (assoc handlers 'file/start-batches (fn [_ _] (-> (plan/plan) (plan/state "IMAGINARY")))))]
      (db/in-tx [tx *ds*]
        (kernel/submit-event! tx {:event-id "illegal-state" :tree-id "T" :target-node-id "F1"
                                  :event-type "ReceiveFileWritten"}))
      (kernel/drain! *ds* bad)
      (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))))))

(deftest a-failed-transition-rolls-back-entirely
  ;; Atomicity: the effect intent, the emitted event, the checkpoint and the node
  ;; state either all land or none do.
  (new-tree! 0 (as-code 'file/received))
  (let [bad (assoc runtime :handlers
                   (assoc handlers 'file/received
                          (fn [_ _] (-> (plan/plan)
                                        (plan/state "WRITING_RECEIVE_FILE")
                                        (plan/emit "ReceiveFileWritten")
                                        (plan/call "screening.check" {})))))]
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "atomic" :tree-id "T" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:batches []}}))
    (kernel/drain! *ds* bad))
  (is (= "FILE_NEW" (state-of "F1")))
  (is (empty? (filter #(= "file/received" (:handler-name %))
                      (db/in-tx [tx *ds*] (tlog/history tx "T" "F1"))))
      "no checkpoint survived the rollback")
  (is (empty? (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1"))))
  (is (= 1 (:n (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM event_log"]))))
      "no emitted event survived the rollback"))

(deftest a-declared-ignore-drops-an-event-the-state-does-not-handle
  ;; The batch commands every transaction to pay, including one that failed
  ;; screening. The transaction's :ignores says TX_FAILED drops that command:
  ;; the event is recorded as ignored, not rejected as an error.
  (new-tree! 1)
  (set-state! "B1" "BATCH_PROCESSING")
  (set-state! "TX0" "TX_FAILED")
  (let [[outcome] (send! "TX0" "TransactionPayCommanded")]
    (is (= :ignored (:outcome outcome)) (pr-str outcome))
    (is (= "TX_FAILED" (state-of "TX0"))))
  (let [[outcome] (send! "TX0" "TransactionStartCommanded")]
    (is (= :rejected (:outcome outcome)) "an event the state neither handles nor ignores is still refused")))
