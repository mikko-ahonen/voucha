(ns voucha.compiler-mutation-test
  "T1: mutations of the reference definition that break a rule must fail to
  compile with an error naming that rule. The table is the specification of
  what the compiler guarantees; a mutation that compiles is a hole."
  (:require [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.profiles.file-batch-transaction :as fbt]))

(defn- codes [d] (set (map :code (compiler/validate d))))

(defn- update-transition [d pred f]
  (update d :transitions (fn [ts] (mapv #(if (pred %) (f %) %) ts))))

(def ^:private mutations
  [["a state removed from its node type"
    #(update-in % [:node-types "Attempt" :states] (fn [ss] (vec (remove #{"ATTEMPT_INST_WAITING"} ss))))
    :unknown-state]
   ["a transition on an undeclared event"
    #(update-transition % (fn [t] (= "FileReceived" (:on t))) (fn [t] (assoc t :on "Nobody")))
    :unknown-event]
   ["a transition emitting an undeclared event"
    #(update-transition % (fn [t] (= 'tx/paid (:handler t))) (fn [t] (assoc t :plan [[:emit "Nobody"]])))
    :unknown-event]
   ["a transition that creates an effect without a handler to decide it"
    #(update-transition % (fn [t] (= 'file/received (:handler t)))
                        (fn [t] (-> t (dissoc :handler :plan) (assoc :effects ["file.writeReceive"] :await ["ReceiveFileWritten"]))))
    :missing-handler]
   ["a transition out of a terminal state"
    #(update % :transitions conj {:node-type "Attempt" :from "ATTEMPT_FAILED" :on "AttemptFailed"
                                  :to "ATTEMPT_NEW" :handler 'attempt/failed})
    :invalid-transition]
   ["two transitions claiming the same (type, from, event)"
    #(update % :transitions conj (first (filter (fn [t] (= 'file/received (:handler t))) (:transitions %))))
    :duplicate-transition]
   ["an effect emitted by a node type not permitted to"
    #(update-transition % (fn [t] (= 'file/received (:handler t))) (fn [t] (assoc t :plan [[:call "rail.inst.send"]])))
    :effect-not-permitted]
   ["an unknown effect"
    #(update-transition % (fn [t] (= 'file/received (:handler t))) (fn [t] (assoc t :plan [[:call "ghost.effect"]])))
    :unknown-effect]
   ["an effect with no version"
    #(update-in % [:effects "screening.check"] dissoc :version)
    :missing-effect-version]
   ["an effect with no idempotency key"
    #(update-in % [:effects "screening.check"] dissoc :idempotency-key)
    :missing-idempotency-key]
   ["a query effect with no status query"
    #(update-in % [:effects "rail.sct.submit"] dissoc :status-query)
    :missing-status-query]
   ["a state no transition reaches"
    #(update-in % [:node-types "Attempt" :states] conj "ATTEMPT_LIMBO")
    :unreachable-state]
   ["a terminal parent state allowing a non-terminal child"
    #(assoc-in % [:node-types "Batch" :child-states-allowed "BATCH_COMPLETED" "Transaction"] ["TX_PAYING"])
    :terminal-parent-allows-nonterminal-child]
   ["a constraint reading a counter the type does not declare"
    #(update-transition % (fn [t] (= 'file/write-result (:handler t)))
                        (fn [t] (assoc t :requires [{:constraint :counter :counter :ghost :op :>= :value 1}])))
    :missing-counter]
   ["a constraint of an unknown type"
    #(update-transition % (fn [t] (= 'file/write-result (:handler t)))
                        (fn [t] (assoc t :requires [{:constraint :telepathy}])))
    :invalid-constraint]
   ["a routing rule for an undeclared event"
    #(update % :routing conj {:event "Nobody" :from-node-type "Batch" :to-node-type "File" :scope :upward})
    :unknown-event]
   ["two routing rules for one (event, source type)"
    #(update % :routing conj (first (:routing %)))
    :ambiguous-event-route]
   ["a transition guarding on an undeclared policy"
    #(update-transition % (fn [t] (= 'file/received (:handler t))) (fn [t] (assoc t :policies ["ghost"])))
    :unknown-policy]
   ["a node type whose initial state is not a state"
    #(assoc-in % [:node-types "Attempt" :initial-state] "ELSEWHERE")
    :missing-initial-state]
   ["a node type that may contain an unknown type"
    #(update-in % [:node-types "File" :may-contain] conj "Ghost")
    :unknown-node-type]
   ;; decision D14: stuck-by-construction
   ["a non-terminal state with no path to a terminal"
    #(-> % (update-in [:node-types "Attempt" :states] conj "ATTEMPT_LIMBO")
           (update :transitions conj {:node-type "Attempt" :from "ATTEMPT_NEW" :on "AttemptFailed"
                                      :to "ATTEMPT_LIMBO" :handler 'attempt/failed}))
    :dead-end-state]
   ["an effect outcome no reachable transition consumes"
    #(update % :transitions (fn [ts] (vec (remove (fn [t] (and (= "Transaction" (:node-type t))
                                                                (= "TransactionScreeningFailed" (:on t))))
                                                  ts))))
    :unrouted-effect-outcome]])

(deftest the-reference-definition-compiles
  (is (empty? (codes fbt/definition))))

(deftest every-breaking-mutation-is-named-by-the-compiler
  (doseq [[what mutate expected] mutations]
    (testing what
      (is (contains? (codes (mutate fbt/definition)) expected)
          (str what ": expected " expected ", got " (pr-str (codes (mutate fbt/definition))))))))
