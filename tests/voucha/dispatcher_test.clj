(ns voucha.dispatcher-test
  "The effect lifecycle, uncertainty policies, and timeouts.

  The kernel proved that effects are created as intents. These tests prove what
  happens after commit: that dispatch is idempotent, that an intermediate durable
  status is distinct from completion, and -- the point of decision D8 -- that an
  unknown outcome is never resolved by blindly calling the endpoint again.

  On the reference profile the lifecycle plays out on two shapes of effect:
  a store effect (`file.writeReceive`, retry-safe, owned by the File) and the
  payment rails (`rail.inst.send`, an asynchronous :query effect whose send is
  DISPATCHED and whose outcome arrives as a callback; `rail.sct.submit`, a
  synchronous :query effect). DISPATCHED and ACKNOWLEDGED here play the role the
  old mainframe's ACKNOWLEDGED did: a first-class status the node advances on
  before the work is known to be done."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.codec :as codec]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.repo.outbox :as outbox]
            [voucha.test-db :as tdb :refer [*ds*]])
  (:import (java.sql Timestamp)
           (java.time Instant)))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(defn- runtime
  "A runtime whose default adapters all succeed. `adapters` overrides individual
  effect adapters (to make one fail, throw, or answer a status query); `store`
  overrides the `file.writeReceive` store adapter."
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

(defn- compile-tree! [definition]
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(defn- new-tree!
  "Just a File. Enough to own a `file.writeReceive` store effect."
  []
  (compile-tree! fbt/definition))

(defn- payment-tree!
  "File -> Batch -> Transaction, built directly, with the batch already paying.
  `pay!` then primes the transaction and commands it, so the profile creates the
  Attempt `TX1-A0` and drives it down a rail without running the whole file --
  which is what the rail-effect tests need."
  []
  (let [aid (compile-tree! fbt/definition)]
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

(defn- an-hour-from-now [] (Timestamp/from (.plusSeconds (Instant/now) 3600)))

(defn- to-store-effect!
  "Drive the File to the point where a `file.writeReceive` store effect is
  outstanding (WRITING_RECEIVE_FILE, the effect still CREATED)."
  [rt]
  (send! rt "F1" "FileReceived" {:name "f" :batches []}))

(defn- to-inst-send-effect!
  "Drive TX1 down the instant rail until its Attempt has a `rail.inst.send` to run:
  TX1-A0 in ATTEMPT_INST_SENDING with the send still CREATED (not yet dispatched)."
  [rt]
  (pay! rt "INST" 100)
  (dispatcher/run-once! *ds* rt)          ; account.debit completes -> AccountDebited
  (kernel/drain! *ds* rt))                ; -> ATTEMPT_DEBITED -> ATTEMPT_INST_SENDING

(defn- to-sct-submit-effect!
  "Drive TX1 down the SCT rail until its Attempt has a `rail.sct.submit` to run:
  TX1-A0 in ATTEMPT_SCT_SUBMITTING with the submission still CREATED."
  [rt]
  (pay! rt "SCT" 200)
  (dispatcher/run-once! *ds* rt)
  (kernel/drain! *ds* rt))

;; --- store effects ---------------------------------------------------------------

(deftest a-store-effect-writes-then-completes-and-resumes-the-node
  (let [store (fake/store-adapter)
        rt (runtime {:store store})]
    (new-tree!)
    (to-store-effect! rt)
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (= "CREATED" (:status (the-effect "F1" "file.writeReceive"))))

    (dispatcher/run-once! *ds* rt)
    (is (= "COMPLETED" (:status (the-effect "F1" "file.writeReceive"))))
    (is (= 1 (count (fake/writes store))))
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1"))
        "the node has not moved yet: the completion event exists but is unprocessed")

    (kernel/drain! *ds* rt)
    (is (= "FILE_BATCHES_PROCESSED" (state-of "F1"))
        "the completion event drives the transition, like any other event; with no batches to wait for the file goes straight on to its result write")))

(deftest a-store-write-is-recorded-and-idempotent
  (let [store (fake/store-adapter)
        rt (runtime {:store store})]
    (new-tree!)
    (to-store-effect! rt)
    (dispatcher/run-once! *ds* rt)
    (let [e (the-effect "F1" "file.writeReceive")
          writes (db/in-tx [tx *ds*] (effect/writes tx (:effect-instance-id e)))]
      (is (= 1 (count writes)))
      (testing "re-running the same write is a no-op under the unique constraint"
        (db/in-tx [tx *ds*]
          (effect/write-logged! tx {:write-id "other-id"
                                    :effect-instance-id (:effect-instance-id e)
                                    :target "enrichment_store" :record-key "F1"
                                    :payload {:different "payload"}}))
        (is (= 1 (count (db/in-tx [tx *ds*] (effect/writes tx (:effect-instance-id e))))))))))

;; --- an intermediate durable status precedes completion ---------------------------

(deftest acknowledgement-precedes-completion
  ;; Design 27: systems that hand back a request id before finishing the work need
  ;; a first-class status between "sent" and "done", or the node advances on a
  ;; promise. On the instant rail that status is DISPATCHED: the send left, the
  ;; rail returned a reference, and the node advances to ATTEMPT_INST_WAITING --
  ;; but the attempt is not SUCCEEDED until the callback arrives.
  (let [ip (fake/effect-adapter [{:outcome :dispatched :external-request-id "INST-REQ-1"}])
        rt (runtime {:adapters {"rail.inst.send" ip}})]
    (payment-tree!)
    (to-inst-send-effect! rt)
    (is (= "ATTEMPT_INST_SENDING" (state-of "TX1-A0")))

    (dispatcher/run-once! *ds* rt)
    (let [e (the-effect "TX1-A0" "rail.inst.send")]
      (is (= "DISPATCHED" (:status e)))
      (is (= "INST-REQ-1" (:external-request-id e))
          "the durable external identifier is stored, not hidden in the adapter"))

    (kernel/drain! *ds* rt)
    (is (= "ATTEMPT_INST_WAITING" (state-of "TX1-A0"))
        "the node advanced on a first-class status, not on an assumption of completion")

    (testing "completion is a distinct step: the callback drives the final transition"
      (dispatcher/deliver-callback! *ds* {:effect-instance-id (:effect-instance-id (the-effect "TX1-A0" "rail.inst.send"))
                                          :outcome :completed
                                          :payload {:rail-ref "INST-REF"}})
      (kernel/drain! *ds* rt)
      (is (= "ATTEMPT_SUCCEEDED" (state-of "TX1-A0")))
      (is (= "COMPLETED" (:status (the-effect "TX1-A0" "rail.inst.send"))))
      (is (= "INST-REF" (:rail-ref (:local-data (db/in-tx [tx *ds*] (node/fetch tx "T" "TX1-A0")))))
          "the business identifier entered node state through a valid transition"))))

(deftest an-effect-is-never-dispatched-twice-once-terminal
  (let [sct (fake/effect-adapter [{:outcome :completed :external-request-id "R1"}])
        rt (runtime {:adapters {"rail.sct.submit" sct}})]
    (payment-tree!)
    (to-sct-submit-effect! rt)
    (dispatcher/run-once! *ds* rt)
    (is (= "COMPLETED" (:status (the-effect "TX1-A0" "rail.sct.submit"))))
    (dispatcher/run-once! *ds* rt)
    (dispatcher/run-once! *ds* rt)
    (is (= 1 (count (filter #(= :dispatch (:op %)) (fake/calls sct))))
        "a completed effect is never re-executed (design 29)")))

(deftest effect-intents-are-idempotent-under-the-same-key
  (let [rt (runtime {})]
    (new-tree!)
    (to-store-effect! rt)
    (let [e (the-effect "F1" "file.writeReceive")]
      (testing "creating the same intent again returns the original row"
        (let [again (db/in-tx [tx *ds*]
                      (effect/insert! tx {:effect-instance-id "a-different-id"
                                          :tree-id "T" :owner-node-id "F1"
                                          :effect-kind :store :effect-name "file.writeReceive"
                                          :effect-version "v1" :artifact-id (:artifact-id e)
                                          :idempotency-key (:idempotency-key e)
                                          :correlation-key "F1"
                                          :uncertainty-policy :retry-safe}))]
          (is (= (:effect-instance-id e) again))
          (is (= 1 (count (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1"))))))))))

;; --- dispatch failure and retry -----------------------------------------------------

(deftest a-retry-safe-effect-retries-with-the-same-idempotency-key
  (let [store (fake/store-adapter [{:throw "connection reset"} {}])
        ;; A backoff long enough that the assertion below is about the backoff and
        ;; not about how long a dispatcher turn happens to take on this machine.
        rt (assoc-in (runtime {:store store}) [:dispatcher :backoff-ms] 1000)]
    (new-tree!)
    (to-store-effect! rt)

    (dispatcher/run-once! *ds* rt)
    (let [e (the-effect "F1" "file.writeReceive")]
      (is (= "DISPATCH_FAILED" (:status e)))
      (is (= 1 (:retry-count e)))
      (is (= "WRITING_RECEIVE_FILE" (state-of "F1")) "the node waits; it does not fail"))

    (testing "the backoff must elapse before the effect is claimed again"
      (is (empty? (dispatcher/dispatch-once! *ds* rt))
          "claimed immediately, the retry would hammer the failing system"))

    (Thread/sleep 1100)
    (dispatcher/run-once! *ds* rt)
    (is (= "COMPLETED" (:status (the-effect "F1" "file.writeReceive"))))
    (kernel/drain! *ds* rt)
    (is (= "FILE_BATCHES_PROCESSED" (state-of "F1")))))

(deftest a-retry-safe-effect-eventually-fails-terminally
  (let [store (fake/store-adapter [{:throw "still broken"}])
        rt (runtime {:store store})]
    (new-tree!)
    (to-store-effect! rt)
    (dotimes [i 6]
      (dispatcher/run-once! *ds* rt)
      (Thread/sleep (* 70 (inc i))))
    (let [e (the-effect "F1" "file.writeReceive")]
      (is (= "FAILED" (:status e)))
      (is (<= 3 (:retry-count e))))
    (kernel/drain! *ds* rt)
    (is (= "FILE_PERMANENT_FAILURE" (state-of "F1"))
        "the failure event drives the file to its declared failure state")
    (testing "every attempt is recorded for the operator"
      (is (<= 3 (count (db/in-tx [tx *ds*]
                         (effect/attempts tx (:effect-instance-id (the-effect "F1" "file.writeReceive"))))))))))

;; --- uncertainty (decision D8) ---------------------------------------------------

(deftest an-uncertain-query-policy-effect-asks-rather-than-resubmits
  ;; The rail throws after possibly receiving the debit. Resubmitting could
  ;; pay twice, so the adapter is asked what happened instead (rail.sct.submit
  ;; declares :query).
  (let [sct (fake/effect-adapter [{:throw "socket timeout after send"}]
                                 {:outcome :completed :external-request-id "SCT-9"})
        rt (runtime {:adapters {"rail.sct.submit" sct}})]
    (payment-tree!)
    (to-sct-submit-effect! rt)
    (dispatcher/run-once! *ds* rt)

    (let [ops (map :op (fake/calls sct))]
      (is (= [:dispatch :query-status] ops)
          "exactly one dispatch, then a status query -- never a second dispatch"))
    (let [e (the-effect "TX1-A0" "rail.sct.submit")]
      (is (= "COMPLETED" (:status e)))
      (is (= "SCT-9" (:external-request-id e))))

    (kernel/drain! *ds* rt)
    (is (= "ATTEMPT_SUCCEEDED" (state-of "TX1-A0"))
        "the attempt advanced on what actually happened, not on an assumption")))

(deftest an-inconclusive-status-query-backs-off-and-asks-again
  (let [sct (fake/effect-adapter [{:throw "socket timeout"}] {:outcome :unknown})
        rt (runtime {:adapters {"rail.sct.submit" sct}})]
    (payment-tree!)
    (to-sct-submit-effect! rt)
    (dispatcher/run-once! *ds* rt)
    (let [e (the-effect "TX1-A0" "rail.sct.submit")]
      (is (= "DISPATCHED" (:status e))
          "possibly sent: the honest state, and one only the query path may claim")
      (is (true? (:uncertain (:failure-payload e)))))
    (is (= "ATTEMPT_SCT_SUBMITTING" (state-of "TX1-A0")) "the node is not advanced on a guess")

    (testing "when the external system can finally answer, the effect resolves"
      (fake/set-status! sct {:outcome :completed :external-request-id "SCT-LATE"})
      (Thread/sleep 150)
      (dispatcher/run-once! *ds* rt)
      (is (= "COMPLETED" (:status (the-effect "TX1-A0" "rail.sct.submit"))))
      (is (= 1 (count (filter #(= :dispatch (:op %)) (fake/calls sct))))
          "still only ever one dispatch"))))

(deftest a-manual-policy-effect-parks-for-an-operator
  (let [rt (runtime {})]
    (new-tree!)
    (to-store-effect! rt)
    ;; Rewrite the effect's policy to :manual to exercise the third branch.
    (db/in-tx [tx *ds*]
      (db/execute-one! tx ["UPDATE effect_instance SET uncertainty_policy = 'manual'"]))
    (let [rt (runtime {:store (fake/store-adapter [{:throw "unknown outcome"}])})]
      (dispatcher/run-once! *ds* rt)
      (let [e (the-effect "F1" "file.writeReceive")]
        (is (= "DISPATCH_FAILED" (:status e)))
        (is (true? (:requires-operator (:failure-payload e))))
        (is (true? (:uncertain (:failure-payload e)))))
      (testing "a parked effect is not retried automatically"
        (Thread/sleep 150)
        (is (empty? (dispatcher/dispatch-once! *ds* rt))
            "no next_attempt_at, so nothing claims it")))))

;; --- timeouts ------------------------------------------------------------------------

(deftest a-due-timeout-becomes-an-event
  ;; A transition may arm a timeout on the state it enters. Here the File is
  ;; WRITING_RECEIVE_FILE with the write still outstanding; a timeout that fires
  ;; drives it to failure the same way the write failing would.
  (let [rt (runtime {})]
    (new-tree!)
    (to-store-effect! rt)
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (= "CREATED" (:status (the-effect "F1" "file.writeReceive")))
        "queued, and not yet attempted")
    (db/in-tx [tx *ds*]
      (outbox/schedule-timeout! tx {:timeout-id "TO-1" :tree-id "T" :node-id "F1"
                                    :timeout-kind "transition" :event-type "ReceiveFileWriteFailed"
                                    :fire-at (an-hour-from-now)}))

    (testing "the timeout does not fire before it is due"
      (is (zero? (dispatcher/fire-due-timeouts! *ds*)))
      (is (= "WRITING_RECEIVE_FILE" (state-of "F1"))))

    (testing "once due, it fires as an ordinary event"
      (is (= 1 (dispatcher/fire-due-timeouts!
                *ds* (Timestamp/from (.plusSeconds (Instant/now) 7200)))))
      (kernel/drain! *ds* rt)
      (is (= "FILE_PERMANENT_FAILURE" (state-of "F1"))))))

(deftest an-acknowledged-effect-disarms-its-timeout
  ;; The instant send is dispatched -- a first-class "sent" status -- which
  ;; advances the attempt to ATTEMPT_INST_WAITING. Leaving ATTEMPT_INST_SENDING disarms the timeout
  ;; that state armed, so a stale timeout cannot later fire against a node that
  ;; already moved on.
  (let [rt (runtime {})]
    (payment-tree!)
    (to-inst-send-effect! rt)
    (is (= "ATTEMPT_INST_SENDING" (state-of "TX1-A0")))
    (db/in-tx [tx *ds*]
      (outbox/schedule-timeout! tx {:timeout-id "TO-1" :tree-id "T" :node-id "TX1-A0"
                                    :timeout-kind "transition" :event-type "InstPaymentRejected"
                                    :fire-at (an-hour-from-now)}))
    (dispatcher/run-once! *ds* rt)
    (kernel/drain! *ds* rt)
    (is (= "ATTEMPT_INST_WAITING" (state-of "TX1-A0")))
    (is (zero? (dispatcher/fire-due-timeouts!
                *ds* (Timestamp/from (.plusSeconds (Instant/now) 7200))))
        "a stale timeout must not fire against a node that already moved on")))

;; --- crash safety ----------------------------------------------------------------------

(deftest a-duplicate-completion-event-is-absorbed
  ;; A dispatcher that crashes after the external call and before its own commit
  ;; will re-run. The event id is derived from the effect, so the second delivery
  ;; is a no-op rather than a second transition.
  (let [store (fake/store-adapter)
        rt (runtime {:store store})]
    (new-tree!)
    (to-store-effect! rt)
    (dispatcher/run-once! *ds* rt)
    (let [e (the-effect "F1" "file.writeReceive")
          n-before (:n (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM event_log"])))]
      (db/in-tx [tx *ds*]
        (event/insert! tx {:event-id (codec/sha256
                                      (str (:effect-instance-id e) ":ReceiveFileWritten"))
                           :tree-id "T" :target-node-id "F1"
                           :event-type "ReceiveFileWritten"}))
      (is (= n-before (:n (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM event_log"]))))
          "the duplicate event was absorbed by the event_log primary key"))))

(deftest run-once-is-safe-to-call-with-nothing-to-do
  (let [rt (runtime {})]
    (new-tree!)
    (is (= {:reclaimed 0 :reports 0 :dispatched [] :timeouts 0 :effect-timeouts 0 :outbox 0}
           (dispatcher/run-once! *ds* rt)))))
