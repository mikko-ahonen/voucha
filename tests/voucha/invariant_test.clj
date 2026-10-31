(ns voucha.invariant-test
  "Declarative business invariants, the compiler's part: the forms are
  validated against the definition, each property gets its class, predicates
  are inlined without capture, and the examples' properties compile."
  (:require [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.example.order :as order]
            [voucha.invariant :as invariant]
            [voucha.profiles.file-batch-transaction :as fbt]))

(defn- errors-of [d] (:errors (compiler/validate d)))
(defn- with-invariant [d name prop] (assoc-in d [:invariants name] prop))
(defn- messages [d] (mapv :message (compiler/validate d)))

(deftest the-examples-properties-compile-with-their-classes
  (let [sepa (:specs (compiler/compile-definition fbt/definition))
        order* (:specs (compiler/compile-definition order/definition))]
    (is (= #{:provable} (set (map :class (vals (:invariants sepa))))) "every SEPA property is provable")
    (is (= {:one-charge-per-order :provable :shipped-is-paid :provable
            :charged-total-is-the-lines :checkable :cancelled-holds-nothing :provable}
           (into {} (map (fn [[n v]] [n (:class v)])) (:invariants order*))))
    (testing "predicates are inlined, renamed apart, and gone from the artifact"
      (is (nil? (:predicates sepa)))
      (is (= [:forall :a [:nodes "Attempt" :in ["ATTEMPT_SUCCEEDED"]]
              [:exists :e_1 [:effects "account.debit" :owner [:var :a] :status ["COMPLETED"]]]]
             (get-in sepa [:invariants :success-only-after-debit :property]))))))

(deftest every-name-is-checked-against-the-definition
  (let [d fbt/definition
        bad (fn [prop] (messages (with-invariant d :p prop)))]
    (is (some #(re-find #"unknown node type \"Payment\"" %) (bad [:forall :x [:nodes "Payment"] [:root? [:var :x]]])))
    (is (some #(re-find #"state \"TX_LOST\" is not a state of \"Transaction\"" %) (bad [:forall :x [:nodes "Transaction" :in ["TX_LOST"]] [:root? [:var :x]]])))
    (is (some #(re-find #"unknown effect \"ledger.post\"" %) (bad [:exists :e [:effects "ledger.post"] [:= [:status-of [:var :e]] "COMPLETED"]])))
    (is (some #(re-find #"unknown effect status \"DONE\"" %) (bad [:exists :e [:effects "account.debit" :status ["DONE"]] [:= 1 1]])))
    (is (some #(re-find #"field :amout is not in the :data schema of \"Transaction\"" %) (bad [:forall :t [:nodes "Transaction"] [:= [:data-of [:var :t] :amout] 1]])))
    (is (some #(re-find #"counter :tx-lost is not a counter of \"Batch\"" %) (bad [:forall :b [:nodes "Batch"] [:= [:counter-of [:var :b] :tx-lost] 0]])))
    (is (some #(re-find #"unknown predicate :paid\?" %) (bad [:forall :a [:nodes "Attempt"] [:pred :paid? [:var :a]]])))
    (is (some #(re-find #"unbound variable :y" %) (bad [:forall :x [:nodes "Attempt"] [:root? [:var :y]]])))
    (is (some #(re-find #"a property must be a boolean form" %) (bad [:count [:nodes "Attempt"]])))
    (is (some #(re-find #"is a plan expression" %) (bad [:forall :x [:nodes "Attempt"] [:= [:data :amount] 1]])))
    (is (empty? (messages d)) "the profile's own properties are clean")))

(deftest kinds-are-checked
  (let [d fbt/definition
        bad (fn [prop] (messages (with-invariant d :p prop)))]
    (is (some #(re-find #":status-of needs an effect" %) (bad [:forall :x [:nodes "Attempt"] [:= [:status-of [:var :x]] "COMPLETED"]])))
    (is (some #(re-find #":state-of needs a node" %) (bad [:forall :e [:effects "account.debit"] [:= [:state-of [:var :e]] "X"]])))
    (is (some #(re-find #":parent-of must be guarded" %) (bad [:forall :x [:nodes "Attempt"] [:= [:state-of [:parent-of [:var :x]]] "TX_PAYING"]])))
    (is (empty? (bad [:forall :x [:nodes "Attempt"] [:implies [:not [:root? [:var :x]]] [:= [:state-of [:parent-of [:var :x]]] "TX_PAYING"]]]))
        "guarded by :root? in an :implies")
    (is (empty? (bad [:forall :x [:nodes "Attempt"] [:if [:root? [:var :x]] true [:= [:state-of [:parent-of [:var :x]]] "TX_PAYING"]]]))
        "guarded by :root? in an :if")))

(deftest predicates-may-use-one-another-but-not-in-a-cycle
  (let [d (-> fbt/definition
              (assoc :predicates {:a? [:fn :x [:pred :b? [:var :x]]]
                                  :b? [:fn :x [:pred :c? [:var :x]]]
                                  :c? [:fn :x [:pred :a? [:var :x]]]}))]
    (is (some #(re-find #"cycle: :a\? -> :b\? -> :c\? -> :a\?" %) (messages d)))))

(deftest classes-follow-the-forms
  (let [classes (fn [prop] (get-in (invariant/check (with-invariant fbt/definition :p prop)) [:classes :p]))]
    (is (= :provable (classes [:forall :t [:nodes "Transaction"] [:< [:data-of [:var :t] :attempts] 4]])) "a scalar field")
    (is (= :checkable (classes [:forall :b [:nodes "Batch"] [:= [:count [:nodes "Transaction" :children-of [:var :b]]] 1]])) "a count")
    (is (= :checkable (classes [:forall :f [:nodes "File"] [:= [:count [:data-of [:var :f] :batches]] 0]])) "a collection field")
    (is (= :checkable (classes [:when-quiescent [:forall :f [:nodes "File"] [:root? [:var :f]]]])) "when quiescent")
    (is (= :provable (classes [:forall :b [:nodes "Batch" :in ["BATCH_COMPLETED"]] [:none :t [:nodes "Transaction" :children-of [:var :b]] [:= [:state-of [:var :t]] "TX_NEW"]]])))))

;; --- the evaluator ------------------------------------------------------------------------

(def ^:private order-specs (:specs (compiler/compile-definition order/definition)))

(defn- order-world [& {:keys [charges total items state] :or {charges [["c1" "COMPLETED"]] total 20 items [{:qty 2 :price 10}] state "ORDER_SHIPPING"}}]
  {:nodes {"O1" {:node-id "O1" :parent-node-id nil :node-type "Order" :local-state state
                 :local-data {:total total :items items :stock-release-pending false}}
           "O1-L1" {:node-id "O1-L1" :parent-node-id "O1" :node-type "Item" :local-state "ITEM_RESERVED" :local-data {}}}
   :effects (into [{:id "r1" :owner-node-id "O1-L1" :effect-name "inventory.reserve" :status "COMPLETED"}]
                  (map (fn [[id st]] {:id id :owner-node-id "O1" :effect-name "payment.charge" :status st}) charges))
   :quiescent? true})

(deftest the-evaluator-runs-the-examples-properties-over-a-world
  (testing "a shipped order with one completed charge and the right total satisfies every property"
    (is (= [] (invariant/violations order-specs (order-world)))))
  (testing "a second charge request while the first may have been acted on is the double charge, named by its order"
    (let [vs (invariant/violations order-specs (order-world :charges [["c1" "TIMED_OUT"] ["c2" "COMPLETED"]]))]
      (is (= [:one-charge-per-order] (map :property vs)))
      (is (= "O1" (:node-id (first vs))))
      (is (re-find #"one-charge-per-order fails for o = O1 \(Order ORDER_SHIPPING\)" (:detail (first vs))))))
  (testing "a cancelled or definitely failed request does not count: a retry after it is not a double charge"
    (is (= [] (invariant/violations order-specs (order-world :charges [["c1" "CANCELLED"] ["c2" "COMPLETED"]]))))
    (is (= [] (invariant/violations order-specs (order-world :charges [["c1" "FAILED"] ["c2" "COMPLETED"]])))))
  (testing "shipping without a completed charge"
    (is (= [:shipped-is-paid] (map :property (invariant/violations order-specs (order-world :charges [["c1" "FAILED"]]))))))
  (testing "the checkable property: a total that is not the lines'"
    (let [vs (invariant/violations order-specs (order-world :total 25))]
      (is (= [:charged-total-is-the-lines] (map :property vs)))
      (is (= :checkable (:class (first vs)))))))

(deftest the-evaluator-covers-every-form
  (let [w (order-world)
        holds? (fn [prop] (invariant/holds? w prop))]
    (is (holds? [:forall :o [:nodes "Order"] [:root? [:var :o]]]))
    (is (holds? [:forall :l [:nodes "Item"] [:implies [:not [:root? [:var :l]]] [:= [:state-of [:parent-of [:var :l]]] "ORDER_SHIPPING"]]]))
    (is (holds? [:forall :l [:nodes "Item" :under [:var :o]] [:= [:type-of [:var :l]] "Item"]]) "an unbound :under reads nil and matches nothing")
    (is (holds? [:forall :o [:nodes "Order"] [:exactly-one :l [:nodes "Item" :children-of [:var :o]]]]))
    (is (holds? [:forall :o [:nodes "Order"] [:none :l [:nodes "Item" :under [:var :o] :in ["ITEM_UNAVAILABLE"]]]]))
    (is (holds? [:forall :e [:effects "inventory.reserve" :owner-under [:var :o]] [:= [:status-of [:var :e]] "COMPLETED"]]))
    (is (holds? [:forall :e [:effects "payment.charge"] [:= [:type-of [:owner-of [:var :e]]] "Order"]]))
    (is (holds? [:forall :e [:effects "payment.charge" :status ["COMPLETED"]] [:= [:effect-of [:var :e]] "payment.charge"]]))
    (is (holds? [:forall :o [:nodes "Order"] [:= [:count [:nodes "Item" :children-of [:var :o]]] 1]]))
    (is (holds? [:forall :o [:nodes "Order"] [:= [:sum :l [:data-of [:var :o] :items] [:* [:get [:var :l] :qty] [:get [:var :l] :price]]] 20]]))
    (is (holds? [:forall :o [:nodes "Order"] [:= [:counter-of [:var :o] :items-reserved] 0]]) "an unset counter reads 0")
    (is (holds? [:when-quiescent [:forall :o [:nodes "Order"] [:nil? [:data-of [:var :o] :tracking]]]]))
    (is (holds? [:forall :o [:nodes "Order"] [:if [:> [:data-of [:var :o] :total] 10] [:>= [:+ 1 1] 2] false]]))
    (is (invariant/holds? (assoc w :quiescent? false) [:when-quiescent false]) "evaluated only when quiescent: here it is not, so it passes")
    (is (not (holds? [:when-quiescent false])))))
