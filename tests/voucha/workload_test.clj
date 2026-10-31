(ns voucha.workload-test
  "The seeded workload generator (test plan T0 §6.3) and its expected-state oracle.

  Two things must hold for the generator to be trusted by every later tier: the
  same seed yields the same workload (a result is reproducible from its seed),
  and the expected terminal states it derives are the ones the reference profile
  actually reaches -- for a hand-built workload exercising every scripted path,
  and for random seeds."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.test-db :as tdb :refer [*ds*]]
            [voucha.workload :as w]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(deftest the-same-seed-yields-the-same-workload
  (is (= (w/generate 42) (w/generate 42)))
  (is (= (w/generate 7 {:files 2 :batches [2 2]}) (w/generate 7 {:files 2 :batches [2 2]})))
  (is (not= (w/generate 42) (w/generate 43)) "different seeds, different workloads"))

(deftest across-seeds-the-generator-produces-every-scripted-path
  ;; Not a statistical claim -- just that none of the paths is dead code in the
  ;; generator. High probabilities, many seeds.
  (let [opts {:files 1 :batches [2 3] :transactions [2 4]
              :p-invalid 0.3 :p-rejected 0.3 :p-screening-transient 0.3
              :p-rail-fail-once 0.3 :p-rail-permanent 0.3}
        ws (map #(w/generate % opts) (range 1 31))]
    ;; `true?` keeps a failure from printing thirty whole workloads.
    (is (true? (boolean (some #(seq (get-in % [:script :rejected])) ws))) "a rejected batch")
    (is (true? (boolean (some #(seq (get-in % [:script :screening-transient])) ws))) "a transient screening failure")
    (is (true? (boolean (some #(some (fn [[a o]] (and (= :completed o) (re-find #"-A1$" a))) (get-in % [:script :rail])) ws)))
        "a rail that fails once then succeeds")
    (is (true? (boolean (some #(some (fn [[a _]] (re-find #"-A3$" a)) (get-in % [:script :rail])) ws)))
        "a permanently failing rail")
    (is (true? (boolean (some #(some (fn [t] (zero? (:amount t)))
                                     (mapcat :transactions (mapcat :batches (map :payload (:files %)))))
                              ws)))
        "an invalid transaction")
    (is (true? (boolean (some #(seq (:absent %)) ws))) "and absent nodes for rejected batches")))

(deftest a-hand-built-workload-covering-every-path-reaches-its-expected-states
  ;; One file, three batches: B0 rejected (control sum off by one); B1 with an
  ;; invalid tx, a transient-screening tx, an instant fail-once tx; B2 with an SCT
  ;; permanent-failure tx and a plain one.
  (let [f "H-F0" b0 (str f "-B0") b1 (str f "-B1") b2 (str f "-B2")
        tx (fn [b n] (str b "-T" n))
        workload
        {:seed :hand
         :files [{:tree-id f :root-node-id f
                  :payload {:name "hand.pain"
                            :batches [{:batch-id b0 :control-sum 11 :count 1
                                       :transactions [{:tx-id (tx b0 0) :amount 10 :payment-type "SCT"}]}
                                      {:batch-id b1 :control-sum 50 :count 3
                                       :transactions [{:tx-id (tx b1 0) :amount 0 :payment-type "INST"}
                                                      {:tx-id (tx b1 1) :amount 20 :payment-type "SCT"}
                                                      {:tx-id (tx b1 2) :amount 30 :payment-type "INST"}]}
                                      {:batch-id b2 :control-sum 90 :count 2
                                       :transactions [{:tx-id (tx b2 0) :amount 40 :payment-type "SCT"}
                                                      {:tx-id (tx b2 1) :amount 50 :payment-type "INST"}]}]}}]
         :script {:rejected #{b0}
                  :screening-transient #{(tx b1 1)}
                  :rail (merge {(str (tx b1 2) "-A0") :failed (str (tx b1 2) "-A1") :completed}
                               (into {} (map (fn [n] [(str (tx b2 0) "-A" n) :failed])) (range 4)))}
         :expected (merge {f "FILE_COMPLETED"
                           b0 "BATCH_REJECTED" b1 "BATCH_COMPLETED" b2 "BATCH_COMPLETED"
                           (tx b1 0) "TX_FAILED"
                           (tx b1 1) "TX_COMPLETED" (str (tx b1 1) "-A0") "ATTEMPT_SUCCEEDED"
                           (tx b1 2) "TX_COMPLETED" (str (tx b1 2) "-A0") "ATTEMPT_FAILED" (str (tx b1 2) "-A1") "ATTEMPT_SUCCEEDED"
                           (tx b2 0) "TX_FAILED"
                           (tx b2 1) "TX_COMPLETED" (str (tx b2 1) "-A0") "ATTEMPT_SUCCEEDED"}
                          (into {} (map (fn [n] [(str (tx b2 0) "-A" n) "ATTEMPT_FAILED"])) (range 4)))
         :absent #{(tx b0 0) (str (tx b0 0) "-A0")}}
        r (w/run! *ds* workload)]
    (is (:settled? r) (pr-str (:file-states r)))
    (is (empty? (:mismatches r)) (pr-str (:mismatches r)))
    (is (empty? (:present-but-expected-absent r)) "a rejected batch created no transactions")
    (is (:ok? (:invariants r)) (pr-str (:violations (:invariants r))))
    (is (:ok? r))))

(deftest random-workloads-reach-their-expected-states
  (doseq [seed [11 23 37]]
    (tdb/clean-db
     (fn []
       (let [workload (w/generate seed {:files 1 :batches [1 3] :transactions [1 4]
                                        :p-invalid 0.2 :p-rejected 0.2 :p-screening-transient 0.2
                                        :p-rail-fail-once 0.2 :p-rail-permanent 0.1})
             r (w/run! *ds* workload)]
         (testing (str "seed " seed)
           (is (:settled? r) (pr-str (:file-states r)))
           (is (empty? (:mismatches r)) (pr-str (:mismatches r)))
           (is (empty? (:present-but-expected-absent r)))
           (is (:ok? (:invariants r)) (pr-str (:violations (:invariants r))))))))))
