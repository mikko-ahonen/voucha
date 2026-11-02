(ns voucha.harness
  "Test harness (design 62).

  A harness builds a tree in whatever state a test needs, sends an event, and
  asserts on the outcome. It computes parent counters from the tree it is given,
  so a test never hand-maintains aggregate bookkeeping -- which would let a test
  pass against counters the kernel would never have produced."
  (:require [clojure.string :as str]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.inspect :as inspect]
            [voucha.kernel :as kernel]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.node :as node]
            [voucha.repo.tree :as tree]))

(defn- fail!
  [msg data]
  (throw (ex-info msg (assoc data :type ::expectation-failed))))

(defn for-artifact
  "Compile `definition` and start a harness against `ds`."
  [ds definition {:keys [handlers effect-adapters store-adapters tree-id]
                  :or {tree-id "test-tree"}}]
  (let [a (compiler/compile-definition definition)]
    (db/in-tx [tx ds] (artifact/insert! tx a))
    {:ds ds
     :tree-id tree-id
     :artifact a
     :specs (:specs a)
     :runtime {:handlers handlers
               :effect-adapters effect-adapters
               :store-adapters store-adapters
               :event {:max-attempts 1}
               :dispatcher {:batch-size 10 :backoff-ms 10 :max-attempts 3}}}))

(defn- derive-counters
  "Compute what the kernel's counters would be for `parent`, given its children."
  [specs parent-type children]
  (reduce (fn [acc {:keys [type state]}]
            (reduce (fn [acc cname] (update acc cname (fnil inc 0)))
                    acc
                    (compiler/counters-watching specs parent-type type state)))
          {} children))

(defn given-tree
  "Materialise a tree. `nodes` is a vector of
  {:id .. :type .. :state .. :parent ..}, root first.

  States are set directly: a harness describes a situation, it does not have to
  replay the history that produced it."
  [{:keys [ds tree-id specs artifact] :as h} nodes]
  (let [root (first nodes)]
    (db/in-tx [tx ds]
      (tree/insert! tx {:tree-id tree-id :artifact-id (:artifact-id artifact)
                        :root-node-id (:id root)
                        :profile-name (:profile-name artifact)
                        :profile-version (:profile-version artifact)})
      (doseq [{:keys [id type state parent]} nodes]
        (let [children (filter #(= id (:parent %)) nodes)
              counters (derive-counters specs type children)]
          (node/insert! tx {:tree-id tree-id :node-id id :parent-node-id parent
                            :node-type type
                            :local-state (or state (get-in specs [:node-types type :initial-state]))
                            :local-data {:child-count (count children) :counters counters}
                            :status (if (compiler/terminal? specs type state)
                                      node/status-terminal
                                      node/status-active)}))))
    h))

(defn when-event
  "Send an event to a node and drain. Records the outcome for that node."
  ([h node-id event-type] (when-event h node-id event-type {}))
  ([{:keys [ds tree-id runtime] :as h} node-id event-type payload]
   (let [event-id (str (gensym "harness-ev"))]
     (db/in-tx [tx ds]
       (kernel/submit-event! tx {:event-id event-id :tree-id tree-id
                                 :target-node-id node-id :event-type event-type
                                 :payload payload}))
     (let [outcomes (kernel/drain! ds runtime)]
       (assoc h :outcomes outcomes
              :outcome (first (filter #(= event-id (:event-id %)) outcomes)))))))

(defn dispatch-effects!
  "Run the fake dispatchers, then feed the resulting events back in."
  [{:keys [ds runtime] :as h}]
  (dispatcher/run-once! ds runtime)
  (assoc h :outcomes (kernel/drain! ds runtime)))

;; --- expectations --------------------------------------------------------------

(defn node
  "The node as the kernel sees it now: :local-state, :local-data and the rest."
  [{:keys [ds tree-id]} node-id]
  (db/in-tx [tx ds] (node/fetch tx tree-id node-id)))

(defn expect-state
  [{:keys [ds tree-id] :as h} node-id expected]
  (let [actual (:local-state (db/in-tx [tx ds] (node/fetch tx tree-id node-id)))]
    (when-not (= expected actual)
      (fail! (format "Expected %s to be in state %s, but it is %s" node-id expected actual)
             {:node-id node-id :expected expected :actual actual})))
  h)

(defn expect-transitioned
  [{:keys [outcome] :as h}]
  (when-not (= :transitioned (:outcome outcome))
    (fail! (format "Expected a transition, but the outcome was %s: %s"
                   (:outcome outcome) (:reason outcome))
           {:outcome outcome}))
  h)

(defn expect-rejected-transition
  "Assert the transition was rejected, optionally matching the reason.

  The reason comes from the same constraint evaluator the kernel runs, so the
  message a test asserts on is the message an operator will see."
  ([h] (expect-rejected-transition h nil))
  ([{:keys [outcome] :as h} reason-pattern]
   (when-not (= :rejected (:outcome outcome))
     (fail! (format "Expected a rejected transition, but the outcome was %s" (:outcome outcome))
            {:outcome outcome}))
   (when (and reason-pattern (not (re-find reason-pattern (:reason outcome))))
     (fail! (format "Rejection reason %s did not match %s" (pr-str (:reason outcome)) reason-pattern)
            {:reason (:reason outcome)}))
   h))

(defn expect-effect
  "Assert an effect of the given `name:version` was created for a node."
  [{:keys [ds tree-id] :as h} node-id effect-ref]
  (let [[nm ver] (str/split effect-ref #":")
        effects (db/in-tx [tx ds] (effect/by-owner tx tree-id node-id))]
    (when-not (some #(and (= nm (:effect-name %))
                          (or (nil? ver) (= ver (:effect-version %))))
                    effects)
      (fail! (format "Expected effect %s on %s, found %s"
                     effect-ref node-id (mapv :effect-name effects))
             {:expected effect-ref :actual (mapv :effect-name effects)})))
  h)

(defn expect-no-effects
  [{:keys [ds tree-id] :as h} node-id]
  (let [effects (db/in-tx [tx ds] (effect/by-owner tx tree-id node-id))]
    (when (seq effects)
      (fail! (format "Expected no effects on %s, found %s" node-id (mapv :effect-name effects))
             {:actual (mapv :effect-name effects)})))
  h)

(defn expect-await
  "Assert the node's current state awaits `event-type`, per the artifact."
  [{:keys [ds tree-id specs] :as h} node-id event-type]
  (let [nd (db/in-tx [tx ds] (node/fetch tx tree-id node-id))
        awaits (into #{} (mapcat :await)
                     (filter #(and (= (:node-type nd) (:node-type %))
                                   (= (:local-state nd) (:to %)))
                             (:transitions specs)))]
    (when-not (contains? awaits event-type)
      (fail! (format "Expected %s to await %s, it awaits %s" node-id event-type awaits)
             {:expected event-type :actual awaits})))
  h)

(defn expect-counter
  [{:keys [ds tree-id] :as h} node-id counter expected]
  (let [actual (get-in (db/in-tx [tx ds] (node/fetch tx tree-id node-id))
                       [:local-data :counters counter] 0)]
    (when-not (= expected actual)
      (fail! (format "Expected %s counter %s to be %s, it is %s" node-id counter expected actual)
             {:expected expected :actual actual})))
  h)

(defn why-stuck
  [{:keys [ds tree-id]} node-id]
  (db/in-tx [tx ds] (inspect/why-stuck tx tree-id node-id)))
