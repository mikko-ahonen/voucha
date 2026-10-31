(ns voucha.model-apalache-test
  "The typed, abstracted TLA+ specification for Apalache: generated from the
  artifact and the schemas, one action per transition, and, when Apalache is
  installed, type-checked and run through the first induction check. Apalache
  is not assumed by CI."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.example.order :as order]
            [voucha.model-apalache :as apa]
            [voucha.profiles.file-batch-transaction :as fbt]))

(def ^:private sepa (compiler/compile-definition fbt/definition))
(def ^:private config {:root-type "File" :root-id "F1" :first-event "FileReceived"
                       :first-payload {:name "f" :batches [{:batch-id "B1" :control-sum 100 :count 1
                                                            :transactions [{:tx-id "T1" :amount 100 :payment-type "INST"}]}]}
                       :children {} :max-repeat 3 :allow-drop false})

(deftest the-typed-specification-follows-the-schemas
  (let [files (apa/generate sepa config)
        tla (get files "file_batch_transaction_typed.tla")]
    (is (= ["file_batch_transaction_typed.tla"] (keys files)))
    (testing "aliases from the schemas, scalars only"
      (is (re-find #"@typeAlias: data = \{ .*attempts: Int.*pay_commanded: Bool.*\};" tla))
      (is (not (str/includes? tla "transactions: Set(")) "a collection is not carried in the typed state")
      (is (re-find #"@typeAlias: payload = \{ .*control_sum: \{ some: Bool, v: Int \}" tla) "a maybe field is a record with a flag"))
    (testing "plans over typed records, abstracted"
      (is (str/includes? tla "(~(payload.control_sum).some) \\/ ((payload.control_sum).v = Gen(1))") "a :nil? test on a maybe field, and a sum over a collection as any integer")
      (is (str/includes? tla "ApaFoldSet(Step, a16, {i \\in 1..MaxChildren : i <= kids})") "a loop over records creates some number of children")
      (is (str/includes? tla "!.children = @ \\cup {[id |-> acc.next, type |-> \"Transaction\"]}") "a child takes the next fresh id")
      (is (str/includes? tla "v_t == Gen(1)") "the record a loop binds is arbitrary"))
    (testing "the definition, specialised per transition"
      (is (str/includes? tla "Match_39(ns, id, ev) == ns[id].type = \"Transaction\" /\\ ns[id].state = \"TX_SCREENED\" /\\ ev = \"TransactionPayCommanded\""))
      (is (str/includes? tla "Guards_39(ns, efs, n) == (LET other == ns[n].parent IN other /= NoId /\\ ns[other].state \\in {\"BATCH_SCREEN_TRANSACTIONS\"") "a parent-state guard as a literal")
      (is (re-find #"Guards_\d+\(ns, efs, n\) == \(\\A c \\in ChildrenIn\(ns, n\) : Counted_Batch_\w+\(ns, c\)( \\/ Counted_Batch_\w+\(ns, c\))+\)" tla) "the cover rule as a quantifier")
      (is (str/includes? tla "(nt = \"Transaction\" /\\ st = \"TX_FAILED\" /\\ ev = \"TransactionPayCommanded\")") "the ignores, as literals")
      (is (re-find #"\\/ \\E i \\in DOMAIN queue : Eligible\(i\) /\\ Fire_39\(i\)" tla) "one action per transition")
      (is (not (str/includes? tla "T[ti]")) "no table indexed symbolically"))
    (testing "the run and the invariant"
      (is (str/includes? tla "RootType == \"File\""))
      (is (str/includes? tla "FirstPayload == [DefaultPayload EXCEPT !.name = \"f\"]") "the scalar fields of the first payload")
      (is (str/includes? tla "NodeBound == 2"))
      (is (str/includes? tla "IndInv == WellFormed /\\ Accepted /\\ Properties /\\ Lemmas"))
      (is (str/includes? tla "AutoEventsFrom(t, s) == CASE") "the queued events are accepted in order, auto requests along auto transitions")
      (is (str/includes? tla "Lemmas == TRUE")))
    (is (= files (apa/generate sepa config)) "deterministic")))

(deftest the-order-example-declares-its-schemas-from-java
  ;; voucha.sdk.Schema on the profile builder: the same schemas the Clojure API declares
  (let [artifact (compiler/compile-definition order/definition)
        specs (:specs artifact)]
    (is (= {:item-id :str :sku :str :qty :int :price :int}
           (second (get-in specs [:node-types "Order" :data :items]))))
    (is (= :bool (get-in specs [:node-types "Order" :data :stock-release-pending])))
    (is (= {:customer :str :items [:set {:item-id :str :sku :str :qty :int :price :int}]}
           (get-in specs [:payloads "OrderPlaced"])))
    (let [tla (get (apa/generate artifact {:root-type "Order" :first-event "OrderPlaced" :first-payload {:customer "C-9"}
                                           :children {"Item" 2} :max-repeat 2 :allow-drop false})
                   "order_typed.tla")]
      (is (re-find #"@typeAlias: data = \{ .*charge_attempts: Int.*stock_release_pending: Bool" tla))
      (is (str/includes? tla "!.children = @ \\cup {[id |-> acc.next, type |-> \"Item\"]}") "the Java loop over lines creates children")
      (when (apa/apalache)
        (let [dir (str "tmp/apa-test/order-" (System/nanoTime))]
          (apa/write! dir {"order_typed.tla" tla})
          (is (:ok? (apa/typecheck! dir "order_typed")) "the order example's typed specification type-checks"))))))

(deftest a-definition-without-schemas-is-refused
  (let [bare (compiler/compile-definition (-> fbt/definition
                                              (update :node-types (fn [nts] (into {} (map (fn [[k v]] [k (dissoc v :data)])) nts)))
                                              (dissoc :payloads)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares no :data schemas" (apa/generate bare config)))))

(deftest the-typed-specification-type-checks-and-the-initial-state-is-in-the-invariant
  ;; The full induction takes minutes and is run by hand (docs/testing.md); here the
  ;; generated invariant must type-check and hold of the initial state.
  (if-not (apa/apalache)
    (println "  [skip] apalache-mc not found; the typed specification is generated but not checked")
    (let [dir (str "tmp/apa-test/sepa-" (System/nanoTime))
          name* (apa/module-name (:specs sepa))]
      (apa/write! dir (apa/generate sepa config))
      (let [tc (apa/typecheck! dir name*)]
        (is (:ok? tc) (:out tc)))
      (let [r (#'apa/outcome dir (#'apa/run! dir ["check" "--init=Init" "--next=Next" "--inv=IndInv" "--length=0" (str name* ".tla")]))]
        (is (:ok? r) (:out r))))))

(deftest the-provable-business-invariants-are-part-of-what-the-induction-proves
  (let [sepa-tla (get (apa/generate sepa config) "file_batch_transaction_typed.tla")
        order-tla (get (apa/generate (compiler/compile-definition order/definition)
                                     {:root-type "Order" :first-event "OrderPlaced" :first-payload {:customer "C-9"}
                                      :children {"Item" 2} :max-repeat 2 :allow-drop false})
                       "order_typed.tla")]
    (is (str/includes? sepa-tla "Properties == NoRejection /\\ ChildrenAllowed /\\ NotDead /\\ Inv_batch_settled_means_transactions_settled /\\ Inv_one_success_per_transaction /\\ Inv_paid_means_one_success /\\ Inv_reversal_only_after_debit /\\ Inv_success_only_after_debit"))
    (is (str/includes? sepa-tla "Inv_one_success_per_transaction == (\\A p_tx \\in {n_ \\in DOMAIN nodes : nodes[n_].type = \"Transaction\"} : (\\A p_a, p_a_2 \\in {n_ \\in DOMAIN nodes : nodes[n_].type = \"Attempt\" /\\ nodes[n_].parent = p_tx /\\ nodes[n_].state \\in {\"ATTEMPT_SUCCEEDED\"}} : (TRUE /\\ TRUE) => p_a = p_a_2))")
        "at most one, as a pair quantifier")
    (is (str/includes? order-tla "\\* charged-total-is-the-lines: checkable, not provable; the simulator, the models and the oracle check it"))
    (is (str/includes? order-tla "(~nodes[p_o].data.stock_release_pending)") "a scalar field read")
    (is (str/includes? order-tla "Properties == NoRejection /\\ ChildrenAllowed /\\ NotDead /\\ Inv_cancelled_holds_nothing /\\ Inv_one_charge_per_order /\\ Inv_shipped_is_paid"))))

