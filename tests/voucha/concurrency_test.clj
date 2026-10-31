(ns voucha.concurrency-test
  "Hierarchical constraint reads must be locked.

  Design 54: \"If a hierarchical transition depends on parent or aggregate state,
  the runtime must also lock or validate the relevant context version.\"

  The optimistic lock on `state_node` protects a node against a concurrent writer
  of *itself*. It says nothing about the ancestors a transition validated against.
  A transition that reads its parent and then commits minutes later has validated
  against a parent that may no longer exist in that state.

  Note what these tests do *not* assert. `Attempt ATTEMPT_NEW -> ATTEMPT_DEBITING`
  under a `TX_PAYING` transaction, followed by `Transaction -> TX_COMPLETED`,
  is a legal serial history: the resulting pair of states is reachable either way.
  The defect is not the end state, it is that the two transactions interleave --
  each validating against a context the other invalidates before commit. So the
  test asserts the guarantee itself: while a child holds a constraint read on its
  parent, the parent cannot move."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.handler :as handler]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.test-db :as tdb :refer [*ds*]])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private tree-id "T")

(def ^:private specs (:specs (compiler/compile-definition fbt/definition)))

(defn- planned
  "The profile's plan for a transition, as a function a test can wrap."
  [nt from on]
  (handler/plan-fn (compiler/transition-for specs nt from on) {}))

;; Two transitions run their plan as a registered function, so that a test can
;; wrap it with a latch; the definition drops the plan for exactly those.
(def ^:private wrapped #{'attempt/debit 'tx/start-validation})
(def ^:private definition
  (update fbt/definition :transitions (fn [ts] (mapv #(if (wrapped (:handler %)) (dissoc % :plan) %) ts))))

(def ^:private runtime
  {:handlers (assoc app/handlers
                    'attempt/debit (planned "Attempt" "ATTEMPT_NEW" "AttemptCommanded")
                    'tx/start-validation (planned "Transaction" "TX_NEW" "TransactionStartCommanded"))
   :event {:max-attempts 5}})

(defn- install! []
  (db/in-tx [tx *ds*]
    (let [a (compiler/compile-definition definition)]
      (artifact/insert! tx a)
      (:artifact-id a))))

(defn- given-nodes!
  "Place a tree in the state a test needs. States are set directly: these tests
  are about two transactions meeting, not about the history that led there."
  [nodes]
  (let [aid (install!)]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid
                               :root-node-id (:id (first nodes))
                               :root-node-type (:type (first nodes))})
      (doseq [{:keys [id type state parent]} (rest nodes)]
        (node/insert! tx {:tree-id tree-id :node-id id :parent-node-id parent
                          :node-type type :local-state state
                          :local-data {:child-count 0 :counters {}}}))
      (doseq [{:keys [id state]} nodes
              :let [n (node/fetch tx tree-id id)
                    kids (count (node/children tx tree-id id))]]
        (node/update-state! tx (assoc n
                                      :local-state state
                                      :local-data {:child-count kids :counters {}}))))))

(defn- submit! [event-id node-id event-type]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id event-id :tree-id tree-id
                              :target-node-id node-id :event-type event-type})))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id))))

(defn- process-with-lock-timeout
  "Process one event in a transaction that refuses to wait. Returns the outcome,
  or the exception a blocked row lock raised."
  [rt event-id]
  (try
    (db/in-tx [tx *ds*]
      (db/execute-one! tx ["SET LOCAL lock_timeout = '750ms'"])
      (kernel/process-event! tx rt event-id))
    (catch Exception e e)))

;; --- F1: a parent read by a constraint cannot move under the child ------------

(deftest a-parent-read-by-a-constraint-cannot-move-before-the-child-commits
  ;; `Attempt ATTEMPT_NEW -> ATTEMPT_DEBITING` requires its parent Transaction to
  ;; be TX_PAYING (design 14, 16). Neither ATTEMPT_NEW nor ATTEMPT_DEBITING is a
  ;; counted state, so the incidental parent CAS in `update-parent-counters!`
  ;; never fires and nothing else protects the read.
  (given-nodes! [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                 {:id "B1" :type "Batch" :state "BATCH_PROCESSING" :parent "F1"}
                 {:id "TX0" :type "Transaction" :state "TX_PAYING" :parent "B1"}
                 {:id "A1" :type "Attempt" :state "ATTEMPT_NEW" :parent "TX0"}])
  (submit! "ev-dispatch" "A1" "AttemptCommanded")
  (submit! "ev-settle" "TX0" "AttemptSucceeded")

  (let [reached-handler (promise)
        release (promise)
        paused (assoc-in runtime [:handlers 'attempt/debit]
                         (fn [ctx ev]
                           ;; Past constraint evaluation, before commit: exactly the
                           ;; window in which the audit's race commits the parent move.
                           (deliver reached-handler true)
                           (deref release 10000 :timed-out)
                           ((get-in runtime [:handlers 'attempt/debit]) ctx ev)))
        attempt (future (db/in-tx [tx *ds*] (kernel/process-event! tx paused "ev-dispatch")))]

    (is (deref reached-handler 10000 false)
        "the attempt's transition validated its parent and is mid-flight")

    (let [settle (process-with-lock-timeout runtime "ev-settle")]
      (is (instance? Exception settle)
          "AttemptSucceeded must not commit while a child holds the parent state it validated against")
      (is (re-find #"lock timeout" (str (ex-message settle)))
          "it is blocked by a row lock, not by some other failure"))

    (deliver release true)
    (is (= :transitioned (:outcome @attempt)))
    (is (= "ATTEMPT_DEBITING" (state-of "A1")))
    (is (= "TX_PAYING" (state-of "TX0"))
        "the parent move rolled back entirely"))

  (testing "once the child has committed, the parent move applies"
    (kernel/drain! *ds* runtime)
    (is (= "TX_COMPLETED" (state-of "TX0")))))

;; --- F1: the fix must not serialise siblings ----------------------------------

(deftest siblings-that-only-read-their-parent-still-run-in-parallel
  ;; Guard against the fix quietly reintroducing a bottleneck. `Transaction
  ;; TX_NEW -> TX_VALIDATING` reads its Batch (every transition does, for the
  ;; child-state allowance in design 16) but writes none of its counters. Two such
  ;; siblings must be able to sit inside their transitions at the same moment.
  (given-nodes! [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                 {:id "B1" :type "Batch" :state "BATCH_PROCESSING" :parent "F1"}
                 {:id "TX0" :type "Transaction" :state "TX_NEW" :parent "B1"}
                 {:id "TX1" :type "Transaction" :state "TX_NEW" :parent "B1"}])
  (submit! "ev-0" "TX0" "TransactionStartCommanded")
  (submit! "ev-1" "TX1" "TransactionStartCommanded")

  (let [both-inside (CountDownLatch. 2)
        overlapped (atom 0)
        rt (assoc-in runtime [:handlers 'tx/start-validation]
                     (fn [ctx ev]
                       (.countDown both-inside)
                       (when (.await both-inside 5 TimeUnit/SECONDS)
                         (swap! overlapped inc))
                       ((get-in runtime [:handlers 'tx/start-validation]) ctx ev)))
        outcomes (mapv deref
                       [(future (db/in-tx [tx *ds*] (kernel/process-event! tx rt "ev-0")))
                        (future (db/in-tx [tx *ds*] (kernel/process-event! tx rt "ev-1")))])]
    (is (= 2 @overlapped)
        "both siblings held a constraint read on the same parent concurrently")
    (is (every? #(= :transitioned (:outcome %)) outcomes))
    (is (= ["TX_VALIDATING" "TX_VALIDATING"] [(state-of "TX0") (state-of "TX1")]))))
