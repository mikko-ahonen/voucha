(ns voucha.retry-paths-test
  "A node that re-issues an effect after a transient failure must get a new
  dispatch, not the old terminal row.

  Found while designing the workload generator (test plan T0 §6.3): every
  effect's idempotency key was `[:tree-id :node-id]`, so a Transaction retrying
  `screening.check` re-created the *same* intent -- `effect/insert!` is idempotent and returned the existing,
  already-FAILED row -- and nothing dispatched. The node waited forever for a
  completion that could never come. No test drove a same-node retry.

  The fix lets an idempotency key name request fields, so a retrying handler can
  make each cycle a distinct intent (`:attempt`), while the key still protects
  against a *replayed* transition re-creating the same cycle's request."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.invariants :as inv]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private tree-id "T")
(def ^:private max-steps 60)

(defn- ok [] {:outcome :completed})

(defn- rt [overrides]
  (runtime/runtime
   {:handlers app/handlers
    :effect-adapters (merge {"screening.check" (fake/effect-adapter [(ok)])
                             "account.debit"   (fake/effect-adapter [(ok)])
                             "account.reverse" (fake/effect-adapter [(ok)])
                             "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])
                             "rail.sct.submit" (fake/effect-adapter [(ok)])}
                            overrides)
    :store-adapters {"file.writeReceive" (fake/store-adapter) "file.writeResult" (fake/store-adapter)}
    :config {:dispatcher {:batch-size 25 :backoff-ms 20 :max-attempts 3} :event {:max-attempts 3}}}))

(defn- state-of [nid] (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id nid))))

(defn- drive!
  "Drain, dispatch, deliver instant-rail callbacks, drain -- until the file is terminal.
  Returns the file's state, or :stuck if it never gets there."
  [r]
  (loop [n 0]
    (kernel/drain! *ds* r)
    (dispatcher/run-once! *ds* r)
    (doseq [row (db/in-tx [tx *ds*] (db/execute! tx ["SELECT effect_instance_id FROM effect_instance
                                                       WHERE effect_name = 'rail.inst.send' AND status = 'DISPATCHED'"]))]
      (dispatcher/deliver-callback! *ds* {:effect-instance-id (:effect_instance_id row)
                                          :outcome :completed :payload {:rail-ref "INST"}}))
    (kernel/drain! *ds* r)
    (let [s (state-of "F1")]
      (cond (contains? #{"FILE_COMPLETED" "FILE_PERMANENT_FAILURE"} s) s
            (> n max-steps) :stuck
            :else (do (Thread/sleep 25) (recur (inc n)))))))

(defn- one-sct-file! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/submit-event! tx {:event-id "received" :tree-id tree-id :target-node-id "F1"
                                :event-type "FileReceived"
                                :payload {:name "f" :batches [{:batch-id "B1"
                                                               :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "SCT"}]}]}}))))

(deftest a-transaction-whose-first-attempt-is-rejected-is-retried-and-completes
  (one-sct-file!)
  (let [sct (fake/effect-adapter [{:outcome :failed :payload {:error "AC01: incorrect account number"}} (ok)])
        r (rt {"rail.sct.submit" sct})]
    (is (= "FILE_COMPLETED" (drive! r)) "the retry cycle must reach a terminal state")
    (is (= "TX_COMPLETED" (state-of "B1-T1")))
    (testing "two attempts: the first was rejected and reversed, the second succeeded"
      (is (= "ATTEMPT_FAILED" (state-of "B1-T1-A0")))
      (is (= "ATTEMPT_SUCCEEDED" (state-of "B1-T1-A1")))
      (is (= 2 (count (filter #(= :dispatch (:op %)) (fake/calls sct))))))
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers app/handlers))))))

(deftest a-transient-screening-failure-is-retried-and-clears
  (one-sct-file!)
  (let [screening (fake/effect-adapter [{:outcome :failed :payload {:error "screening service busy"}} (ok)])
        r (rt {"screening.check" screening})]
    (is (= "FILE_COMPLETED" (drive! r)) "the retry cycle must reach a terminal state")
    (is (= "TX_COMPLETED" (state-of "B1-T1")))
    (is (= 2 (count (filter #(= :dispatch (:op %)) (fake/calls screening)))) "screened twice")
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers app/handlers))))))

(deftest a-permanently-failing-rail-exhausts-its-retries-and-fails-the-transaction
  (one-sct-file!)
  (let [sct (fake/effect-adapter [{:outcome :failed :payload {:error "rejected"}}])
        r (rt {"rail.sct.submit" sct})]
    (is (= "FILE_COMPLETED" (drive! r)))
    (is (= "TX_FAILED" (state-of "B1-T1")))
    (is (= 4 (count (filter #(= :dispatch (:op %)) (fake/calls sct))))
        "one attempt plus the handler's three retries, then it gives up")
    (is (every? #(= "ATTEMPT_FAILED" (state-of (str "B1-T1-A" %))) (range 4)) "every attempt was reversed and failed")
    (is (= "BATCH_COMPLETED" (state-of "B1")) "a batch completes even when its transaction failed")
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers app/handlers))))))
