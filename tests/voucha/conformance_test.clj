(ns voucha.conformance-test
  "T1 kernel conformance: the real kernel and the in-memory reference model
  (`voucha.model`) must agree on every node and every outcome for random event
  sequences -- and on which plans are accepted, via a handler that returns
  seeded random plans. A divergence is a bug in one of them; the seed reproduces
  it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.model :as model]
            [voucha.plan :as plan]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.test-db :as tdb :refer [*ds*]])
  (:import (java.util Random)))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

(def ^:private tree-id "T")
(def ^:private max-attempts 3)

(defn- compiled [] (compiler/compile-definition fbt/definition))

(defn- kernel-nodes []
  (into {} (map (fn [r] [(:node_id r) {:node-type (:node_type r) :parent-node-id (:parent_node_id r)
                                       :local-state (:local_state r) :status (:status r)
                                       :local-data (voucha.codec/<-jsonb (:local_data r))}]))
        (db/in-tx [tx *ds*]
          (db/execute! tx ["SELECT node_id, node_type, parent_node_id, local_state, status, local_data
                            FROM state_node WHERE tree_id = ?" tree-id]))))

(defn- both-trees!
  "The same tree in the database and in the model: File F1 with Batch B1 and
  Transaction TX1 and Attempt A1, so random events reach every level quickly."
  []
  (let [a (compiled)]
    (db/in-tx [tx *ds*]
      (artifact/insert! tx a)
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id (:artifact-id a) :root-node-id "F1" :root-node-type "File"})
      (kernel/add-child! tx {:tree-id tree-id :parent-node-id "F1" :node-id "B1" :node-type "Batch"})
      (kernel/add-child! tx {:tree-id tree-id :parent-node-id "B1" :node-id "TX1" :node-type "Transaction"})
      (kernel/add-child! tx {:tree-id tree-id :parent-node-id "TX1" :node-id "A1" :node-type "Attempt"}))
    (-> (model/new-tree (:specs a) tree-id "F1" "File")
        (model/add-child "F1" "B1" "Batch")
        (model/add-child "B1" "TX1" "Transaction")
        (model/add-child "TX1" "A1" "Attempt"))))

;; --- a seeded event generator -----------------------------------------------------------

(defn- pick [^Random r coll] (nth (vec coll) (.nextInt r (count coll))))

(defn- payload-for [^Random r event-type]
  (case event-type
    "FileReceived" {:name "f" :batches [{:batch-id (str "B" (.nextInt r 3))
                                         :transactions [{:tx-id (str "B-T" (.nextInt r 3)) :amount (.nextInt r 50) :payment-type (pick r ["INST" "SCT"])}]}]}
    "BatchStartCommanded" {:transactions [{:tx-id (str "G-T" (.nextInt r 9)) :amount (.nextInt r 50) :payment-type (pick r ["INST" "SCT"])}]}
    "TransactionStartCommanded" {:amount (.nextInt r 50) :payment-type (pick r ["INST" "SCT"])}
    "AttemptCommanded" {:amount (.nextInt r 50) :payment-type (pick r ["INST" "SCT"])}
    {}))

(defn- next-event
  "Mostly an event the node's current state has a transition for (so the trees
  progress), sometimes any declared event (so rejections are exercised)."
  [^Random r specs m i]
  (let [node (pick r (vals (:nodes m)))
        from-here (filter #(and (= (:node-type node) (:node-type %)) (= (:local-state node) (:from %)))
                          (:transitions specs))
        event-type (if (and (seq from-here) (< (.nextDouble r) 0.7))
                     (:on (pick r from-here))
                     (pick r (:events specs)))]
    {:event-id (str "ev-" i) :tree-id tree-id :target-node-id (:node-id node)
     :event-type event-type :payload (payload-for r event-type)}))

(defn- step!
  "One external event through both. Returns [model kernel-outcomes model-outcomes]."
  [m rt handlers ev]
  (db/in-tx [tx *ds*] (kernel/submit-event! tx ev))
  (let [ko (kernel/drain! *ds* rt)
        [m mo] (model/drain (model/submit m ev) handlers max-attempts)]
    [m ko mo]))

(defn- conform! [seed steps handlers]
  (let [specs (:specs (compiled))
        rt {:handlers handlers :event {:max-attempts max-attempts}}
        r (Random. (long seed))]
    (loop [m (both-trees!) i 0]
      (when (< i steps)
        (let [ev (next-event r specs m i)
              [m ko mo] (step! m rt handlers ev)]
          (is (= (mapv :outcome mo) (mapv :outcome ko))
              (str "seed " seed " step " i " " (:event-type ev) "->" (:target-node-id ev)
                   " kernel " (pr-str (mapv (juxt :outcome :reason) ko)) " model " (pr-str (mapv (juxt :outcome :reason :error) mo))))
          (is (= (model/nodes m) (kernel-nodes))
              (str "seed " seed " step " i " " (:event-type ev) ": node states diverged"))
          (recur m (inc i)))))))

(deftest the-kernel-agrees-with-the-reference-model-on-random-event-sequences
  (doseq [seed [1 2 3 4 5]]
    (tdb/clean-db #(conform! seed 40 app/handlers))))

;; --- plan validation, fuzzed on both sides ----------------------------------------------

(defn- random-plan
  "A plan derived from the event id, so model and kernel see the same one: a
  mostly-right plan with a chance of each kind of mistake."
  [specs t ctx ev]
  (let [r (Random. (long (hash (:event-id ev))))
        nt (get-in ctx [:node :node-type])
        states (get-in specs [:node-types nt :states])
        child-types (get-in specs [:node-types nt :may-contain])
        mistake (when (< (.nextDouble r) 0.5)
                  (pick r [:wrong-state :imaginary-state :undeclared-event :forbidden-effect
                           :illegal-child :unknown-target :bad-patch]))]
    (cond-> (-> (plan/plan)
                (plan/state (case mistake
                              :wrong-state (pick r (remove #{(:to t)} states))
                              :imaginary-state "IMAGINARY"
                              (:to t)))
                (plan/patch (list 'assoc-in [:fuzz] (.nextInt r 100))))
      (= mistake :undeclared-event) (plan/emit "NobodyDeclaredThis")
      (= mistake :forbidden-effect) (plan/call "rail.inst.send" {})
      (and (= mistake :illegal-child) (seq states)) (plan/child "X" "File")
      (= mistake :unknown-target) (plan/emit-to "GHOST" (first (:events specs)))
      (= mistake :bad-patch) (update :patch conj (list 'eval [:x] 1))
      (and (nil? mistake) (seq child-types) (< (.nextDouble r) 0.3))
      (plan/child (str "C" (.nextInt r 1000)) (first child-types)))))

(deftest the-kernel-and-the-model-accept-and-reject-the-same-plans
  ;; Every handler returns a seeded random plan: valid ones must commit on both
  ;; sides, invalid ones must fail on both, and the trees must stay identical.
  (let [specs (:specs (compiled))
        fuzz-handlers (into {} (map (fn [t] [(:handler t) (fn [ctx ev] (random-plan specs t ctx ev))]))
                            (:transitions specs))]
    (doseq [seed [11 12 13]]
      (tdb/clean-db #(conform! seed 30 fuzz-handlers)))))
