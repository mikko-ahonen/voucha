(ns voucha.inspect-test
  "Inspection, chain view, harness, graph export, and replay.

  Design 63 asks that the system answer 'why is this node stuck?' and
  'which parent condition blocks it?'. These tests hold it to that."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.graph :as graph]
            [voucha.handler :as handler]
            [voucha.harness :as h]
            [voucha.inspect :as inspect]
            [voucha.plan :as plan]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.replay :as replay]
            [voucha.repo.transition-log :as tlog]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(defn- completed [] {:outcome :completed})

(defn- harness []
  (h/for-artifact *ds* fbt/definition
                  {:handlers app/handlers
                   :effect-adapters {"screening.check" (fake/effect-adapter [(completed)])
                                     "account.debit"   (fake/effect-adapter [(completed)])
                                     "account.reverse" (fake/effect-adapter [(completed)])
                                     "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])
                                     "rail.sct.submit" (fake/effect-adapter [(completed)])}
                   :store-adapters {"file.writeReceive" (fake/store-adapter)
                                    "file.writeResult"  (fake/store-adapter)}}))

;; A transaction locally ready to pay (TX_SCREENED) under a batch that has not
;; started processing (BATCH_NEW): the design-62 hierarchy example.
(def ^:private a-tree
  [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
   {:id "B1" :type "Batch" :state "BATCH_NEW" :parent "F1"}
   {:id "TX1" :type "Transaction" :state "TX_SCREENED" :parent "B1"}
   {:id "TX2" :type "Transaction" :state "TX_VALIDATING" :parent "B1"}])

;; --- the harness ------------------------------------------------------------------

(deftest harness-rejects-a-transition-the-hierarchy-forbids
  ;; The design-62 example, verbatim in intent: a transaction that is locally
  ;; ready to pay, under a batch that has not started processing.
  (-> (harness)
      (h/given-tree a-tree)
      (h/when-event "TX1" "TransactionPayCommanded")
      (h/expect-rejected-transition #"parent is BATCH_NEW, must be one of")
      (h/expect-state "TX1" "TX_SCREENED")))

(deftest harness-derives-parent-counters-from-the-given-tree
  (let [hh (-> (harness) (h/given-tree a-tree))]
    (h/expect-counter hh "B1" :tx-ready 1)
    (h/expect-counter hh "B1" :tx-failed 0)))

(deftest harness-asserts-effects-and-awaits
  ;; The other design-62 example: a transaction screens itself via an external
  ;; effect and awaits the answer.
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_PROCESSING" :parent "F1"}
                     {:id "TX1" :type "Transaction" :state "TX_VALIDATED" :parent "B1"}])
      (h/when-event "TX1" "TransactionScreeningStart")
      (h/expect-transitioned)
      (h/expect-state "TX1" "TX_SCREENING")
      (h/expect-effect "TX1" "screening.check:v1")
      (h/expect-await "TX1" "TransactionScreeningCleared")))

(deftest harness-runs-fake-dispatchers
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_PROCESSING" :parent "F1"}
                     {:id "TX1" :type "Transaction" :state "TX_VALIDATED" :parent "B1"}])
      (h/when-event "TX1" "TransactionScreeningStart")
      (h/dispatch-effects!)
      (h/expect-state "TX1" "TX_SCREENED")))

(deftest harness-failures-are-legible
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Expected TX1 to be in state TX_PAYING, but it is TX_SCREENED"
       (-> (harness) (h/given-tree a-tree) (h/expect-state "TX1" "TX_PAYING")))))

;; --- inspection --------------------------------------------------------------------

(deftest why-stuck-names-the-blocking-parent-condition
  (-> (harness) (h/given-tree a-tree))
  (let [w (db/in-tx [tx *ds*] (inspect/why-stuck tx "test-tree" "TX1"))]
    (is (= "TX_SCREENED" (:local-state w)))
    (is (empty? (:allowed-next-events w)) "nothing can advance TX1 right now")
    (let [[blocked] (:blocked-transitions w)]
      (is (= "TransactionPayCommanded" (:event blocked)))
      (is (= "TX_PAYING" (:to blocked)))
      (is (re-find #"parent is BATCH_NEW" (first (:blocked-by blocked)))))))

(deftest allowed-next-events-reflects-the-live-hierarchy
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_PROCESSING" :parent "F1"}
                     {:id "TX1" :type "Transaction" :state "TX_SCREENED" :parent "B1"}]))
  (is (= ["TransactionPayCommanded"]
         (db/in-tx [tx *ds*] (inspect/allowed-next-events tx "test-tree" "TX1")))
      "the same transition that was blocked is now allowed, because the batch moved"))

(deftest inspection-reports-outstanding-effects-and-their-external-ids
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_PROCESSING" :parent "F1"}
                     {:id "TX1" :type "Transaction" :state "TX_VALIDATED" :parent "B1"}])
      (h/when-event "TX1" "TransactionScreeningStart"))
  (let [w (db/in-tx [tx *ds*] (inspect/why-stuck tx "test-tree" "TX1"))
        [e] (:outstanding-effects w)]
    (is (= "screening.check" (:effect-name e)))
    (is (= "CREATED" (:status e)))))

(deftest a-file-completes-and-is-reported-terminal
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_BATCHES_PROCESSED"}])
      (h/when-event "F1" "ResultFileWritten")
      (h/expect-state "F1" "FILE_COMPLETED"))
  (let [w (db/in-tx [tx *ds*] (inspect/why-stuck tx "test-tree" "F1"))]
    (is (true? (:terminal? w)))
    (is (empty? (:allowed-next-events w)))))

(deftest history-records-the-pinned-artifact-and-read-set
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_BATCHES_PROCESSED"}])
      (h/when-event "F1" "ResultFileWritten"))
  (let [[entry] (db/in-tx [tx *ds*] (inspect/history tx "test-tree" "F1"))]
    (is (= "FILE_BATCHES_PROCESSED" (:from-state entry)))
    (is (= "FILE_COMPLETED" (:to-state entry)))
    (is (string? (:artifact-id entry)))))

;; --- chain view ----------------------------------------------------------------------

(deftest chain-view-projects-profile-steps-over-the-tree
  (-> (harness)
      (h/given-tree [{:id "B1" :type "Batch" :state "BATCH_PROCESSING"}
                     {:id "TX1" :type "Transaction" :state "TX_SCREENED" :parent "B1"}
                     {:id "TX2" :type "Transaction" :state "TX_FAILED" :parent "B1"}
                     {:id "TX3" :type "Transaction" :state "TX_VALIDATING" :parent "B1"}]))
  (let [v (db/in-tx [tx *ds*] (inspect/chain-view tx "test-tree" "BatchChain" "B1"))
        by-name (into {} (map (juxt :name identity)) (:steps v))]
    (is (= "BATCH_PROCESSING" (:local-state v)))
    (is (= :complete (:status (by-name "Validate"))))
    (is (= :active (:status (by-name "Process"))))
    (testing "progress counts come from the same counters the kernel maintains"
      (is (= {:total 3 :tx-ready 1 :tx-paid 0 :tx-failed 1}
             (:progress (by-name "Process")))))))

(deftest chain-view-rejects-a-mismatched-node-type
  (-> (harness) (h/given-tree a-tree))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"defined over Batch, not File"
                        (db/in-tx [tx *ds*] (inspect/chain-view tx "test-tree" "BatchChain" "F1")))))

(deftest profiles-are-listed-from-compiled-artifacts
  (harness)
  (let [[p] (db/in-tx [tx *ds*] (inspect/profiles tx))]
    (is (= "file-batch-transaction" (:profile-name p)))
    (is (= "v3" (:profile-version p)))))

;; --- graph export ---------------------------------------------------------------------

(deftest graph-export-renders-the-artifact
  (let [dot (graph/to-dot (:specs (compiler/compile-definition fbt/definition)))]
    (is (str/starts-with? dot "digraph artifact {"))
    (is (str/includes? dot "\"Batch.BATCH_PAY_TRANSACTIONS\" -> \"Batch.BATCH_COMPLETED\""))
    (is (str/includes? dot "style=dashed") "constrained transitions are drawn dashed")
    (is (str/includes? dot "peripheries=2") "terminal states are doubled"))
  (let [dot (graph/hierarchy-dot (:specs (compiler/compile-definition fbt/definition)))]
    (is (str/includes? dot "\"File\" -> \"Batch\""))
    (is (str/includes? dot "\"Transaction\" -> \"Attempt\"")))
  (testing "Mermaid and PlantUML nest the machines by containment"
    (let [specs (:specs (compiler/compile-definition fbt/definition))
          mm (graph/to-mermaid specs)
          pu (graph/to-plantuml specs)]
      (is (str/starts-with? mm "stateDiagram-v2"))
      (is (< (str/index-of mm "as File {") (str/index-of mm "as BATCH_NEW")
             (str/index-of mm "as TX_NEW") (str/index-of mm "as ATTEMPT_NEW"))
          "each contained type's machine appears inside its container, parents before children")
      (is (< (str/index-of mm "as FILE_PROCESS_BATCH {") (str/index-of mm "as BATCH_NEW"))
          "a child type's machine is drawn inside the parent state that hosts it, with no container of its own")
      (is (not (str/includes? mm "as Batch {")) "no second container around the hosted machine")
      (is (str/includes? mm "state \"TX_SCREEN_RETRY_WAIT\" as TX_SCREEN_RETRY_WAIT")
          "a state's label is its id, level prefix and all")
      (is (str/includes? mm "TX_PAYING --> TX_COMPLETED : AttemptSucceeded ^TransactionPaid")
          "a bubbling event is shown where the child emits it")
      (is (str/includes? mm "state \"TX_PAYING\" as TX_PAYING {") "the state hosting the attempts is drawn as a container")
      (is (< (str/index-of mm "as TX_PAYING {") (str/index-of mm "as ATTEMPT_NEW")) "with the attempt machine inside it")
      (is (str/includes? mm "TX_SCREENING --> TX_FAILED : TransactionCancelled ^TransactionRejected"))
      (is (= 1 (count (re-seq #"\[\*\] --> TX_" mm))) "one start per hosted machine")
      (is (str/includes? mm "BATCH_PAY_TRANSACTIONS --> BATCH_COMPLETED : AllTransactionsSettled ^BatchSettled (auto, guarded)")
          "a decision is an auto transition, marked as such")
      (is (str/includes? mm "TX_VALIDATED --> TX_SCREENING : TransactionScreeningStart / screening.check"))
      (is (str/includes? mm "FILE_COMPLETED --> [*]"))
      (is (str/starts-with? pu "@startuml"))
      (is (str/includes? pu "as ATTEMPT_NEW #") "PlantUML colours each state by its level"))))

;; --- replay (design 57) -----------------------------------------------------------------

(deftest audit-replay-reconstructs-history-from-the-log
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_NEW" :parent "F1"}])
      (h/when-event "B1" "BatchStartCommanded" {:transactions []})
      (h/dispatch-effects!))
  (let [r (db/in-tx [tx *ds*] (replay/audit tx "test-tree" "B1"))]
    (is (= "BATCH_COMPLETED" (:final-state r)))
    (is (= ["BATCH_NEW" "BATCH_VALIDATING" "BATCH_SCREEN_TRANSACTIONS" "BATCH_PAY_TRANSACTIONS"]
           (mapv :from-state (:transitions r)))
        "an empty batch: validated, then both decisions taken at once, by the kernel")
    (is (= {:transactions [] :children [] :pay-commanded true :paid 0 :failed 0} (:final-data r))
        "folding the recorded patches reproduces the node's data")))

(deftest deterministic-replay-reproduces-handler-output
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_NEW" :parent "F1"}])
      (h/when-event "B1" "BatchStartCommanded")
      (h/dispatch-effects!))
  (let [r (db/in-tx [tx *ds*] (replay/verify-deterministic tx "test-tree" app/handlers))]
    (is (:ok? r) (pr-str (:divergences r)))
    (is (pos? (:checked r))))

  (testing "replay never dispatches an effect"
    (let [before (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM effect_attempt"]))]
      (db/in-tx [tx *ds*] (replay/verify-deterministic tx "test-tree" app/handlers))
      (is (= before (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM effect_attempt"])))))))

(deftest deterministic-replay-detects-a-nondeterministic-handler
  ;; The point of design 58: a handler that reads the wall clock cannot be
  ;; replayed, and replay is what proves it.
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_NEW" :parent "F1"}])
      (h/when-event "B1" "BatchStartCommanded"))
  (let [naughty (fn [_ctx _ev]
                  (-> (plan/plan)
                      (plan/state "BATCH_VALIDATING")
                      (plan/patch (list 'assoc-in [:at] (str (System/nanoTime))))))]
    (testing "a plan is in the pinned artifact: no registry can make it diverge"
      (let [r (db/in-tx [tx *ds*] (replay/verify-deterministic tx "test-tree" (assoc app/handlers 'batch/start-validation naughty)))]
        (is (true? (:ok? r)))))
    (testing "a code handler that changed since the run diverges"
      (tdb/clean-db
       (fn []
         (let [specs (:specs (compiler/compile-definition fbt/definition))
               honest (handler/plan-fn (compiler/transition-for specs "Batch" "BATCH_NEW" "BatchStartCommanded") {})
               as-code (update fbt/definition :transitions
                               (fn [ts] (mapv #(if (= 'batch/start-validation (:handler %)) (dissoc % :plan) %) ts)))]
           (-> (h/for-artifact *ds* as-code {:handlers (assoc app/handlers 'batch/start-validation honest)})
               (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                              {:id "B1" :type "Batch" :state "BATCH_NEW" :parent "F1"}])
               (h/when-event "B1" "BatchStartCommanded"))
           (let [r (db/in-tx [tx *ds*] (replay/verify-deterministic tx "test-tree" (assoc app/handlers 'batch/start-validation naughty)))]
             (is (false? (:ok? r)))
             (is (= 1 (count (:divergences r))))
             (is (= "batch/start-validation" (:handler (first (:divergences r))))))))))))

(deftest investigation-replay-explains-a-past-transition
  (-> (harness)
      (h/given-tree [{:id "F1" :type "File" :state "FILE_PROCESS_BATCH"}
                     {:id "B1" :type "Batch" :state "BATCH_PROCESSING" :parent "F1"}
                     {:id "TX1" :type "Transaction" :state "TX_SCREENED" :parent "B1"}])
      (h/when-event "TX1" "TransactionPayCommanded")
      (h/expect-transitioned))
  (let [tid (:transition-id (first (filter #(= "tx/start-paying" (:handler-name %))
                                           (db/in-tx [tx *ds*]
                                             (tlog/history tx "test-tree" "TX1")))))
        e (db/in-tx [tx *ds*] (replay/explain-transition tx "test-tree" "TX1" tid))]
    (is (= "TX_PAYING" (:to-state e)))
    (is (true? (:context-hash-verified? e))
        "the recorded read-set still hashes to the recorded context hash")
    (is (= 1 (count (:constraints e))))
    (is (true? (:satisfied? (first (:constraints e)))))
    (is (= {:parent-state "BATCH_PROCESSING"} (:context-recorded e))
        "the transition recorded exactly the parent state its rule read")))
