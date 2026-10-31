(ns voucha.stranded-effect-test
  "Test plan T0 §6.2: an effect stranded in DISPATCHING is reclaimed by lease and
  resolved by its uncertainty policy (decision D8).

  DISPATCHING is the honest state for 'we may have sent it'. A dispatcher that
  dies between the adapter call and recording the outcome leaves the effect
  there, and -- before this fix -- nothing ever looked at it again: not
  `claim-dispatchable!` (CREATED/DISPATCH_FAILED), not `claim-reconcilable!`
  (DISPATCHED), not `claim-timed-out!` (DISPATCHED/ACKNOWLEDGED). The node made
  no false progress, but the work was lost until an operator noticed. Oracle
  invariant I5 is what notices now; this is the machinery that resolves it.

  The lease is `updated_at`: claiming sets it, so a DISPATCHING row older than
  the lease belongs to a dead dispatcher. Reclaiming never re-dispatches blindly;
  it hands the effect to the same policy resolution an adapter throw would get.

  Each test stages the real crash: a Fault-tier `crash!` at `:effect/after-call`
  aborts the dispatcher after the adapter ran and before anything was recorded --
  exactly a dispatcher dying mid-call -- then ages the row past the lease."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.intercept :as intercept]
            [voucha.invariants :as inv]
            [voucha.kernel :as kernel]
            [voucha.metrics :as metrics]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.node :as node]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

(def ^:private lease-ms (* 60 1000))
(def ^:private backoff-ms 60)

(defn- runtime
  [{:keys [adapters store definition] :or {store (fake/store-adapter)}}]
  {:handlers app/handlers
   :event {:max-attempts 3}
   :dispatcher {:batch-size 10 :backoff-ms backoff-ms :max-attempts 3 :lease-ms lease-ms}
   :effect-adapters (merge {"account.debit"   (fake/effect-adapter [{:outcome :completed}])
                            "account.reverse" (fake/effect-adapter [{:outcome :completed}])
                            "rail.sct.submit" (fake/effect-adapter [{:outcome :completed}])}
                           adapters)
   :store-adapters {"file.writeReceive" store}})

(defn- new-tree!
  "File -> Batch -> Transaction, built directly (as effect_lifecycle_test does)
  with the batch already paying, so `pay!` can drive an Attempt down a rail."
  ([] (new-tree! fbt/definition))
  ([definition]
   (let [aid (db/in-tx [tx *ds*]
               (let [a (compiler/compile-definition definition)]
                 (artifact/insert! tx a) (:artifact-id a)))]
     (db/in-tx [tx *ds*]
       (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1" :root-node-type "File"})
       (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
       (kernel/add-child! tx {:tree-id "T" :parent-node-id "B1" :node-id "TX1" :node-type "Transaction"})
       (node/update-state! tx (assoc (node/fetch tx "T" "B1") :local-state "BATCH_PROCESSING")))
     aid)))

(defn- send! [rt node-id event-type payload]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str (gensym "ev")) :tree-id "T"
                              :target-node-id node-id :event-type event-type :payload payload}))
  (kernel/drain! *ds* rt))

(defn- pay!
  "TX1 is screened and carries its record; command it to pay. The profile creates
  `TX1-A0`, whose `account.debit` is then CREATED."
  [rt payment-type amount]
  (db/in-tx [tx *ds*]
    (let [n (node/fetch tx "T" "TX1")
          b (node/fetch tx "T" "B1")]
      (node/update-state! tx (assoc n :local-state "TX_SCREENED"
                                    :local-data (assoc (:local-data n) :payment-type payment-type :amount amount)))
      ;; TX_SCREENED is a counted state: the batch's counter must say so, or the
      ;; invariant oracle (I3) will rightly object when the transaction leaves it.
      (node/update-state! tx (assoc-in b [:local-data :counters :tx-ready] 1))))
  (send! rt "TX1" "TransactionPayCommanded" {}))

(defn- state-of [node-id] (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))
(defn- the-effect [owner name*]
  (first (filter #(= name* (:effect-name %)) (db/in-tx [tx *ds*] (effect/by-owner tx "T" owner)))))
(defn- attempt-statuses [e]
  (mapv :status (db/in-tx [tx *ds*] (effect/attempts tx (:effect-instance-id e)))))

(defn- strand!
  "The dispatcher dies after the adapter call: the effect is left DISPATCHING with
  nothing recorded. Then age it past the lease."
  [rt effect-name]
  (debug/crash! :effect/after-call :match #(= effect-name (get-in % [:effect :effect-name])))
  (is (thrown? clojure.lang.ExceptionInfo (dispatcher/dispatch-once! *ds* rt)))
  (intercept/reset!)
  (db/in-tx [tx *ds*]
    (db/execute! tx ["UPDATE effect_instance SET updated_at = now() - interval '1 hour'
                      WHERE effect_name = ? AND status = ?" effect-name effect/status-dispatching])))

(defn- stranded [] (db/in-tx [tx *ds*] (inv/no-stranded-dispatching tx :bound-ms lease-ms)))

;; --- retry-safe: send again under the idempotency key -------------------------------

(deftest a-stranded-retry-safe-effect-is-re-dispatched
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime {:store store})]
    (send! rt "F1" "FileReceived" {:name "f" :batches []})
    (strand! rt "file.writeReceive")
    (let [e (the-effect "F1" "file.writeReceive")]
      (is (= "DISPATCHING" (:status e)) "the honest state after a mid-call death")
      (is (= 1 (count (fake/writes store))) "the write happened; it was never recorded")
      (is (= 1 (count (stranded))) "the oracle sees it"))

    (testing "reclaim resolves it as an uncertain outcome: a retry is scheduled"
      (let [r (dispatcher/run-once! *ds* rt)
            e (the-effect "F1" "file.writeReceive")]
        (is (= 1 (:reclaimed r)))
        (is (= "DISPATCH_FAILED" (:status e)))
        (is (= ["ERROR"] (attempt-statuses e)) "the lost call is on the record")
        (is (empty? (stranded)))))

    (testing "and the retry completes the work -- the write log makes the repeat a no-op"
      (Thread/sleep (* 2 backoff-ms))
      (dispatcher/run-once! *ds* rt)
      (kernel/drain! *ds* rt)
      (is (= "COMPLETED" (:status (the-effect "F1" "file.writeReceive"))))
      (is (= 2 (count (fake/writes store))) "sent twice, as retry-safe permits")
      (is (= "FILE_PROCESS_BATCH" (state-of "F1"))
          "the file decided: the fixture's batch B1 is still open, so it waits for it")
      (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :dispatching-bound-ms lease-ms)))))))

;; --- query: ask, never resend ----------------------------------------------------------

(deftest a-stranded-query-effect-is-resolved-by-asking-never-by-resending
  (new-tree!)
  (let [sct (fake/effect-adapter [{:outcome :completed :external-request-id "SCT-1"}]
                                 {:outcome :completed :external-request-id "SCT-1"
                                  :payload {:rail-ref "SCT-OK"}})
        rt (runtime {:adapters {"rail.sct.submit" sct}})]
    (pay! rt "SCT" 200)
    (dispatcher/run-once! *ds* rt)          ; account.debit completes
    (kernel/drain! *ds* rt)                 ; -> ATTEMPT_SCT_SUBMITTING
    (is (= "ATTEMPT_SCT_SUBMITTING" (state-of "TX1-A0")))
    (strand! rt "rail.sct.submit")
    (is (= [:dispatch] (mapv :op (fake/calls sct))) "one real send went out")

    (testing "reclaim asks the rail what happened"
      (let [r (dispatcher/run-once! *ds* rt)]
        (is (= 1 (:reclaimed r))))
      (is (= [:dispatch :query-status] (mapv :op (fake/calls sct)))
          "exactly one dispatch ever: a query, not a resend (D8, I7)")
      (is (= "COMPLETED" (:status (the-effect "TX1-A0" "rail.sct.submit")))))

    (testing "the answer drives the node like any outcome"
      (kernel/drain! *ds* rt)
      (is (= "ATTEMPT_SUCCEEDED" (state-of "TX1-A0")))
      (let [r (db/in-tx [tx *ds*] (inv/check-all tx :dispatching-bound-ms lease-ms))]
        (is (:ok? r) (pr-str (:violations r)))))))

(deftest a-stranded-query-effect-with-an-inconclusive-answer-waits-for-reconciliation
  (new-tree!)
  (let [sct (fake/effect-adapter [{:outcome :completed}] {:outcome :unknown})
        rt (runtime {:adapters {"rail.sct.submit" sct}})]
    (pay! rt "SCT" 200)
    (dispatcher/run-once! *ds* rt)
    (kernel/drain! *ds* rt)
    (strand! rt "rail.sct.submit")
    (dispatcher/run-once! *ds* rt)
    (let [e (the-effect "TX1-A0" "rail.sct.submit")]
      (is (= "DISPATCHED" (:status e)) "parked where only the reconciliation path can claim it")
      (is (some? (:next-attempt-at (db/in-tx [tx *ds*]
                                     (let [r (db/execute-one! tx ["SELECT next_attempt_at FROM effect_instance WHERE effect_instance_id = ?"
                                                                   (:effect-instance-id e)])]
                                       {:next-attempt-at (:next_attempt_at r)})))))
      (is (= [:dispatch :query-status] (mapv :op (fake/calls sct))))
      (is (empty? (stranded))))))

;; --- manual: a human decides ------------------------------------------------------------

(deftest a-stranded-manual-effect-is-parked-for-an-operator
  (let [manual-def (assoc-in fbt/definition [:effects "account.debit" :uncertainty-policy] :manual)]
    (new-tree! manual-def)
    (let [rt (runtime {})]
      (pay! rt "SCT" 200)
      (strand! rt "account.debit")
      (dispatcher/run-once! *ds* rt)
      (let [e (the-effect "TX1-A0" "account.debit")]
        (is (= "DISPATCH_FAILED" (:status e)))
        (is (true? (:requires-operator (:failure-payload e))))
        (is (= ["ERROR"] (attempt-statuses e)))
        (is (= [(:effect-instance-id e)]
               (mapv :effect-instance-id (db/in-tx [tx *ds*] (metrics/uncertain-effects tx))))
            "it is where an operator looks for uncertain work")
        (is (empty? (stranded)))))))

;; --- the lease ---------------------------------------------------------------------------------

(deftest an-effect-within-its-lease-is-a-call-in-progress-and-is-left-alone
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime {:store store})]
    (send! rt "F1" "FileReceived" {:name "f" :batches []})
    (debug/crash! :effect/after-call)
    (is (thrown? clojure.lang.ExceptionInfo (dispatcher/dispatch-once! *ds* rt)))
    (intercept/reset!)
    ;; No ageing: as far as anyone can tell, a dispatcher is mid-call right now.
    (let [r (dispatcher/run-once! *ds* rt)]
      (is (= 0 (:reclaimed r)))
      (is (= "DISPATCHING" (:status (the-effect "F1" "file.writeReceive"))))
      (is (= 1 (count (fake/writes store))) "nothing was resent under a live lease"))))
