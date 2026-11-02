(ns voucha.auto-transition-test
  "A transition marked :auto fires on entering its state when its rules hold,
  so a decision such as 'are all the children done?' is a state with declared
  conditions, not a check in handler code. A transition with no handler only
  moves the node; the kernel supplies the plan."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.kernel :as kernel]
            [voucha.plan :as plan]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private definition
  "A parent that starts its children, then decides: P_DECIDE goes to P_DONE
  once every child has finished, otherwise to P_WAITING; every child's finish
  brings it back to P_DECIDE to decide again."
  {:profile {:name "auto" :version "v1"}
   :events #{"Start" "AllDone" "Pending" "ChildDone" "ChildFinish"}
   :effects {}
   :node-types {"Parent" {:may-contain ["Child"] :may-emit-effects []
                          :initial-state "P_NEW"
                          :states ["P_NEW" "P_DECIDE" "P_WAITING" "P_DONE"]
                          :terminal-states ["P_DONE"] :failure-state "P_DONE"
                          :counters {:done {:child-type "Child" :when-child-enters ["C_DONE"]}}
                          :hosts {"Child" "P_WAITING"}}
                "Child" {:may-contain [] :may-emit-effects []
                         :initial-state "C_NEW" :states ["C_NEW" "C_DONE"]
                         :terminal-states ["C_DONE"] :failure-state "C_DONE"}}
   :transitions [{:node-type "Parent" :from "P_NEW" :on "Start" :to "P_DECIDE" :handler 'p/start}
                 {:node-type "Parent" :from "P_DECIDE" :on "AllDone" :to "P_DONE" :auto true
                  :requires [{:constraint :counters-cover-children :counters [:done]}]}
                 {:node-type "Parent" :from "P_DECIDE" :on "Pending" :to "P_WAITING" :auto true}
                 {:node-type "Parent" :from "P_WAITING" :on "ChildDone" :to "P_DECIDE"}
                 {:node-type "Parent" :from "P_DECIDE" :on "ChildDone" :to "P_DECIDE"}
                 {:node-type "Child" :from "C_NEW" :on "ChildFinish" :to "C_DONE" :handler 'c/finish :emit ["ChildDone"]}]
   :routing [{:event "ChildDone" :from-node-type "Child" :to-node-type "Parent" :scope :upward :on-unsatisfied :ignore}]})

(def ^:private handlers
  {'p/start (fn [_ctx ev]
              (reduce (fn [p i] (-> p (plan/child (str "C" i) "Child") (plan/emit-to (str "C" i) "ChildFinish")))
                      (-> (plan/plan) (plan/state "P_DECIDE"))
                      (range (get-in ev [:payload :n]))))
   'c/finish (fn [_ctx _ev] (-> (plan/plan) (plan/state "C_DONE") (plan/emit "ChildDone")))})

(def ^:private rt {:handlers handlers :event {:max-attempts 3}})

(defn- start! [n]
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "P" :root-node-type "Parent"})
      (kernel/submit-event! tx {:event-id "start" :tree-id "T" :target-node-id "P"
                                :event-type "Start" :payload {:n n}}))
    (kernel/drain! *ds* rt)))

(defn- state-of [nid] (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" nid))))
(defn- events [] (db/in-tx [tx *ds*] (db/execute! tx ["SELECT event_type, status, payload FROM event_log ORDER BY received_at, event_id"])))

(deftest the-definition-compiles-without-handlers-for-moves
  (is (empty? (compiler/validate definition)) (compiler/explain (compiler/validate definition))))

(deftest a-decision-state-re-decides-on-every-child-and-finishes-when-all-are-done
  (start! 2)
  (is (= "P_DONE" (state-of "P")))
  (is (= "C_DONE" (state-of "C0")))
  (testing "the decision was taken by the kernel from the declared rules, not by code"
    (let [types (map :event_type (events))]
      (is (some #{"Pending"} types) "with children still working, the auto transition chose P_WAITING")
      (is (some #{"AllDone"} types) "once the counter covered the children, it chose P_DONE")))
  (testing "a transition without a handler is recorded as the kernel's move"
    (is (some #(= "kernel/move" (:handler-name %))
              (db/in-tx [tx *ds*] (tlog/history tx "T" "P"))))))

(deftest with-nothing-to-wait-for-the-decision-is-immediate
  (start! 0)
  (is (= "P_DONE" (state-of "P")) "no children: the aggregate rule holds on entry and nothing waits")
  (is (not-any? #(= "Pending" (:event_type %)) (events))))

(deftest an-auto-event-that-arrives-after-the-node-moved-on-is-ignored-not-rejected
  (start! 1)
  (is (= "P_DONE" (state-of "P")))
  (is (not-any? #(= "REJECTED" (:status %)) (events))
      "every kernel-raised event was processed or ignored; none was an error"))
