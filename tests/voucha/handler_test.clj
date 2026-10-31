(ns voucha.handler-test
  "The plan IR: every op and expression interpreted, and every way a plan can be
  malformed caught without evaluating it."
  (:require [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.handler :as handler]
            [voucha.profiles.file-batch-transaction :as fbt]))

(def ^:private ctx {:node {:node-id "B1" :node-type "Batch" :local-state "BATCH_NEW"
                           :local-data {:amount 7 :attempts 2 :tag "x" :counters {:tx-paid 3}
                                        :items [{:id "a" :n 1} {:id "b" :n 2}]}}})
(def ^:private ev {:event-type "E" :payload {:amount 100 :lines [{:id "L1" :qty 2} {:id "L2" :qty 1}] :name "f"}})

(defn- run [plan] ((handler/plan-fn {:to "NEXT" :plan plan} {}) ctx ev))

(deftest expressions-read-the-context-and-the-event
  (let [e (fn [x] (handler/eval-expr {:data (get-in ctx [:node :local-data]) :payload (:payload ev)
                                      :node-id "B1" :vars {:v 5} :opaques {'t/double (fn [n] (* 2 n))}}
                                     x))]
    (is (= 7 (e [:data :amount])))
    (is (= 3 (e [:data :counters :tx-paid])))
    (is (= 3 (e [:counter :tx-paid])))
    (is (= 0 (e [:counter :nope])))
    (is (= 100 (e [:payload :amount])))
    (is (= "B1" (e [:node-id])))
    (is (= 5 (e [:var :v])))
    (is (= 2 (e [:count [:payload :lines]])))
    (is (= 3 (e [:sum [:payload :lines] :qty])))
    (is (= ["L1" "L2"] (e [:map [:payload :lines] :id])))
    (is (= {:n 1} (e [:select [:get [:data :items] 0] [:n]])) "select on a record")
    (is (= "B1-A2" (e [:str [:node-id] "-A" [:data :attempts]])))
    (is (= 9 (e [:+ [:data :amount] 2])))
    (is (= 5 (e [:- [:data :amount] 2])))
    (is (true? (e [:= [:data :tag] "x"])))
    (is (true? (e [:< [:data :attempts] 3])))
    (is (false? (e [:< [:data :missing] 3])) "an ordering on nil is false, not an error")
    (is (false? (e [:> "7" 0])) "an ordering on a non-number is false")
    (is (true? (e [:and [:= 1 1] [:not [:nil? [:data :tag]]]])))
    (is (= 0 (e [:or [:data :missing] 0])) ":or is the default")
    (is (= 7 (e [:or [:data :amount] 0])))
    (is (= 14 (e [:opaque 't/double [:data :amount]])))
    (is (= {:a 7 :b {:c "f"}} (e {:a [:data :amount] :b {:c [:payload :name]}})) "a map is a map of expressions")))

(deftest ops-build-the-same-plan-a-code-handler-would
  (let [p (run [[:set [:x] [:payload :amount]]
                [:inc [:attempts] 1]
                [:call "screening.check" {:amount [:data :amount]}]
                [:store "file.writeResult" {:completed [:counter :tx-paid]}]
                [:await "Done"]
                [:emit "Up" {:n 1}]
                [:emit-to :self "Again"]
                [:each :l [:payload :lines]
                 [:child [:get [:var :l] :id] "Item"]
                 [:emit-to [:get [:var :l] :id] "Go" [:var :l]]]
                [:let {:aid [:str [:node-id] "-A" [:data :attempts]]}
                 [:child [:var :aid] "Attempt"]
                 [:emit-to [:var :aid] "AttemptCommanded"]]
                [:when [:= [:data :tag] "x"] [:set [:seen] true]]
                [:when [:= [:data :tag] "y"] [:set [:unseen] true]]
                [:if [:< [:data :attempts] 3] [[:emit-to :self "Retry"]] [[:emit-to :self "GiveUp"]]]])]
    (is (= "NEXT" (:state p)) "the target state is the transition's :to")
    (is (= [(list 'assoc-in [:x] 100) (list 'inc-in [:attempts] 1) (list 'assoc-in [:seen] true)] (:patch p)))
    (is (= [{:effect "screening.check" :request {:amount 7}}] (:call p)))
    (is (= [{:effect "file.writeResult" :request {:completed 3}}] (:store p)))
    (is (= ["Done"] (:await p)))
    (is (= [{:node-id "L1" :node-type "Item"} {:node-id "L2" :node-type "Item"} {:node-id "B1-A2" :node-type "Attempt"}]
           (:child p)))
    (is (= [{:event-type "Up" :payload {:n 1}}
            {:event-type "Again" :payload {} :target-node-id "B1"}
            {:event-type "Go" :payload {:id "L1" :qty 2} :target-node-id "L1"}
            {:event-type "Go" :payload {:id "L2" :qty 1} :target-node-id "L2"}
            {:event-type "AttemptCommanded" :payload {} :target-node-id "B1-A2"}
            {:event-type "Retry" :payload {} :target-node-id "B1"}]
           (:emit p)))))

(deftest what-a-plan-can-do-is-read-without-running-it
  (let [t {:plan [[:call "screening.check"] [:await "Cleared"]
                  [:if [:< [:data :n] 3]
                   [[:emit-to :self "Retry"] [:each :c [:data :kids] [:child [:var :c] "Attempt"] [:emit-to [:var :c] "Go"]]]
                   [[:emit-to :self "GiveUp"] [:emit "Settled"]]]]}]
    (is (= {:emit ["Retry" "GiveUp" "Settled"] :effects ["screening.check"] :await ["Cleared"]
            :creates ["Attempt"] :commands ["Go"]}
           (handler/derive t))
        "alternatives in declaration order, so the give-up event is last")
    (is (= {} (handler/derive {:plan []})))))

(deftest malformed-plans-are-rejected-by-the-compiler
  (let [with (fn [plan] (update fbt/definition :transitions
                                (fn [ts] (mapv #(if (= 'tx/paid (:handler %)) (assoc % :plan plan) %) ts))))
        codes (fn [plan] (set (map :code (compiler/validate (with plan)))))
        messages (fn [plan] (map :message (compiler/validate (with plan))))]
    (is (empty? (codes [[:emit "TransactionPaid"]])))
    (is (contains? (codes [[:teleport "X"]]) :invalid-plan))
    (is (contains? (codes [[:set :x 1]]) :invalid-plan) "a path is a vector of keywords")
    (is (contains? (codes [[:emit-to :self "TransactionPaid" {:n [:var :nope]}]]) :invalid-plan) "unbound variable")
    (is (contains? (codes [[:emit "TransactionPaid" {:n [:frobnicate 1]}]]) :invalid-plan))
    (is (contains? (codes [[:if [:= 1 1] [:emit "TransactionPaid"] [[:emit "TransactionPaid"]]]]) :invalid-plan) ":if branches are vectors of ops")
    (is (some #(re-find #"unknown op :teleport" %) (messages [[:teleport "X"]])))
    (testing "the plan's effects and events go through the same checks as declarations"
      (is (contains? (codes [[:emit "Nobody"]]) :unknown-event))
      (is (contains? (codes [[:call "rail.inst.send"]]) :effect-not-permitted) "a Transaction may not send on the rail")
      (is (contains? (codes [[:child [:node-id] "File"]]) :illegal-child-type)))
    (testing "a declaration beside a plan must agree with it"
      (is (contains? (set (map :code (compiler/validate
                                      (update fbt/definition :transitions
                                              (fn [ts] (mapv #(if (= 'tx/paid (:handler %)) (assoc % :emit ["TransactionFailedSettled"]) %) ts))))))
                     :plan-mismatch)))))

(deftest the-reference-profile-is-all-plans
  (let [{:keys [specs]} (compiler/compile-definition fbt/definition)
        with-handler (filter :handler (:transitions specs))]
    (is (every? :plan with-handler) "every handler of the SEPA example is a plan")
    (is (= ["TransactionRetry" "TransactionGaveUp"]
           (:emit (compiler/transition-for specs "Transaction" "TX_PAYING" "AttemptFailed")))
        "derived keys are in the artifact")
    (is (= ["Attempt"] (:creates (compiler/transition-for specs "Transaction" "TX_SCREENED" "TransactionPayCommanded"))))
    (is (= 'plan/X.A.E (handler/name-of {:plan [] :node-type "X" :from "A" :on "E"})))
    (is (= 'tx/paid (handler/name-of {:plan [] :handler 'tx/paid})))
    (is (= 'kernel/move (handler/name-of {})))
    (is (= ['tx/paid] (handler/conflicts fbt/definition {'tx/paid (fn [_ _] nil)})))))

(deftest the-extractor-s-forms-evaluate-as-java-would
  (let [e (fn [x] (handler/eval-expr {:data {:items [{:qty 2 :price 10} {:qty 1 :price 25}] :n nil}
                                      :payload {} :node-id "O1" :vars {} :opaques {}} x))]
    (is (= 45 (e [:sum :line [:data :items] [:* [:or [:get [:var :line] :qty] 0] [:or [:get [:var :line] :price] 0]]]))
        "a sum of a term over the lines")
    (is (= 6 (e [:* 2 3])))
    (is (= "b" (e [:if [:nil? [:data :n]] "b" "a"])) "a value-level if")
    (is (= 0 (e [:or [:data :n] 0])))))
