(ns voucha.integration-test
  "Hardening.

  Everything here is about what happens when the world misbehaves -- concurrent
  workers, crashed processes, wide trees, artifacts that moved on. The unit
  tests prove the kernel is correct when it is left alone; these prove it is
  correct when it is not.

  Reframed onto the reference profile: the aggregate that concurrent
  siblings contend on is a batch's `tx-ready` counter (a screened transaction
  folds itself in), the uncertain external call is a payment rail (a :query
  effect resolved by asking, never by resending), and the store effect whose
  completion must survive a restart is the file's receive-file write."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.handler :as handler]
            [voucha.fake :as fake]
            [voucha.kernel :as kernel]
            [voucha.metrics]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def tree-id "T")

(defn- completed [] {:outcome :completed})

(defn- rt
  "A runtime whose rails all succeed. `overrides` (`:effect-adapters`,
  `:store-adapters`, `:handlers`) replaces individual pieces -- e.g. to make a
  rail crash or a store go down."
  ([] (rt {}))
  ([overrides]
   (runtime/runtime
    {:handlers (or (:handlers overrides) app/handlers)
     :effect-adapters (merge {"screening.check" (fake/effect-adapter [(completed)])
                              "account.debit"   (fake/effect-adapter [(completed)])
                              "account.reverse" (fake/effect-adapter [(completed)])
                              ;; SCT Inst is async; SCT is a synchronous submission.
                              "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])
                              "rail.sct.submit" (fake/effect-adapter [{:outcome :completed
                                                                      :external-request-id "SCT-1"}])}
                             (:effect-adapters overrides))
     :store-adapters (merge {"file.writeReceive" (fake/store-adapter)
                             "file.writeResult"  (fake/store-adapter)}
                            (:store-adapters overrides))
     :config {:dispatcher {:batch-size 25 :backoff-ms 30 :max-attempts 3}
              :event {:max-attempts 5}}})))

(defn- as-code
  "The profile with the named transitions' plans dropped, so that a test can
  register a code handler for them and see it run."
  [& syms]
  (update fbt/definition :transitions
          (fn [ts] (mapv #(if ((set syms) (:handler %)) (dissoc % :plan) %) ts))))

(defn- install!
  ([] (install! fbt/definition))
  ([d]
   (db/in-tx [tx *ds*]
     (let [a (compiler/compile-definition d)]
       (artifact/insert! tx a) (:artifact-id a)))))

(defn- new-tree!
  ([] (new-tree! fbt/definition))
  ([d]
  (let [aid (install! d)]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"
                               :profile-name fbt/profile-name :profile-version fbt/profile-version}))
    aid)))

(defn- receive-file! [batches]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str (gensym "fr")) :tree-id tree-id :target-node-id "F1"
                              :event-type "FileReceived"
                              :payload {:name "f" :batches batches}})))

(defn- submit! [node-id event-type]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str (gensym "ev")) :tree-id tree-id
                              :target-node-id node-id :event-type event-type})))

(defn- send! [r node-id event-type]
  (submit! node-id event-type)
  (kernel/drain! *ds* r))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id))))

(defn- data-of [node-id]
  (:local-data (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id))))

(defn- counters-of [node-id]
  (get-in (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id)) [:local-data :counters]))

(defn- handler-count [node-id handler-name]
  (count (filter #(= handler-name (:handler-name %))
                 (db/in-tx [tx *ds*] (tlog/history tx tree-id node-id)))))

(defn- batch-with-transactions!
  "Batch B1 with `n` transactions parked in TX_SCREENING -- each has validated and
  is now awaiting its sanctions screening. Delivering `TransactionScreeningCleared`
  to a transaction folds it into the batch's `tx-ready` counter, the aggregate
  every concurrency test here contends on. Driven purely by events, so nothing
  but the pending screenings is left outstanding."
  [r n]
  (new-tree!)
  (let [tx-ids (mapv #(str "TX" %) (range n))
        txns (mapv (fn [id] {:tx-id id :amount 100 :payment-type "SCT"}) tx-ids)]
    (receive-file! [{:batch-id "B1" :transactions txns}])
    (kernel/drain! *ds* r)              ; F1 -> WRITING_RECEIVE_FILE
    (send! r "F1" "ReceiveFileWritten") ; F1 -> FILE_PROCESS_BATCH, B1 validates and prepares;
    tx-ids))                            ; children cascade to TX_SCREENING

;; --- concurrency -------------------------------------------------------------------

(deftest concurrent-siblings-serialise-through-the-parent-lock
  ;; Two transactions clear screening at the same instant. Each folds itself into
  ;; the batch's counters, so both contend on the batch row. One must lose, retry,
  ;; and land -- never be silently dropped.
  (let [r (rt)
        tx-ids (batch-with-transactions! r 2)]
    (doseq [t tx-ids] (submit! t "TransactionScreeningCleared"))

    (let [results (mapv deref
                        [(future (kernel/drain! *ds* r))
                         (future (kernel/drain! *ds* r))])]
      (is (every? some? results)))

    ;; Any event that lost the race stays RECEIVED and is picked up on the next pass.
    (dotimes [_ 4] (kernel/drain! *ds* r))

    (is (every? #(= "TX_PAYING" (state-of %)) tx-ids)
        "both children were accounted for and advanced past the fold")
    (testing "each child folded in exactly once"
      (doseq [t tx-ids] (is (= 1 (handler-count t "tx/screening-cleared")))))
    (is (= "BATCH_PROCESSING" (state-of "B1"))
        "the aggregate rule fired once every child was accounted for")
    (is (= 1 (handler-count "B1" "batch/start-paying"))
        "the batch's own transition happened once, not once per contender")))

(deftest a-lost-race-does-not-lose-the-event
  (let [r (rt)
        tx-ids (batch-with-transactions! r 3)]
    (doseq [t tx-ids] (submit! t "TransactionScreeningCleared"))
    (mapv deref (repeatedly 3 #(future (kernel/drain! *ds* r))))
    (dotimes [_ 4] (kernel/drain! *ds* r))

    (is (every? #(= "TX_PAYING" (state-of %)) tx-ids))
    (is (zero? (count (db/in-tx [tx *ds*] (event/parked tx tree-id))))
        "no event was parked: contention is retried, not treated as poison")
    (testing "each event produced exactly one checkpoint"
      (doseq [t tx-ids]
        (is (= 1 (handler-count t "tx/screening-cleared")))))))

(deftest two-runtime-nodes-never-double-transition
  ;; Design 64: identical runtime nodes over one database, claiming with
  ;; FOR UPDATE SKIP LOCKED.
  (let [r (rt)
        tx-ids (batch-with-transactions! r 6)]
    (doseq [t tx-ids] (submit! t "TransactionScreeningCleared"))
    (mapv deref (repeatedly 4 #(future (kernel/drain! *ds* r))))
    (dotimes [_ 4] (kernel/drain! *ds* r))

    (is (every? #(= "TX_PAYING" (state-of %)) tx-ids))
    (is (= "BATCH_PROCESSING" (state-of "B1")))
    (testing "the batch's own transition happened once, not once per worker"
      (is (= 1 (handler-count "B1" "batch/start-paying"))))))

(deftest concurrent-dispatchers-send-an-effect-once
  (let [screening (fake/effect-adapter [(completed)])
        r (rt {:effect-adapters {"screening.check" screening}})]
    ;; Drive TX0 into TX_SCREENING, where a single screening.check intent sits
    ;; ready for dispatch.
    (new-tree!)
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX0" :amount 100 :payment-type "SCT"}]}])
    (kernel/drain! *ds* r)          ; F1 -> WRITING_RECEIVE_FILE
    (dispatcher/run-once! *ds* r)   ; file.writeReceive completes
    (kernel/drain! *ds* r)          ; F1 -> FILE_PROCESS_BATCH, B1 prepares, TX0 -> TX_SCREENING
    (is (= "TX_SCREENING" (state-of "TX0")))

    (mapv deref (repeatedly 4 #(future (dispatcher/dispatch-once! *ds* r))))
    (is (= 1 (count (filter #(= :dispatch (:op %)) (fake/calls screening))))
        "four dispatchers, one external call")))

;; --- crash and restart ----------------------------------------------------------------

(deftest a-crash-after-the-external-call-leaves-an-uncertain-effect
  ;; The dispatcher sent the submission and died before recording the outcome. A rail
  ;; is a :query effect: it must not be resent blindly -- the rail may hold it --
  ;; only asked.
  (let [rail (fake/effect-adapter [{:throw "process died mid-call"}]
                                  {:outcome :completed :external-request-id "SCT-RECOVERED"})
        r (rt {:effect-adapters {"rail.sct.submit" rail}})]
    (new-tree!)
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX1" :amount 100 :payment-type "SCT"}]}])
    (runtime/settle! *ds* r)

    (let [e (first (filter #(= "rail.sct.submit" (:effect-name %))
                           (db/in-tx [tx *ds*] (effect/by-owner tx tree-id "TX1-A0"))))]
      (is (= "COMPLETED" (:status e)) "resolved by asking, not by resending")
      (is (= "SCT-RECOVERED" (:external-request-id e))))
    (is (= 1 (count (filter #(= :dispatch (:op %)) (fake/calls rail))))
        "the submission was sent exactly once, despite the crash")))

(deftest restart-resumes-from-the-last-checkpoint
  ;; Design 20: restart is not rerunning the chain, it is resuming from committed
  ;; state. A completed store effect is not rewritten.
  (let [store (fake/store-adapter)
        r (rt {:store-adapters {"file.writeReceive" store}})]
    (new-tree!)
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX0" :amount 100 :payment-type "SCT"}]}])
    (kernel/drain! *ds* r)          ; F1 -> WRITING_RECEIVE_FILE (store intent created)
    (dispatcher/run-once! *ds* r)   ; the receive-file write commits
    (kernel/drain! *ds* r)          ; F1 -> FILE_PROCESS_BATCH
    (is (= "FILE_PROCESS_BATCH" (state-of "F1")))
    (is (= 1 (count (fake/writes store))))

    (testing "a fresh runtime resumes without redoing completed work"
      (let [r2 (rt {:store-adapters {"file.writeReceive" store}})]
        (kernel/drain! *ds* r2)
        (dispatcher/run-once! *ds* r2)
        (dispatcher/run-once! *ds* r2)
        (is (= 1 (count (fake/writes store))) "the store effect was not rewritten")
        (is (= "FILE_PROCESS_BATCH" (state-of "F1")) "and the file did not move backwards")))))

(deftest an-interrupted-drain-leaves-no-partial-transition
  (let [r (rt)
        broken (assoc r :handlers (assoc app/handlers 'file/received
                                         (fn [_ _] (throw (RuntimeException. "killed mid-transition")))))]
    (new-tree! (as-code 'file/received))
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX0" :amount 100 :payment-type "SCT"}]}])
    (kernel/drain! *ds* broken)

    (is (= "FILE_NEW" (state-of "F1")) "no state change")
    (is (empty? (db/in-tx [tx *ds*] (effect/by-owner tx tree-id "F1"))) "no effect intent")
    (is (zero? (:n (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM outbox"]))))
        "no outbox row")

    (testing "and a healthy runtime picks the event straight back up"
      (let [specs (:specs (compiler/compile-definition fbt/definition))
            healthy (assoc-in r [:handlers 'file/received]
                              (handler/plan-fn (compiler/transition-for specs "File" "FILE_NEW" "FileReceived") {}))]
        (kernel/drain! *ds* healthy)
        (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))))))

;; --- scale -------------------------------------------------------------------------------

(deftest a-wide-parent-aggregates-without-counting-children
  ;; Design 68 asked how large child sets stay efficient. Counters are held on
  ;; the parent, so the aggregate rule reads one row, not N.
  (let [r (rt)
        n 150
        tx-ids (batch-with-transactions! r n)]
    (doseq [t tx-ids] (submit! t "TransactionScreeningCleared"))
    (let [start (System/nanoTime)]
      (dotimes [_ 3] (kernel/drain! *ds* r))
      (let [elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (is (= "BATCH_PROCESSING" (state-of "B1"))
            "the aggregate rule fired over every child, reading one counter row")
        (is (= n (:child-count (data-of "B1"))))
        (println (format "  [scale] %d children folded in %.0f ms" n elapsed-ms))))))

;; --- artifact pinning across versions -------------------------------------------------------

(deftest trees-pinned-to-different-artifacts-coexist
  ;; Decision D10: no migration in the MVP. An old tree runs its own artifact to
  ;; completion while new trees start on the new one.
  (let [r (rt)
        old-aid (install!)
        new-def (assoc-in fbt/definition [:profile :version] "v4")
        new-aid (db/in-tx [tx *ds*]
                  (let [a (compiler/compile-definition new-def)]
                    (artifact/insert! tx a) (:artifact-id a)))]
    (is (not= old-aid new-aid))

    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "old" :artifact-id old-aid :root-node-id "F1"
                               :root-node-type "File"
                               :profile-name fbt/profile-name :profile-version "v2"})
      (kernel/create-tree! tx {:tree-id "new" :artifact-id new-aid :root-node-id "F1"
                               :root-node-type "File"
                               :profile-name fbt/profile-name :profile-version "v3"})
      (kernel/submit-event! tx {:event-id "o1" :tree-id "old" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:name "old.csv" :batches []}})
      (kernel/submit-event! tx {:event-id "n1" :tree-id "new" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:name "new.csv" :batches []}}))
    (kernel/drain! *ds* r)

    (let [old-entry (first (db/in-tx [tx *ds*] (tlog/history tx "old" "F1")))
          new-entry (first (db/in-tx [tx *ds*] (tlog/history tx "new" "F1")))]
      (is (= old-aid (:artifact-id old-entry)))
      (is (= new-aid (:artifact-id new-entry)))
      (is (= "WRITING_RECEIVE_FILE" (:to-state old-entry)))
      (is (= "WRITING_RECEIVE_FILE" (:to-state new-entry))))))

;; --- settle ---------------------------------------------------------------------------------

(deftest the-runtime-settles
  (let [r (rt)]
    (new-tree!)
    ;; SCT transactions only -- a synchronous rail, so the file settles without
    ;; any out-of-band callback.
    (receive-file! [{:batch-id "B1"
                     :transactions [{:tx-id "TX0" :amount 100 :payment-type "SCT"}
                                    {:tx-id "TX1" :amount 100 :payment-type "SCT"}]}])
    (runtime/settle! *ds* r)
    (is (= "FILE_COMPLETED" (state-of "F1")))
    (is (empty? (db/in-tx [tx *ds*] (event/claim-pending tx 10)))
        "settle leaves no unprocessed events")))

;; --- observability ------------------------------------------------------------------------

(deftest metrics-report-what-an-operator-must-act-on
  (let [store (fake/store-adapter [{:throw "store down"}])
        r (rt {:store-adapters {"file.writeReceive" store}})]
    (new-tree! (as-code 'file/fail))   ; the last check registers a failing file/fail
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX0" :amount 100 :payment-type "SCT"}]}])
    (kernel/drain! *ds* r)          ; F1 -> WRITING_RECEIVE_FILE (store intent created)
    (dispatcher/run-once! *ds* r)   ; the write fails -> DISPATCH_FAILED

    (let [snap (db/in-tx [tx *ds*] (voucha.metrics/snapshot tx))]
      (is (zero? (:event-backlog snap)))
      (is (zero? (:parked-events snap)))
      (testing "the failing store effect shows in the backlog"
        (let [[b] (:effect-backlog snap)]
          (is (= "file.writeReceive" (:effect-name b)))
          (is (= "DISPATCH_FAILED" (:status b)))
          (is (= 1 (:count b))))))

    (testing "an uncertain effect awaiting an operator is listed separately"
      (db/in-tx [tx *ds*]
        (db/execute-one! tx ["UPDATE effect_instance SET uncertainty_policy = 'manual'"]))
      (Thread/sleep 80)
      (dispatcher/run-once! *ds* r)
      (is (= 1 (count (db/in-tx [tx *ds*] (voucha.metrics/uncertain-effects tx))))))

    (testing "a parked event is reported with its error"
      ;; F1 is still WRITING_RECEIVE_FILE, so ReceiveFileWriteFailed has a
      ;; transition to run -- and a handler that always throws parks it.
      (let [broken (assoc r :handlers (assoc app/handlers 'file/fail
                                             (fn [_ _] (throw (RuntimeException. "boom")))))]
        (submit! "F1" "ReceiveFileWriteFailed")
        (dotimes [_ 6] (kernel/drain! *ds* broken))
        (let [parked (db/in-tx [tx *ds*] (voucha.metrics/parked-events tx))]
          (is (= 1 (count parked)))
          (is (re-find #"boom" (:last-error (first parked)))))))

    (testing "the health snapshot renders as one structured log line"
      (is (re-find #":metric \"voucha.health\""
                   (voucha.metrics/log-line (db/in-tx [tx *ds*] (voucha.metrics/snapshot tx))))))))
