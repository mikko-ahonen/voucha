(ns voucha.simulate
  "The simulation executor (decision D16, test plan T8): run a compiled artifact
  and its handlers with no database, exploring the interleavings a real
  deployment could produce -- events for different nodes in any order, effect
  outcomes arriving in any order and, if the script allows, with any result --
  and checking properties on every world reached.

  Built on `voucha.model`, which `conformance_test` keeps equal to the kernel,
  so what is explored here is the application model as the kernel would run it.
  The interception facility makes one interleaving deterministic per test; this
  explores many. Exhaustive to a bound with de-duplication, or seeded random
  walks for larger workloads.

  The outside world is in the moves too: an event the definition handles but
  nothing in it produces, an operator's cancel for instance, is injected once
  per node and event wherever the node's state handles it. The order example's
  cancel during a charge in flight was found by the induction check before this
  existed; it is in reach of the simulator now.

  Properties checked on every world: the in-memory invariants (child-state
  allowances, counters against children, no live effect under a terminal node),
  the definition's business invariants (docs/design/invariants.md), and, at
  quiescence (nothing queued, nothing in flight), liveness -- the root
  must be terminal -- and the workload's expected states if given. A handler
  that never emits the event its retry state waits for is invisible to the
  compiler (the transition exists) and is found here."
  (:require [voucha.compiler :as compiler]
            [voucha.invariant :as invariant]
            [voucha.model :as model]
            [voucha.workload :as workload])
  (:import (java.util Random)))

(def default-max-attempts 3)

;; --- scripts: what the outside world may answer ---------------------------------------

(defn any-outcome
  "Every effect may complete or fail: the most adversarial script."
  [_effect] [:completed :failed])

(defn all-succeed [_effect] [:completed])

(defn workload-script
  "The outcomes a workload's script assigns: transient screening failures, rail
  attempts rejected once or for good. Everything else completes. (A rejected
  batch needs no script: its control sum is wrong in the file itself.)"
  [{:keys [script]}]
  (fn [{:keys [effect-name owner-node-id request]}]
    (case effect-name
      "screening.check" (if (and (contains? (:screening-transient script) owner-node-id) (zero? (get request :attempt 0)))
                          [:failed] [:completed])
      ("rail.inst.send" "rail.sct.submit") [(workload/rail-outcome script owner-node-id)]
      [:completed])))

;; --- moves ---------------------------------------------------------------------------------

(defn- effect-moves [m script]
  (for [e (:effects m)
        :when (model/live-effect? e)
        :let [spec (model/effect-spec m e)]
        outcome (case (:status e)
                  "CREATED" (if (:dispatch-event spec) [:dispatched] (script e))
                  "DISPATCHED" (script e)
                  "ACKNOWLEDGED" (script e)
                  [])]
    {:kind :effect :effect-id (:id e) :effect-name (:effect-name e) :owner (:owner-node-id e) :outcome outcome}))

(defn- injections
  "An external event, one an operator or a caller sends, for a node whose state
  handles it, once per node and event: what the definition cannot produce but
  must take."
  [m]
  (let [specs (:specs m)]
    (for [ev (compiler/external-events specs)
          :when (not= ev (:first-event m))
          n (vals (:nodes m))
          :when (and (compiler/transition-for specs (:node-type n) (:local-state n) ev)
                     (not (contains? (:injected m) [(:node-id n) ev])))]
      {:kind :inject :event ev :target (:node-id n) :label (str "inject " ev "->" (:node-id n))})))

(defn moves
  "Everything that could happen next in this world: an eligible queued event,
  an outcome of a live effect, and, unless `inject?` is false, an external
  event injected."
  ([m script] (moves m script true))
  ([m script inject?]
   (concat (map (fn [ev] {:kind :event :event ev :label (str (:event-type ev) "->" (:target-node-id ev))})
                (model/eligible-events m #{}))
           (map #(assoc % :label (str (name (:outcome %)) " " (:effect-name %) "@" (:owner %)))
                (effect-moves m script))
           (when inject? (injections m)))))

(defn apply-move
  "The world after a move. An event move leaves its outcome on the world as
  :last-outcome, for the property that no event is rejected."
  [m handlers max-attempts {:keys [kind event effect-id outcome target]}]
  (case kind
    :event (let [[m* out] (model/step-event m handlers max-attempts event)]
             (assoc m* :last-outcome (assoc out :target (:target-node-id event))))
    :effect (-> (model/deliver-outcome m effect-id outcome {}) (dissoc :last-outcome))
    :inject (-> (model/submit m {:event-id (str "inject:" target ":" event) :tree-id (:tree-id m) :target-node-id target
                                 :event-type event :payload {} :operator-id "sim"})
                (update :injected (fnil conj #{}) [target event])
                (dissoc :last-outcome))))

;; --- properties ----------------------------------------------------------------------------

(defn- counters-expected [m node]
  (let [specs (:specs m)
        kids (filter #(= (:node-id node) (:parent-node-id %)) (vals (:nodes m)))]
    (into {} (map (fn [[cname {:keys [child-type when-child-enters]}]]
                    [cname (count (filter #(and (= child-type (:node-type %))
                                                (contains? (set when-child-enters) (:local-state %)))
                                          kids))]))
          (:counters (compiler/node-type-spec specs (:node-type node))))))

(defn invariant-violations
  "The in-memory invariants: I2, I3, I4 of the oracle."
  [m]
  (let [specs (:specs m) nodes (vals (:nodes m))]
    (concat
     (for [n nodes
           :let [parent (get-in m [:nodes (:parent-node-id n)])
                 allowed (when parent (compiler/child-states-allowed specs (:node-type parent) (:local-state parent) (:node-type n)))]
           :when (and allowed (not (contains? (set allowed) (:local-state n))))]
       {:property :I2 :node-id (:node-id n) :detail (str (:local-state n) " under " (:local-state parent))})
     (for [n nodes
           [cname want] (counters-expected m n)
           :let [have (get-in n [:local-data :counters cname] 0)]
           :when (not= want have)]
       {:property :I3 :node-id (:node-id n) :detail (format "%s is %d, children say %d" (name cname) have want)})
     (for [n nodes
           :let [kids (count (filter #(= (:node-id n) (:parent-node-id %)) nodes))]
           :when (not= kids (get-in n [:local-data :child-count] 0))]
       {:property :I3 :node-id (:node-id n) :detail (format ":child-count %d, children %d" (get-in n [:local-data :child-count] 0) kids)})
     (for [e (:effects m)
           :when (model/live-effect? e)
           :let [owner (get-in m [:nodes (:owner-node-id e)])]
           :when (= model/status-terminal (:status owner))]
       {:property :I4 :node-id (:owner-node-id e) :detail (str (:effect-name e) " " (:status e) " under terminal node")})
     ;; the definition's own business invariants, on this world
     (invariant/violations specs (assoc m :quiescent? (and (empty? (:queue m)) (not-any? model/live-effect? (:effects m))))))))

(defn outcome-violations
  "The move that produced this world must not have been rejected: an event the
  kernel refuses -- no transition from the node's state, a hierarchy rule that
  fails and is not declared ignorable -- is a command or callback the process
  did not expect, such as one sent twice. The kernel records it as an error
  for an operator; the simulator reports it as one."
  [m]
  (let [{:keys [outcome reason blocked-by node-id target event-id]} (:last-outcome m)]
    (when (= :rejected outcome)
      [{:property :rejected :node-id (or node-id target)
        :detail (str (or reason (str "blocked by " (pr-str blocked-by))) " (event " event-id ")")}])))

(defn quiescent? [m] (and (empty? (:queue m)) (not-any? model/live-effect? (:effects m))))

(defn quiescence-violations
  "At quiescence nothing can ever move again: the root must be terminal, and the
  expected states, if given, must hold; node ids expected absent must be absent."
  [m root-id {:keys [expected absent]}]
  (when (quiescent? m)
    (concat
     (let [root (get-in m [:nodes root-id])]
       (when (not= model/status-terminal (:status root))
         [{:property :I11 :node-id root-id :detail (str "quiescent but root is " (:local-state root))}]))
     (for [[nid want] expected :let [have (get-in m [:nodes nid :local-state])] :when (not= want have)]
       {:property :expected :node-id nid :detail (str "expected " want ", is " have)})
     (for [nid absent :when (contains? (:nodes m) nid)]
       {:property :absent :node-id nid :detail "expected never to exist"}))))

;; --- exploration ------------------------------------------------------------------------------

(defn- world-key [m]
  [(model/nodes m)
   (sort (map (juxt :event-id :attempts) (:queue m)))
   (sort (map (juxt :id :status) (:effects m)))
   (:injected m)])

(defn explore
  "Depth-first over every interleaving from `m`, de-duplicating worlds, to
  `max-worlds`. Returns {:explored n :terminal n :complete? bool :violations [..]}
  where each violation carries the move path that produced it."
  [m handlers script root-id {:keys [max-attempts max-worlds expected absent max-violations inject]
                              :or {max-attempts default-max-attempts max-worlds 20000 max-violations 5 inject true}}]
  (loop [stack [[m []]] seen #{} explored 0 terminal 0 violations []]
    (if (or (empty? stack) (>= explored max-worlds) (>= (count violations) max-violations))
      {:explored explored :terminal terminal :complete? (and (empty? stack) (< explored max-worlds))
       :violations violations}
      (let [[w path] (peek stack) stack (pop stack) k (world-key w)]
        (if (contains? seen k)
          (recur stack seen explored terminal violations)
          (let [vs (concat (outcome-violations w) (invariant-violations w)
                           (quiescence-violations w root-id {:expected expected :absent absent}))
                ms (moves w script inject)
                violations (into violations (map #(assoc % :path path)) vs)]
            (recur (into stack (map (fn [mv] [(apply-move w handlers max-attempts mv) (conj path (:label mv))]) ms))
                   (conj seen k) (inc explored) (if (empty? ms) (inc terminal) terminal) violations)))))))

(defn random-walk
  "One seeded walk of up to `steps` moves. Returns {:steps n :quiescent? bool :violations [..]}."
  [m handlers script root-id seed {:keys [max-attempts steps expected absent inject]
                                   :or {max-attempts default-max-attempts steps 500 inject true}}]
  (let [r (Random. (long seed))]
    (loop [w m i 0 path []]
      (let [vs (concat (outcome-violations w) (invariant-violations w)
                       (quiescence-violations w root-id {:expected expected :absent absent}))
            ms (vec (moves w script inject))]
        (cond
          (seq vs) {:steps i :quiescent? (quiescent? w) :violations (mapv #(assoc % :path path) vs)}
          (or (empty? ms) (>= i steps)) {:steps i :quiescent? (quiescent? w) :violations []}
          :else (let [mv (nth ms (.nextInt r (count ms)))]
                  (recur (apply-move w handlers max-attempts mv) (inc i) (conj path (:label mv)))))))))

;; --- from a workload --------------------------------------------------------------------------

(defn world-from
  "A model world of one tree: a root of `root-type` with one event submitted
  to it, which is how every tree begins."
  [specs root-type root-id event-type payload]
  (-> (model/submit (model/new-tree specs "sim" root-id root-type)
                    {:event-id "sim:first" :tree-id "sim" :target-node-id root-id
                     :event-type event-type :payload payload})
      (assoc :first-event event-type :tree-id "sim")))

(defn world-for
  "A model world with the workload's files submitted (one tree per file is the
  kernel's shape; here the first file, as the simulator explores one tree)."
  [specs {:keys [files]}]
  (let [{:keys [tree-id root-node-id payload]} (first files)]
    (-> (model/submit (model/new-tree specs tree-id root-node-id "File")
                      {:event-id (str tree-id ":received") :tree-id tree-id :target-node-id root-node-id
                       :event-type "FileReceived" :payload payload})
        (assoc :first-event "FileReceived" :tree-id tree-id))))
