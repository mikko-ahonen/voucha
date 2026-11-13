(ns voucha.model-tla-test
  "The TLA+ specification of a definition: generated from the artifact, and,
  when the TLA+ tools are installed, checked exhaustively by TLC. TLC is not
  assumed by CI; those tests skip without the jar."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.example.order :as order]
            [voucha.model-tla :as model-tla]
            [voucha.profiles.file-batch-transaction :as fbt]))

(def ^:private sepa (compiler/compile-definition fbt/definition))
(def ^:private small-file
  {:name "f" :batches [{:batch-id "B1" :control-sum 100 :count 1
                        :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "SCT"}]}]})
(def ^:private sepa-config {:root-type "File" :root-id "F1" :first-event "FileReceived" :first-payload small-file
                            :children {} :max-repeat 3 :allow-drop false})
(def ^:private order-config {:root-type "Order" :root-id "O1" :first-event "OrderPlaced"
                             :first-payload {:customer "C-9" :items [{:item-id "O1-L1" :sku "A" :qty 2 :price 10}]}
                             :children {} :max-repeat 2 :allow-drop false})

(deftest the-specification-carries-the-definition-and-the-plans
  (let [files (model-tla/generate sepa sepa-config)
        tla (get files "file_batch_transaction.tla")
        cfg (get files "file_batch_transaction.cfg")]
    (is (= #{"file_batch_transaction.tla" "file_batch_transaction.cfg"} (set (keys files))))
    (is (str/starts-with? tla "---- MODULE file_batch_transaction ----"))
    (is (str/includes? tla "ntype |-> \"Transaction\", src |-> \"TX_SCREENED\", evt |-> \"TransactionPayCommanded\""))
    (is (str/includes? tla "<<\"Transaction\", \"TX_FAILED\", \"TransactionPayCommanded\">>") "the ignores")
    (is (str/includes? tla "External == {\"TransactionCancelled\"}") "the outside world's events, injected")
    (is (str/includes? tla "kind |-> \"cover\"") "the counters-cover rule")
    (testing "plans are operators, exact"
      (is (str/includes? tla "SumField(Get(payload, \"transactions\"), \"amount\")") "the control-sum check")
      (is (re-find #"RECURSIVE Each_\d+" tla) "a loop over the records is a recursive operator")
      (is (str/includes? tla "IF Lt(Or2(Get(d0, \"attempts\"), 0), 3)") "the bounded retry")
      (is (str/includes? tla "RunPlan(ti, node, d0, payload, ns) ==")))
    (testing "the run"
      (is (str/includes? tla "RootId == <<\"F1\", \"\">>") "node ids are base-suffix pairs")
      (is (str/includes? tla "(\"control-sum\" :> 100)"))
      (is (str/includes? cfg "INVARIANTS NoRejection ChildrenAllowed NotDead")))
    (is (= files (model-tla/generate sepa sepa-config)) "deterministic")
    (testing "symmetric ids become model values TLC may permute"
      (let [{tla* "file_batch_transaction.tla" cfg* "file_batch_transaction.cfg"}
            (model-tla/generate sepa (assoc sepa-config :symmetric ["tx-id"]))]
        (is (str/includes? tla* "CONSTANTS Nil, sym_B1_T1"))
        (is (str/includes? tla* "Symm == Permutations({sym_B1_T1})"))
        (is (str/includes? tla* "(\"tx-id\" :> sym_B1_T1)"))
        (is (str/includes? cfg* "SYMMETRY Symm"))))
    (testing "liveness adds the temporal properties under the fair spec"
      (let [cfg* (get (model-tla/generate sepa (assoc sepa-config :liveness true)) "file_batch_transaction.cfg")]
        (is (str/includes? cfg* "SPECIFICATION Spec"))
        (is (str/includes? cfg* "PROPERTIES TerminalStays Termination"))))))

(deftest the-order-example-is-exhaustively-clean-under-tlc
  (if-not (model-tla/tla2tools)
    (println "  [skip] tla2tools.jar not found; the specification is generated but not checked")
    (let [dir (str "tmp/tla-test/order-" (System/nanoTime))
          artifact (compiler/compile-definition order/definition)]
      (model-tla/write! dir (model-tla/generate artifact order-config))
      (let [r (model-tla/check! dir (model-tla/module-name (:specs artifact)) {:workers 2})]
        (is (:ok? r) (str (:error r) "\n" (:out r)))
        (is (pos? (:distinct r)) "TLC reports the state count")))))

(deftest a-definition-that-refuses-a-command-is-found-by-tlc
  ;; Without TX_FAILED's :ignores, a transaction that failed screening refuses
  ;; the batch's pay command; TLC finds the state and prints the trace to it.
  (if-not (model-tla/tla2tools)
    (println "  [skip] tla2tools.jar not found")
    (let [dir (str "tmp/tla-test/sepa-" (System/nanoTime))
          flawed (compiler/compile-definition (update-in fbt/definition [:node-types "Transaction" :ignores "TX_FAILED"]
                                                         (fn [evs] (vec (remove #{"TransactionPayCommanded"} evs)))))
          ;; the screening check may fail, which is what makes a transaction fail before paying
          cfg (assoc sepa-config :first-payload
                     {:name "f" :batches [{:batch-id "B1" :control-sum 300 :count 2
                                           :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "SCT"}
                                                          {:tx-id "B1-T2" :amount 200 :payment-type "SCT"}]}]})]
      (model-tla/write! dir (model-tla/generate flawed cfg))
      (let [r (model-tla/check! dir (model-tla/module-name (:specs flawed)) {:workers 2})]
        (is (false? (:ok? r)))
        (is (= "Invariant NoRejection is violated." (:error r)))
        (is (re-find #"has no transition from TX_FAILED on TransactionPayCommanded" (str (:trace r) (:out r))))))))

(def ^:private skipping-the-debit
  ;; a handler that reports the debit done without requesting it: every framework
  ;; property holds, the definition's own success-only-after-debit does not
  (update fbt/definition :transitions
          (fn [ts] (mapv #(if (= 'attempt/debit (:handler %))
                            (-> % (assoc :plan [[:emit-to :self "AccountDebited"]]) (dissoc :effects :await :emit :creates :commands))
                            %) ts))))

(deftest the-business-invariants-are-tlc-invariants
  (let [files (model-tla/generate (compiler/compile-definition order/definition) order-config)
        tla (get files "order.tla")]
    (is (str/includes? tla "Inv_one_charge_per_order == (\\A p_o \\in {n_ \\in DOMAIN nodes : nodes[n_].type = \"Order\"} : (Cardinality({p_e \\in {j_ \\in 1..Len(effects) : effects[j_].spec = \"payment.charge\" /\\ effects[j_].owner = p_o} : (~(Eq(effects[p_e].status, \"CANCELLED\") \\/ Eq(effects[p_e].status, \"FAILED\")))}) <= 1))"))
    (is (re-find #"RECURSIVE PSum_1\(_, _\)" tla) "a sum over a data collection is a generated operator")
    (is (str/includes? (get files "order.cfg") "INVARIANTS NoRejection ChildrenAllowed NotDead Inv_cancelled_holds_nothing Inv_charged_total_is_the_lines Inv_one_charge_per_order Inv_shipped_is_paid"))))

(deftest a-business-invariant-violation-is-found-by-tlc
  (if-not (model-tla/tla2tools)
    (println "  [skip] tla2tools.jar not found")
    (let [dir (str "tmp/tla-test/sepa-skip-" (System/nanoTime))
          flawed (compiler/compile-definition skipping-the-debit)]
      (model-tla/write! dir (model-tla/generate flawed sepa-config))
      (let [r (model-tla/check! dir (model-tla/module-name (:specs flawed)) {:workers 2})]
        (is (false? (:ok? r)))
        ;; the skipped debit shows as a success without it, or as a reversal of a debit never made
        (is (re-find #"Invariant Inv_(success|reversal)_only_after_debit is violated\." (str (:error r))))))))

