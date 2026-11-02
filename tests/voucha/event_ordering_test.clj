(ns voucha.event-ordering-test
  "Events addressed to one node are processed in the order they were received.

  Found by the chaos harness under contention (test plan T3, seed 505). The
  dispatcher sends an instant payment: effect DISPATCHED, `InstPaymentSent`
  pending for the Attempt. The rail answers at once: `InstPaymentRejected`
  pending for the same Attempt. Two kernels: one claims `InstPaymentSent` and is
  slow inside its transaction, holding the event row; the other's SKIP LOCKED
  claim skips that row and takes the *younger* `InstPaymentRejected` -- against
  an Attempt still in ATTEMPT_INST_SENDING, which has no transition for it.
  Rejected, consumed. Then `InstPaymentSent` lands, the Attempt waits in
  ATTEMPT_INST_WAITING for an answer
  that was thrown away, forever. No invariant fired: nothing was inconsistent,
  only dead.

  The fix: a kernel never claims an event addressed to a node while an older
  event addressed to that node is still pending. Receipt order per node is a
  guarantee, like a queue's. Bubbled (untargeted) aggregation events are
  order-independent by design and unaffected."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

(defn- runtime []
  {:handlers app/handlers
   :event {:max-attempts 3}
   :dispatcher {:batch-size 10 :backoff-ms 50 :max-attempts 3}
   :effect-adapters {"account.debit"   (fake/effect-adapter [{:outcome :completed}])
                     "account.reverse" (fake/effect-adapter [{:outcome :completed}])
                     "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])}
   :store-adapters {}})

(defn- payment-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "B1" :node-id "TX1" :node-type "Transaction"})
      (kernel/add-child! tx {:tree-id "T" :parent-node-id "TX1" :node-id "TX1-A0" :node-type "Attempt"})
      ;; The attempt may only run under a paying transaction; this test is about
      ;; event order, not how the transaction got there.
      (db/execute! tx ["UPDATE state_node SET local_state = 'TX_PAYING' WHERE tree_id = 'T' AND node_id = 'TX1'"]))))

(defn- state-of [nid] (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" nid))))
(defn- events [status]
  (db/in-tx [tx *ds*] (db/execute! tx ["SELECT event_type, target_node_id, last_error FROM event_log WHERE status = ?" status])))

(deftest a-younger-event-for-a-node-is-not-processed-while-an-older-one-is-in-flight
  (payment-tree!)
  (let [rt (runtime)]
    ;; TX1-A0 down the instant rail until the send is CREATED.
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "start" :tree-id "T" :target-node-id "TX1-A0"
                                :event-type "AttemptCommanded" :payload {:payment-type "INST" :amount 100}}))
    (kernel/drain! *ds* rt)
    (dispatcher/run-once! *ds* rt)          ; account.debit completes
    (kernel/drain! *ds* rt)                 ; -> ATTEMPT_DEBITED -> ATTEMPT_INST_SENDING
    (is (= "ATTEMPT_INST_SENDING" (state-of "TX1-A0")))

    ;; The send leaves: InstPaymentSent is now pending for TX1-A0 ...
    (dispatcher/dispatch-once! *ds* rt)
    ;; ... and the rail answers before any kernel has processed it.
    (let [send (db/in-tx [tx *ds*]
                 (db/execute-one! tx ["SELECT effect_instance_id FROM effect_instance WHERE effect_name = 'rail.inst.send'"]))]
      (dispatcher/deliver-callback! *ds* {:effect-instance-id (:effect_instance_id send)
                                          :outcome :failed :payload {:error "rejected"}}))
    (is (= 2 (count (events "RECEIVED"))) "both events wait for TX1-A0, in receipt order")

    ;; Kernel A takes the older event and stalls inside its transaction.
    (debug/block! :transition/before-commit :hold
                  :match #(= "InstPaymentSent" (get-in % [:plan :on] (:event-type (:event %) "InstPaymentSent"))))
    (let [kernel-a (future (kernel/drain! *ds* rt))]
      (Thread/sleep 400)                    ; A is parked at before-commit, holding the event row
      ;; Kernel B drains concurrently. It must NOT process the younger event.
      (kernel/drain! *ds* rt)
      (is (empty? (events "REJECTED"))
          "the younger InstPaymentRejected was not taken while InstPaymentSent was still in flight")
      (debug/signal :hold)
      @kernel-a)

    ;; Now the younger event is processed, in order, against the right state.
    (kernel/drain! *ds* rt)
    (is (= "ATTEMPT_REVERSING" (state-of "TX1-A0")) "the rejection was applied after the send, as received")
    (is (empty? (events "RECEIVED")) "nothing left pending")
    (is (empty? (events "REJECTED")) "nothing was thrown away")))
