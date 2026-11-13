(ns voucha.model-apalache-test
  "The typed, abstracted TLA+ specification for Apalache: generated from the
  artifact and the schemas, one action per transition, and, when Apalache is
  installed, type-checked and run through the first induction check. Apalache
  is not assumed by CI."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.example.order :as order]
            [voucha.example.travel :as travel]
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


(deftest the-queue-order-after-a-guarded-auto-transition-is-a-rule
  ;; Once the batch has paid (entered only by the screening-complete auto transition), its transactions
  ;; are at or past screened, and an event a transaction queued after an auto request, or that survives
  ;; the batch consuming one, is one emitted from there: not a screening result from before.
  (let [tla (get (apa/generate sepa config) "file_batch_transaction_typed.tla")]
    (is (str/includes? tla "(nodes[n].type = \"Batch\" /\\ nodes[n].ranks[\"BATCH_PAY_TRANSACTIONS\"] >= 0) =>\n       /\\ \\A c \\in ChildrenIn(nodes, n) : nodes[c].type = \"Transaction\" => nodes[c].state \\in {\"TX_COMPLETED\", \"TX_FAILED\", \"TX_PAYING\", \"TX_PAY_RETRY_WAIT\", \"TX_SCREENED\"}"))
    (is (str/includes? tla "=> queue[l].evt \\in {\"TransactionFailedSettled\", \"TransactionPaid\"}"))
    (is (str/includes? tla "=> ((nodes[n].prev = \"BATCH_SCREEN_TRANSACTIONS\" /\\ nodes[n].state = \"BATCH_PAY_TRANSACTIONS\") \\/ ~AutoOnlyEdge(\"Batch\", nodes[n].prev, nodes[n].state))"))
    (is (str/includes? tla "(nodes[n].type = \"Batch\" /\\ nodes[n].ranks[\"BATCH_COMPLETED\"] >= 0) =>\n       /\\ \\A c \\in ChildrenIn(nodes, n) : nodes[c].type = \"Transaction\" => nodes[c].state \\in {\"TX_COMPLETED\", \"TX_FAILED\"}")
        "settlement's target is entered by nothing else either; its children are terminal and queue nothing")
    (is (str/includes? tla "t = \"Transaction\" /\\ ev = \"TransactionReady\" -> (ranks[\"TX_SCREENING\"] >= 0 /\\ ranks[\"TX_SCREENED\"] >= 0)"))
    (is (str/includes? tla "(nodes[c].type = \"Transaction\" /\\ \\E s \\in {\"TX_COMPLETED\", \"TX_PAYING\", \"TX_PAY_RETRY_WAIT\"} : nodes[c].ranks[s] >= 0)\n         => \\E s \\in {\"BATCH_PAY_TRANSACTIONS\"} : nodes[n].ranks[s] >= 0"))
    (is (str/includes? tla "AutoOnlyEdge(t, a, b) == (t = \"Batch\" /\\ a = \"BATCH_PAY_TRANSACTIONS\" /\\ b = \"BATCH_COMPLETED\")"))
    (is (str/includes? tla "ranks: Str -> Int") "the order of first entry is a history variable")
    (is (str/includes? tla "/\\ r[\"TX_SCREENED\"] >= 0 => nodes[n].state \\in {\"TX_COMPLETED\", \"TX_FAILED\", \"TX_PAYING\", \"TX_PAY_RETRY_WAIT\", \"TX_SCREENED\"} /\\ (nodes[n].prev = \"\" \\/ nodes[n].state = \"TX_SCREENED\" \\/ nodes[n].prev \\in {\"TX_COMPLETED\", \"TX_FAILED\", \"TX_PAYING\", \"TX_PAY_RETRY_WAIT\", \"TX_SCREENED\"})\n              /\\ ((r[\"TX_SCREENING\"] >= 0 /\\ r[\"TX_SCREENING\"] < r[\"TX_SCREENED\"]))")
        "a history is one walk from the initial state: every state after the first entered from one of lower rank")
    (is (str/includes? tla "nodes = [i \\in DOMAIN g |-> [g[i] EXCEPT !.ranks = [s \\in AllStates |-> Gen(1)]]]")
        "the history is drawn over the whole state set, not bounded by the node bound")))

(deftest a-skeleton-pins-the-shape-the-step-check-starts-from
  (let [order (compiler/compile-definition order/definition)
        order-config {:root-type "Order" :first-event "OrderPlaced" :first-payload {:customer "C-9"}
                      :children {"Item" 2} :max-repeat 2 :allow-drop false
                      :bounds {:nodes 2 :queue 3 :effects 3} :skeleton [["Order" nil] ["Item" 0] ["Item" 0]]}
        tla (get (apa/generate order order-config) "order_typed.tla")]
    (is (str/includes? tla "Skeleton == DOMAIN nodes = 0..2 /\\ nextId = 3 /\\ nodes[0].type = \"Order\" /\\ nodes[0].parent = -1 /\\ nodes[1].type = \"Item\" /\\ nodes[1].parent = 0 /\\ nodes[2].type = \"Item\" /\\ nodes[2].parent = 0"))
    (is (str/includes? tla "NodeBound == 3\nQueueBound == 3") "the skeleton sets the node bound")
    (is (str/includes? tla "IndInit == TypeOK /\\ Skeleton /\\ IndInv"))
    (is (str/includes? (get (apa/generate sepa config) "file_batch_transaction_typed.tla") "Skeleton == TRUE")
        "without one, any shape within the bounds")
    (when (apa/apalache)
      (let [dir (str "tmp/apa-test/skel-" (System/nanoTime))]
        (apa/write! dir (apa/generate order order-config))
        (let [tc (apa/typecheck! dir "order_typed")] (is (:ok? tc) (:out tc)))))))

(deftest what-one-branch-of-a-plan-produces-is-never-present-with-the-other-branch-s
  ;; The travel booking's request either searches or, with nothing to book, tells itself so:
  ;; the search effect and the self-emitted event are never present together. The rule needs a
  ;; transition that fires once per node; SEPA's branching plans are all self-emit against
  ;; self-emit, which the queue simulation already covers, so SEPA gets no rule.
  (let [tla (get (apa/generate (compiler/compile-definition travel/definition)
                               {:root-type "Trip" :first-event "ReservationRequested" :first-payload {:client "C1"}
                                :children {"Reservation" 2} :max-repeat 2 :allow-drop false})
                 "travel_booking_typed.tla")]
    (is (str/includes? tla "BranchExclusion ==\n  /\\ \\A n \\in DOMAIN nodes : nodes[n].type = \"Trip\" =>\n       ~(\\E e \\in DOMAIN effects : effects[e].owner = n /\\ effects[e].spec = \"inventory.search\")\n       \\/ ~(\\E j \\in DOMAIN queue : queue[j].target = n /\\ queue[j].source = n /\\ queue[j].evt = \"NothingToBook\")"))
    (is (str/includes? tla "BranchPresence ==\n  /\\ \\A n \\in DOMAIN nodes : (nodes[n].type = \"Trip\" /\\ nodes[n].state = \"TRIP_SEARCHING\") =>\n       (\\E e \\in DOMAIN effects : effects[e].owner = n /\\ effects[e].spec \\in {\"inventory.search\"} /\\ Pending(e))\n       \\/ (\\E j \\in DOMAIN queue : queue[j].target = n /\\ queue[j].source = n /\\ queue[j].evt \\in {\"NothingToBook\"})")
        "a node in the state the branching plan enters holds one branch's products")
    (let [awaiting (subs tla (str/index-of tla "AwaitingStates(t, spec) ==") (str/index-of tla "\n\\*" (str/index-of tla "AwaitingStates(t, spec) ==")))]
      (is (not (str/includes? awaiting "spec = \"inventory.search\" -> {\"TRIP_SEARCHING\"")) "the conditional call does not make searching an awaiting state"))
    (is (str/includes? tla "t = \"Trip\" /\\ spec = \"client.offer\" -> {\"TRIP_OFFERED\"}") "an unconditional call does")
    (is (str/includes? (get (apa/generate sepa config) "file_batch_transaction_typed.tla") "BranchExclusion ==\n  TRUE"))))
