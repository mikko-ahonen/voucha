(ns voucha.effect-lifecycle-test
  "The effect lifecycle must terminate.

  Design 27 declares statuses the runtime never wrote, and design 16 asks for
  cancellation propagation that had no mechanism. Two holes follow from that:

    - A node that fails leaves its outstanding effects outstanding forever. They
      sit in the operator's backlog, and nothing will ever resolve them.
    - An effect the external system acknowledged and then never completed has no
      timeout of its own. `timeout_schedule` times out a *transition*; it is
      disarmed the moment the node moves on, which dispatch/acknowledgement does.

  Cancelling an effect does not undo it. The external system is not assumed to
  have forgotten the work, which is why an effect cancelled after it may have
  been sent is flagged for an operator rather than quietly closed.

  On the reference profile these properties live on the rails: `rail.inst.send`
  is an asynchronous :query effect that sits DISPATCHED while it waits for a
  callback, owned by an Attempt. An Attempt driven to ATTEMPT_FAILED while its
  send is still DISPATCHED is exactly the audit's example (a rejected attempt
  first reverses its debit, so the attempt turns terminal one dispatcher turn
  after the rejection). The reference profile declares no effect-level
  completion timeout, so the timeout tests compile a variant that arms one on
  `rail.inst.send` -- the timeout machinery under test is the kernel's, not the
  profile's."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.adapter :as adapter]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.kernel :as kernel]
            [voucha.metrics :as metrics]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.node :as node]
            [voucha.repo.outbox :as outbox]
            [voucha.test-db :as tdb :refer [*ds*]])
  (:import (java.sql Timestamp)
           (java.time Instant)))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

;; The reference profile does not arm a completion timeout on any effect (the
;; rails resolve by callback or query). This variant declares one on the instant
;; send, so `fire-effect-timeouts!` -- kernel machinery, not profile policy -- can
;; be exercised. Its timeout event is the rail's own rejection event, so an
;; elapsed send drives the attempt through its reversal to ATTEMPT_FAILED just as
;; a rejection would.
(def ^:private inst-completion-timeout-ms 1800000)

(def ^:private definition-with-inst-timeout
  (-> fbt/definition
      (assoc-in [:effects "rail.inst.send" :completion-timeout-ms] inst-completion-timeout-ms)
      (assoc-in [:effects "rail.inst.send" :timeout-event] "InstPaymentRejected")))

(defn- runtime
  [{:keys [adapters store] :or {store (fake/store-adapter)}}]
  {:handlers app/handlers
   :event {:max-attempts 3}
   :dispatcher {:batch-size 10 :backoff-ms 60 :max-attempts 3}
   :effect-adapters (merge {"screening.check" (fake/effect-adapter [{:outcome :completed}])
                            "account.debit"   (fake/effect-adapter [{:outcome :completed}])
                            "account.reverse" (fake/effect-adapter [{:outcome :completed}])
                            ;; SCT Inst is async: the send is dispatched and the
                            ;; outcome returns later as a callback.
                            "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])
                            "rail.sct.submit" (fake/effect-adapter [{:outcome :completed}])}
                           adapters)
   :store-adapters {"file.writeReceive" store
                    "file.writeResult"  (fake/store-adapter)}})

(defn- compile-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition definition-with-inst-timeout)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(defn- new-tree! [] (compile-tree!))

(defn- payment-tree!
  "File -> Batch -> Transaction, built directly, with the batch already paying.
  `pay!` then primes the transaction and commands it, so the profile creates the
  Attempt `TX1-A0` and drives it down a rail without running the whole file --
  which is what the rail-effect tests need."
  []
  (let [aid (compile-tree!)]
    (db/in-tx [tx *ds*]
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "B1" :node-id "TX1" :node-type "Transaction"})
      (node/update-state! tx (assoc (node/fetch tx "T" "B1") :local-state "BATCH_PROCESSING")))
    aid))

(defn- send!
  ([rt node-id event-type] (send! rt node-id event-type {}))
  ([rt node-id event-type payload]
   (db/in-tx [tx *ds*]
     (kernel/submit-event! tx {:event-id (str (gensym "ev")) :tree-id "T"
                               :target-node-id node-id :event-type event-type :payload payload}))
   (kernel/drain! *ds* rt)))

(defn- pay!
  "TX1 is screened and carries its record; command it to pay. The transaction
  creates `TX1-A0` and commands it, so the attempt's `account.debit` is CREATED."
  [rt payment-type amount]
  (db/in-tx [tx *ds*]
    (let [n (node/fetch tx "T" "TX1")
          b (node/fetch tx "T" "B1")]
      (node/update-state! tx (assoc n :local-state "TX_SCREENED"
                                    :local-data (assoc (:local-data n) :payment-type payment-type :amount amount)))
      ;; TX_SCREENED is a counted state: the batch's counter must say so, or the
      ;; invariant oracle (I3) will rightly object when the transaction leaves it.
      (node/update-state! tx (assoc-in b [:local-data :counters :tx-ready] 1))))
  (send! rt "TX1" "TransactionPayCommanded"))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- the-effect [owner name*]
  (first (filter #(= name* (:effect-name %))
                 (db/in-tx [tx *ds*] (effect/by-owner tx "T" owner)))))

(defn- attempts-of [owner name*]
  (db/in-tx [tx *ds*] (effect/attempts tx (:effect-instance-id (the-effect owner name*)))))

(defn- an-hour-from-now [] (Timestamp/from (.plusSeconds (Instant/now) 3600)))
(defn- now-ts [] (Timestamp/from (Instant/now)))

(defn- to-store-effect! [rt]
  (send! rt "F1" "FileReceived" {:name "f" :batches []}))

(defn- to-inst-send-effect!
  "Drive TX1 down the instant rail: TX1-A0 in ATTEMPT_INST_SENDING with
  `rail.inst.send` still CREATED."
  [rt]
  (pay! rt "INST" 100)
  (dispatcher/run-once! *ds* rt)          ; account.debit completes
  (kernel/drain! *ds* rt))

(defn- to-sct-submit-effect!
  "Drive TX1 down the SCT rail: TX1-A0 in ATTEMPT_SCT_SUBMITTING with
  `rail.sct.submit` still CREATED."
  [rt]
  (pay! rt "SCT" 200)
  (dispatcher/run-once! *ds* rt)
  (kernel/drain! *ds* rt))

(defn- reverse-and-fail!
  "A rejected attempt reverses its debit before it fails: one dispatcher turn
  completes the reversal and the attempt turns terminal."
  [rt]
  (dispatcher/run-once! *ds* rt)
  (kernel/drain! *ds* rt))

(defn- dispatched-inst-send!
  "Get TX1-A0 to ATTEMPT_INST_WAITING with `rail.inst.send` DISPATCHED, awaiting a
  callback that never comes."
  [rt]
  (to-inst-send-effect! rt)
  (dispatcher/run-once! *ds* rt)          ; rail.inst.send -> :dispatched (DISPATCHED), InstPaymentSent
  (kernel/drain! *ds* rt))                ; -> ATTEMPT_INST_WAITING

(defn- stranded-effects
  "Effects still outstanding although the node that owns them is finished. Nothing
  will ever consume their outcome, so their existence is the bug F2 describes."
  []
  (db/in-tx [tx *ds*]
    (db/execute! tx (into ["SELECT e.effect_instance_id, e.status FROM effect_instance e
                              JOIN state_node n
                                ON n.tree_id = e.tree_id AND n.node_id = e.owner_node_id
                             WHERE n.status = 'TERMINAL' AND e.status NOT IN (?,?,?,?)"]
                          (vec effect/terminal-statuses)))))

;; --- cancellation on owner terminality ----------------------------------------

(deftest a-failing-node-cancels-an-effect-that-may-have-reached-the-outside-world
  ;; The audit's example: an Attempt in ATTEMPT_INST_WAITING times out into
  ;; ATTEMPT_FAILED while `rail.inst.send` sits in DISPATCHED. Before this fix the
  ;; effect stayed DISPATCHED forever, visible in the backlog and resolvable by
  ;; nothing.
  (let [rt (runtime {})]
    (payment-tree!)
    (dispatched-inst-send! rt)
    (is (= "ATTEMPT_INST_WAITING" (state-of "TX1-A0")))
    (is (= "DISPATCHED" (:status (the-effect "TX1-A0" "rail.inst.send"))))

    ;; A transition timeout fails the attempt while the send is still outstanding.
    (db/in-tx [tx *ds*]
      (outbox/schedule-timeout! tx {:timeout-id "TO-1" :tree-id "T" :node-id "TX1-A0"
                                    :timeout-kind "transition" :event-type "InstPaymentRejected"
                                    :fire-at (now-ts)}))
    (dispatcher/fire-due-timeouts! *ds* (an-hour-from-now))
    (kernel/drain! *ds* rt)
    (is (= "ATTEMPT_REVERSING" (state-of "TX1-A0")) "the attempt reverses its debit first")
    (is (= "DISPATCHED" (:status (the-effect "TX1-A0" "rail.inst.send")))
        "the send is still outstanding: its owner is not terminal yet")
    (reverse-and-fail! rt)
    (is (= "ATTEMPT_FAILED" (state-of "TX1-A0")))

    (let [e (the-effect "TX1-A0" "rail.inst.send")]
      (is (= "CANCELLED" (:status e)) "a terminal node owes no outstanding effects")
      (is (true? (:requires-operator (:failure-payload e)))
          "the rail may hold the request; cancelling here does not undo it there")
      (is (= "DISPATCHED" (:prior-status (:failure-payload e)))
          "the history records what it was cancelled out of"))

    (testing "the cancellation is an attempt, so the effect's history explains itself"
      (let [last-attempt (last (attempts-of "TX1-A0" "rail.inst.send"))]
        (is (= "CANCELLED" (:status last-attempt)))
        (is (re-find #"DISPATCHED" (:error-detail last-attempt)))))

    (testing "an operator still sees the work the external system may hold"
      (let [unresolved (db/in-tx [tx *ds*] (metrics/unresolved-external-work tx))]
        (is (= ["rail.inst.send"] (mapv :effect-name unresolved)))
        (is (= "TX1-A0" (:owner-node-id (first unresolved)))))
      (is (empty? (filter #(= "rail.inst.send" (:effect-name %))
                          (db/in-tx [tx *ds*] (metrics/effect-backlog tx))))
          "but it has left the backlog: it is terminal, and nothing will resolve it"))

    (is (empty? (stranded-effects)))))

(deftest cancelling-an-effect-that-never-left-the-runtime-needs-no-operator
  (let [rt (runtime {})]
    (new-tree!)
    (to-store-effect! rt)
    (is (= "CREATED" (:status (the-effect "F1" "file.writeReceive")))
        "an intent, not yet claimed by any dispatcher")

    (send! rt "F1" "ReceiveFileWriteFailed")
    (is (= "FILE_PERMANENT_FAILURE" (state-of "F1")))
    (let [e (the-effect "F1" "file.writeReceive")]
      (is (= "CANCELLED" (:status e)))
      (is (nil? (:requires-operator (:failure-payload e)))
          "nothing external happened, so nothing external needs reconciling"))
    (is (empty? (db/in-tx [tx *ds*] (metrics/unresolved-external-work tx))))
    (is (empty? (stranded-effects)))))

(deftest a-node-that-completes-normally-cancels-nothing
  (let [sct (fake/effect-adapter [{:outcome :completed :external-request-id "SCT-1"}])
        rt (runtime {:adapters {"rail.sct.submit" sct}})]
    (payment-tree!)
    (to-sct-submit-effect! rt)
    (dispatcher/run-once! *ds* rt)
    (kernel/drain! *ds* rt)
    (is (= "ATTEMPT_SUCCEEDED" (state-of "TX1-A0")))
    (is (= "COMPLETED" (:status (the-effect "TX1-A0" "rail.sct.submit")))
        "a completed effect is never rewritten to CANCELLED")))

;; --- effect-level completion timeout ------------------------------------------

(deftest an-acknowledged-effect-that-never-completes-times-out-and-drives-the-node
  ;; Design 27: dispatched-but-never-completed is its own path. The
  ;; transition-level timeout cannot cover it -- the send being dispatched moved
  ;; the attempt to ATTEMPT_INST_WAITING, which disarms any transition timeout.
  (let [rt (runtime {})]
    (payment-tree!)
    (dispatched-inst-send! rt)
    (is (= "ATTEMPT_INST_WAITING" (state-of "TX1-A0")))
    (is (= "DISPATCHED" (:status (the-effect "TX1-A0" "rail.inst.send"))))
    (is (zero? (dispatcher/fire-due-timeouts! *ds* (an-hour-from-now)))
        "no transition-level timeout covers a node that has left the state that armed one")

    (testing "the effect's own completion timeout has not elapsed yet"
      (is (zero? (dispatcher/fire-effect-timeouts! *ds*)))
      (is (= "DISPATCHED" (:status (the-effect "TX1-A0" "rail.inst.send")))))

    (testing "once elapsed, the effect times out and its event drives the node"
      (is (= 1 (dispatcher/fire-effect-timeouts! *ds* (an-hour-from-now))))
      (is (= "TIMED_OUT" (:status (the-effect "TX1-A0" "rail.inst.send"))))
      (kernel/drain! *ds* rt)
      (is (= "ATTEMPT_REVERSING" (state-of "TX1-A0")))
      (reverse-and-fail! rt)
      (is (= "ATTEMPT_FAILED" (state-of "TX1-A0")))
      (is (empty? (stranded-effects))))))

(deftest a-completed-effect-is-never-timed-out
  (let [rt (runtime {})]
    (payment-tree!)
    (dispatched-inst-send! rt)
    (dispatcher/deliver-callback! *ds* {:effect-instance-id (:effect-instance-id (the-effect "TX1-A0" "rail.inst.send"))
                                        :outcome :completed
                                        :payload {:rail-ref "INST-REF"}})
    (kernel/drain! *ds* rt)
    (is (= "COMPLETED" (:status (the-effect "TX1-A0" "rail.inst.send"))))
    (is (zero? (dispatcher/fire-effect-timeouts! *ds* (an-hour-from-now))))
    (is (= "COMPLETED" (:status (the-effect "TX1-A0" "rail.inst.send"))))))

;; --- terminal effects refuse late outcomes -------------------------------------

(deftest a-callback-to-a-cancelled-effect-is-refused
  (let [rt (runtime {})]
    (payment-tree!)
    (dispatched-inst-send! rt)
    (db/in-tx [tx *ds*]
      (outbox/schedule-timeout! tx {:timeout-id "TO-1" :tree-id "T" :node-id "TX1-A0"
                                    :timeout-kind "transition" :event-type "InstPaymentRejected"
                                    :fire-at (now-ts)}))
    (dispatcher/fire-due-timeouts! *ds* (an-hour-from-now))
    (kernel/drain! *ds* rt)
    (reverse-and-fail! rt)
    (let [e (the-effect "TX1-A0" "rail.inst.send")]
      (is (= "CANCELLED" (:status e)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"already terminal"
           (dispatcher/deliver-callback! *ds* {:effect-instance-id (:effect-instance-id e)
                                               :outcome :completed
                                               :payload {:rail-ref "IP-LATE"}}))
          "a late completion for cancelled work is an operator's problem, not a transition"))))

(deftest a-dispatcher-in-flight-cannot-resurrect-an-effect-cancelled-under-it
  ;; The adapter is called outside the transaction, by design. So an effect can be
  ;; cancelled -- its owner node having failed -- while its adapter is still
  ;; talking to the external system. When the adapter finally answers, applying
  ;; that outcome would move a terminal effect back to a live status, leaving a
  ;; non-terminal effect owned by a terminal node: precisely the F2 bug, rebuilt
  ;; out of a race. The SCT rail is synchronous, so its submission can be caught
  ;; mid-call.
  (let [calling (promise)
        answer (promise)
        ;; An adapter that is still out when the world changes under it.
        sct (reify adapter/EffectAdapter
              (dispatch [_ _]
                (deliver calling true)
                (deref answer 10000 nil)
                {:outcome :completed :external-request-id "SCT-LATE"})
              (query-status [_ _] {:outcome :unknown}))
        rt (runtime {:adapters {"rail.sct.submit" sct}})]
    (payment-tree!)
    (to-sct-submit-effect! rt)

    (let [dispatching (future (dispatcher/dispatch-once! *ds* rt))]
      (is (deref calling 10000 false) "the adapter is mid-call")
      (is (= "DISPATCHING" (:status (the-effect "TX1-A0" "rail.sct.submit"))))

      ;; While the adapter is out, the attempt is rejected, reverses its debit
      ;; (a separate effect the free dispatcher completes), fails, and cancels
      ;; the submission it still owns.
      (send! rt "TX1-A0" "SctPaymentRejected")
      (is (= "ATTEMPT_REVERSING" (state-of "TX1-A0")))
      (reverse-and-fail! rt)
      (is (= "ATTEMPT_FAILED" (state-of "TX1-A0")))
      (is (= "CANCELLED" (:status (the-effect "TX1-A0" "rail.sct.submit"))))

      (deliver answer true)
      @dispatching)

    (let [e (the-effect "TX1-A0" "rail.sct.submit")]
      (is (= "CANCELLED" (:status e))
          "a terminal effect refuses a late outcome; nothing resurrects it"))
    (is (empty? (stranded-effects))
        "otherwise a terminal attempt owns a live effect again, and F2 is undone")))

;; --- the compiler validates the declaration -------------------------------------

(deftest an-effect-timeout-event-must-be-declared
  ;; An effect that arms a completion timeout must name an event the artifact can
  ;; route when it elapses.
  (let [bad (-> fbt/definition
                (assoc-in [:effects "rail.inst.send" :completion-timeout-ms] 30000)
                (assoc-in [:effects "rail.inst.send" :timeout-event] "NobodyDeclaredThis"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"NobodyDeclaredThis"
                          (compiler/compile-definition bad)))))

(deftest an-effect-timeout-needs-both-a-duration-and-an-event
  (let [with-timeout (-> fbt/definition
                         (assoc-in [:effects "rail.inst.send" :completion-timeout-ms] 30000)
                         (assoc-in [:effects "rail.inst.send" :timeout-event] "InstPaymentRejected"))]
    (testing "a duration with no event could never fire"
      (let [bad (update-in with-timeout [:effects "rail.inst.send"] dissoc :timeout-event)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timeout-event"
                              (compiler/compile-definition bad)))))
    (testing "an event with no duration could never fire either"
      (let [bad (update-in with-timeout [:effects "rail.inst.send"] dissoc :completion-timeout-ms)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"completion-timeout-ms"
                              (compiler/compile-definition bad)))))))
