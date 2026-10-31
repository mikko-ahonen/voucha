(ns voucha.invariants-test
  "The invariant oracle (test plan §3) must detect every violation it claims to.

  Each invariant gets two proofs: a healthy, fully driven tree produces no
  violation, and a database corrupted by hand -- raw SQL, bypassing every kernel
  code path -- produces exactly that invariant's violation, naming the culprit.
  A checker that cannot fail is not a checker."
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
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

;; --- a healthy, fully paid file (the example's driver, reduced) -----------------------

(def ^:private tree-id "T")

(defn- rt []
  (runtime/runtime
   {:handlers app/handlers
    :effect-adapters {"screening.check"  (fake/effect-adapter [{:outcome :completed}])
                      "account.debit"    (fake/effect-adapter [{:outcome :completed}])
                      "account.reverse"  (fake/effect-adapter [{:outcome :completed}])
                      "rail.inst.send"   (fake/effect-adapter [{:outcome :dispatched}])
                      "rail.sct.submit"  (fake/effect-adapter [{:outcome :completed
                                                               :external-request-id "SCT-1"}])}
    :store-adapters {"file.writeReceive" (fake/store-adapter)
                     "file.writeResult"  (fake/store-adapter)}
    :config {:dispatcher {:batch-size 25 :backoff-ms 30 :max-attempts 3}
             :event {:max-attempts 3}}}))

(defn- deliver-inst-callbacks! [ds]
  (doseq [row (db/in-tx [tx ds]
                (db/execute! tx ["SELECT effect_instance_id FROM effect_instance
                                  WHERE effect_name = 'rail.inst.send' AND status = 'DISPATCHED'"]))]
    (dispatcher/deliver-callback! ds {:effect-instance-id (:effect_instance_id row)
                                      :outcome :completed :payload {:rail-ref "INST-REF"}})))

(defn- paid-file!
  "Drive a two-rail file to FILE_COMPLETED. Nodes: F1, B1, B1-T1 (instant) and
  B1-T2 (SCT), attempts <tx>-A0."
  []
  (let [r (rt)
        aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/submit-event! tx {:event-id "file-received" :tree-id tree-id :target-node-id "F1"
                                :event-type "FileReceived"
                                :payload {:name "f" :batches [{:batch-id "B1"
                                                               :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "INST"}
                                                                              {:tx-id "B1-T2" :amount 200 :payment-type "SCT"}]}]}}))
    (loop [n 0]
      (when (> n 200) (throw (ex-info "did not settle" {})))
      (kernel/drain! *ds* r)
      (dispatcher/run-once! *ds* r)
      (deliver-inst-callbacks! *ds*)
      (kernel/drain! *ds* r)
      (let [s (:local_state (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT local_state FROM state_node WHERE node_id = 'F1'"])))]
        (when-not (= "FILE_COMPLETED" s) (recur (inc n)))))
    r))

(defn- sql! [& stmt] (db/in-tx [tx *ds*] (db/execute! tx (vec stmt))))
(defn- check ([] (check nil)) ([handlers] (db/in-tx [tx *ds*] (inv/check-all tx :handlers handlers))))
(defn- only
  "Violations of one invariant, from a per-invariant check."
  [f & args] (db/in-tx [tx *ds*] (apply f tx args)))
(defn- fired [result] (set (map :invariant (:violations result))))

;; --- the healthy case ------------------------------------------------------------------------

(deftest a-fully-paid-file-satisfies-every-invariant
  (paid-file!)
  (let [r (check app/handlers)]
    (is (:ok? r) (pr-str (:violations r)))
    (is (= inv/invariant-ids (:checked r)) "all ten were checked, including replay")
    (is (empty? (:skipped r)))
    (is (= ["invariants OK: I1 I2 I3 I4 I5 I6 I7 I8 I9 I10 I11 hold"] (inv/report r)))))

(deftest without-handlers-replay-is-skipped-not-passed
  (paid-file!)
  (let [r (check)]
    (is (:ok? r))
    (is (= [:I10] (:skipped r)))
    (is (not (contains? (set (:checked r)) :I10)) "an unrun check is never reported as holding")))

;; --- each invariant detects its own violation ----------------------------------------------

(deftest I1-detects-an-undeclared-state
  (paid-file!)
  ;; The root has no parent and no one counts it, so only I1 can see this.
  (sql! "UPDATE state_node SET local_state = 'NONSENSE' WHERE node_id = 'F1'")
  (let [vs (only inv/declared-states)]
    (is (= 1 (count vs)))
    (is (re-find #"F1 is in NONSENSE, which File does not declare" (:detail (first vs)))))
  (is (= #{:I1} (fired (check)))))

(deftest I2-detects-a-live-child-under-a-terminal-parent
  (paid-file!)
  ;; A declared state, so I1 is silent; the Transaction has no counters, so I3
  ;; is silent. Only the child-states-allowed rule of TX_COMPLETED sees it.
  (sql! "UPDATE state_node SET local_state = 'ATTEMPT_INST_WAITING' WHERE node_id = 'B1-T1-A0'")
  (let [vs (only inv/child-states-allowed)]
    (is (= 1 (count vs)))
    (is (re-find #"B1-T1-A0 is ATTEMPT_INST_WAITING under Transaction TX_COMPLETED" (:detail (first vs)))))
  ;; the definition's own paid-means-one-success sees the same row: a completed
  ;; transaction whose only attempt has not succeeded
  (is (= #{:I2 :paid-means-one-success} (fired (check)))))

(deftest I3-detects-a-counter-that-disagrees-with-its-children
  (paid-file!)
  (sql! "UPDATE state_node SET local_data = jsonb_set(local_data, '{counters,batches-completed}', '5')
          WHERE node_id = 'F1'")
  (let [vs (only inv/counters)]
    (is (= 1 (count vs)))
    (is (re-find #"counter batches-completed is 5, live count is 1" (:detail (first vs)))))
  (is (= #{:I3} (fired (check))))
  (testing "and a :child-count that lies"
    (sql! "UPDATE state_node SET local_data = jsonb_set(local_data, '{counters,batches-completed}', '1')
            WHERE node_id = 'F1'")
    (sql! "UPDATE state_node SET local_data = jsonb_set(local_data, '{child-count}', '7') WHERE node_id = 'B1'")
    (is (re-find #":child-count is 7, has 2 children" (:detail (first (only inv/counters)))))))

(deftest I4-detects-a-live-effect-owned-by-a-terminal-node
  (paid-file!)
  (sql! "UPDATE effect_instance SET status = 'DISPATCHED' WHERE effect_name = 'rail.sct.submit'")
  (let [vs (only inv/no-live-effect-under-terminal-node)]
    (is (= 1 (count vs)))
    (is (re-find #"\(rail.sct.submit\) is DISPATCHED under terminal node B1-T2-A0" (:detail (first vs)))))
  (is (= #{:I4} (fired (check)))))

(deftest I5-detects-an-effect-stranded-in-dispatching
  (paid-file!)
  (sql! "UPDATE effect_instance SET status = 'DISPATCHING', updated_at = now() - interval '1 hour'
          WHERE effect_name = 'screening.check' AND owner_node_id = 'B1-T1'")
  (testing "within the bound it is merely in flight"
    (is (empty? (only inv/no-stranded-dispatching :bound-ms (* 2 60 60 1000)))))
  (testing "past the bound it is stranded"
    (let [vs (only inv/no-stranded-dispatching)]
      (is (= 1 (count vs)))
      (is (re-find #"\(screening.check\) has been DISPATCHING since" (:detail (first vs))))))
  ;; Its owner is terminal too, so I4 rightly fires alongside.
  (is (= #{:I4 :I5} (fired (check)))))

(deftest I6-detects-lost-duplicated-and-orphaned-checkpoints
  (paid-file!)
  (let [row (db/in-tx [tx *ds*]
              (db/execute-one! tx ["SELECT * FROM node_transition_log
                                     WHERE node_id = 'F1' AND handler_name = 'file/received'"]))]
    (testing "a processed event with no checkpoint is a lost write"
      (sql! "DELETE FROM node_transition_log WHERE transition_id = ?" (:transition_id row))
      (let [vs (only inv/event-checkpoint-agreement)]
        (is (= 1 (count vs)))
        (is (re-find #"event file-received is PROCESSED but has no checkpoint" (:detail (first vs)))))
      (is (= #{:I6} (fired (check)))))
    (testing "two checkpoints for one event is the double-processing race"
      (let [orig (:transition_id (db/in-tx [tx *ds*]
                                   (db/execute-one! tx ["SELECT transition_id FROM node_transition_log
                                                          WHERE node_id = 'F1' AND handler_name = 'file/start-batches'"])))]
        (db/in-tx [tx *ds*]
          (doseq [id ["dup-1" "dup-2"]]
            (db/execute! tx ["INSERT INTO node_transition_log
                               (transition_id, tree_id, node_id, artifact_id, triggering_event_id, from_state,
                                to_state, handler_name, state_patch)
                              SELECT ?, tree_id, node_id, artifact_id, triggering_event_id, from_state,
                                     to_state, handler_name, state_patch
                                FROM node_transition_log WHERE transition_id = ?"
                             id orig]))))
      (is (some #(re-find #"event .* produced 3 checkpoints; processed twice" (:detail %))
                (only inv/event-checkpoint-agreement))))
    (testing "a checkpoint citing an unprocessed event is an orphan"
      (sql! "DELETE FROM node_transition_log WHERE transition_id IN ('dup-1','dup-2')")
      (sql! "UPDATE event_log SET status = 'RECEIVED' WHERE event_id = ?" (:triggering_event_id row))
      ;; `row` itself was deleted above; re-add a fresh citing checkpoint.
      (db/in-tx [tx *ds*]
        (db/execute! tx ["INSERT INTO node_transition_log
                           (transition_id, tree_id, node_id, artifact_id, triggering_event_id, from_state,
                            to_state, handler_name, state_patch)
                          VALUES ('orphan', ?, 'F1', ?, ?, 'FILE_NEW', 'WRITING_RECEIVE_FILE', 'file/received', '[]')"
                         (:tree_id row) (:artifact_id row) (:triggering_event_id row)]))
      (is (some #(re-find #"checkpoint orphan cites event file-received, which is RECEIVED" (:detail %))
                (only inv/event-checkpoint-agreement))))))

(deftest I7-detects-a-query-effect-dispatched-twice
  (paid-file!)
  (db/in-tx [tx *ds*]
    (db/execute! tx ["INSERT INTO effect_attempt (attempt_id, effect_instance_id, attempt_no, status)
                      SELECT 'second-send', effect_instance_id, 2, 'completed'
                        FROM effect_instance WHERE effect_name = 'rail.sct.submit'"]))
  (let [vs (only inv/single-dispatch)]
    (is (= 1 (count vs)))
    (is (re-find #"\(rail.sct.submit, query\) was dispatched 2 times" (:detail (first vs)))))
  (is (= #{:I7} (fired (check))))
  (testing "a cancellation record is a resolution, not a second call"
    (sql! "UPDATE effect_attempt SET status = 'CANCELLED' WHERE attempt_id = 'second-send'")
    (is (empty? (only inv/single-dispatch)))))

(deftest I8-detects-duplicate-idempotency-keys-even-without-the-index
  ;; The scenario this guards: a migration drops the unique index and nothing
  ;; else notices. Drop it, duplicate a key, assert; restore it in `finally`.
  (paid-file!)
  (let [{:keys [indexname indexdef]}
        (db/in-tx [tx *ds*]
          (db/execute-one! tx ["SELECT indexname, indexdef FROM pg_indexes
                                 WHERE tablename = 'effect_instance' AND indexdef LIKE '%UNIQUE%idempotency_key%'"]))]
    (is (some? indexname) "the schema's unique index exists to begin with")
    (try
      (sql! (str "DROP INDEX " indexname))
      (db/in-tx [tx *ds*]
        (db/execute! tx ["INSERT INTO effect_instance
                           (effect_instance_id, tree_id, owner_node_id, effect_kind, effect_name, effect_version,
                            artifact_id, status, uncertainty_policy, idempotency_key, correlation_key,
                            request_payload, request_payload_hash)
                          SELECT 'twin', tree_id, owner_node_id, effect_kind, effect_name, effect_version,
                                 artifact_id, status, uncertainty_policy, idempotency_key, correlation_key,
                                 request_payload, request_payload_hash
                            FROM effect_instance WHERE effect_name = 'screening.check' AND owner_node_id = 'B1-T1'"]))
      (let [vs (only inv/unique-idempotency-keys)]
        (is (= 1 (count vs)))
        (is (re-find #"2 effects share idempotency key screening.check:v1:T:B1-T1" (:detail (first vs)))))
      (is (contains? (fired (check)) :I8))
      (finally
        (sql! "DELETE FROM effect_instance WHERE effect_instance_id = 'twin'")
        (sql! indexdef)))))

(deftest I9-detects-a-stale-pending-event
  (paid-file!)
  (db/in-tx [tx *ds*]
    (db/execute! tx ["INSERT INTO event_log (event_id, tree_id, target_node_id, event_type, status, received_at)
                      VALUES ('forgotten', 'T', 'F1', 'FileReceived', 'RECEIVED', now() - interval '1 hour')"]))
  (testing "within the bound it is merely pending"
    (is (empty? (only inv/no-stale-events :bound-ms (* 2 60 60 1000)))))
  (let [vs (only inv/no-stale-events)]
    (is (= 1 (count vs)))
    (is (re-find #"event forgotten \(FileReceived\) has waited in RECEIVED since" (:detail (first vs)))))
  (is (= #{:I9} (fired (check)))))

(deftest I10-detects-a-checkpoint-replay-cannot-reproduce
  (paid-file!)
  ;; The log claims the handler went somewhere it did not. Replaying the recorded
  ;; handler against the recorded inputs says otherwise.
  (sql! "UPDATE node_transition_log SET to_state = 'FILE_PERMANENT_FAILURE'
          WHERE node_id = 'F1' AND handler_name = 'file/received'")
  (let [vs (only inv/replay-fidelity app/handlers)]
    (is (= 1 (count vs)))
    (is (re-find #"T/F1 replayed file/received" (:detail (first vs))))
    (is (re-find #"FILE_PERMANENT_FAILURE" (:detail (first vs)))))
  (is (= #{:I10} (fired (check app/handlers))))
  (testing "the report names it"
    (is (re-find #"INVARIANT VIOLATIONS: 1" (first (inv/report (check app/handlers)))))))

(deftest I11-detects-a-dead-tree
  ;; A file waiting on its receive-file store effect is alive: the effect is live.
  (let [r (rt)]
    (db/in-tx [tx *ds*]
      (let [a (compiler/compile-definition fbt/definition)]
        (artifact/insert! tx a)
        (kernel/create-tree! tx {:tree-id tree-id :artifact-id (:artifact-id a) :root-node-id "F1" :root-node-type "File"})
        (kernel/submit-event! tx {:event-id "received" :tree-id tree-id :target-node-id "F1"
                                  :event-type "FileReceived" :payload {:name "f" :batches []}})))
    (kernel/drain! *ds* r)
    (is (empty? (only inv/no-dead-trees :bound-ms 0)) "a live effect means the tree can still move"))
  ;; The effect ends terminal without its completion ever reaching the node -- the
  ;; shape the chaos harness found -- and nothing else is pending. Dead.
  (sql! "UPDATE effect_instance SET status = 'FAILED' WHERE effect_name = 'file.writeReceive'")
  (sql! "UPDATE state_node SET updated_at = now() - interval '1 hour' WHERE tree_id = ?" tree-id)
  (testing "within the bound it is merely quiet"
    (is (empty? (only inv/no-dead-trees :bound-ms (* 2 60 60 1000)))))
  (let [vs (only inv/no-dead-trees)]
    (is (= 1 (count vs)))
    (is (re-find #"tree T is dead: root F1 is WRITING_RECEIVE_FILE, nothing pending or in flight" (:detail (first vs)))))
  (is (= #{:I11} (fired (check))))
  (testing "a parked event is a declared wait for an operator, not death"
    (db/in-tx [tx *ds*]
      (db/execute! tx ["INSERT INTO event_log (event_id, tree_id, target_node_id, event_type, status)
                        VALUES ('parked', ?, 'F1', 'ReceiveFileWritten', 'PARKED')" tree-id]))
    (is (empty? (only inv/no-dead-trees)))))

;; --- the definition's business invariants -------------------------------------------------

(deftest a-paid-file-satisfies-the-definitions-business-invariants
  (paid-file!)
  (is (empty? (inv/business-invariants *ds*)) "the five SEPA properties hold of the rows"))

(deftest a-business-invariant-detects-a-completed-transaction-without-a-success
  (paid-file!)
  ;; TX_COMPLETED allows a failed attempt (I2 is silent) and a transaction has no
  ;; counters (I3 is silent): only paid-means-one-success sees it.
  (sql! "UPDATE state_node SET local_state = 'ATTEMPT_FAILED' WHERE node_id = 'B1-T1-A0'")
  (let [vs (inv/business-invariants *ds*)]
    (is (= [:paid-means-one-success] (map :invariant vs)))
    (is (= "B1-T1" (:node-id (first vs))))
    (is (re-find #"fails for tx = B1-T1 \(Transaction TX_COMPLETED\)" (:detail (first vs)))))
  (is (= #{:paid-means-one-success} (fired (check)))))

