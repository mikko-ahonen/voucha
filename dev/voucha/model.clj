(ns voucha.model
  "An in-memory reference model of the kernel's semantics (test plan T1).

  One event, one transition, over a plain data tree: the same compiled artifact,
  the same handlers, the same constraint evaluator and patch vocabulary -- and no
  database, no locks, no dispatcher. Its only job is to *disagree* with the real
  kernel when one of them is wrong. `conformance_test` drives random event
  sequences through both and compares every node and every outcome.

  What it mirrors exactly, because the comparison depends on it: target
  resolution (direct or bubbled to the nearest routed ancestor), the
  ignore-vs-reject distinction for aggregation routes, constraint evaluation,
  plan validation against the artifact, the order in which identifiers are
  minted from the triggering event (emitted events are ordered by id within one
  step, so the cascade order depends on it), counter folding as current
  membership, cancellation of a terminal node's effects, and the retry-then-park
  handling of a failing event.

  What it leaves out: persistence, concurrency, dispatchers, timeouts, outbox,
  checkpoints. Those are the kernel's own, tested elsewhere."
  (:require [voucha.codec]
            [voucha.compiler :as compiler]
            [voucha.constraint :as constraint]
            [voucha.facts :as facts]
            [voucha.patch :as patch]
            [voucha.handler :as handler]
            [voucha.plan :as plan])
  (:import (java.time Instant)))

(def status-active "ACTIVE")
(def status-terminal "TERMINAL")
(def effect-cancelled "CANCELLED")
(def ^:private effect-terminal #{"CANCELLED" "COMPLETED" "FAILED"})

(defn- initial-data [] {:child-count 0 :counters {}})

;; --- tree construction ---------------------------------------------------------------

(defn new-tree
  [specs tree-id root-id root-type]
  {:tree-id tree-id
   :specs specs
   :nodes {root-id {:node-id root-id :node-type root-type :parent-node-id nil
                    :local-state (get-in specs [:node-types root-type :initial-state])
                    :local-data (initial-data) :status status-active}}
   :effects []
   :queue []
   :seq 0})

(defn add-child
  "Mirror of kernel/add-child!: the child in its initial state, the parent's
  :child-count up by one (no counter fold -- the kernel does none on add)."
  [m parent-id node-id node-type]
  (-> m
      (assoc-in [:nodes node-id] {:node-id node-id :node-type node-type :parent-node-id parent-id
                                  :local-state (get-in m [:specs :node-types node-type :initial-state])
                                  :local-data (initial-data) :status status-active})
      (update-in [:nodes parent-id :local-data] patch/apply-patch ['(inc-in [:child-count] 1)])))

;; --- lookups ------------------------------------------------------------------------------

(defn- ancestors
  "Nearest first, like node/ancestors."
  [m node-id]
  (loop [acc [] cur (get-in m [:nodes node-id :parent-node-id])]
    (if cur
      (recur (conj acc (get-in m [:nodes cur])) (get-in m [:nodes cur :parent-node-id]))
      acc)))

(defn- subtree-ids [m node-id]
  (let [children (filter #(= node-id (:parent-node-id %)) (vals (:nodes m)))]
    (into #{node-id} (mapcat #(subtree-ids m (:node-id %)) children))))

(defn live-effect? [e] (not (contains? effect-terminal (:status e))))

(defn- outstanding-effects [m node-id]
  (let [subtree (subtree-ids m node-id)]
    {:node (count (filter #(and (live-effect? %) (= node-id (:owner-node-id %))) (:effects m)))
     :subtree (count (filter #(and (live-effect? %) (contains? subtree (:owner-node-id %))) (:effects m)))}))

(defn- resolve-target [m ev]
  (if-let [nid (:target-node-id ev)]
    (get-in m [:nodes nid])
    (let [src-id (get-in ev [:payload :source-node-id])
          src-type (get-in ev [:payload :source-node-type])
          rule (compiler/routing-rule (:specs m) (:event-type ev) src-type)]
      (when (and rule src-id (= :upward (:scope rule)))
        (first (filter #(= (:to-node-type rule) (:node-type %)) (ancestors m src-id)))))))

(defn- ignorable? [m ev source-type]
  (or (true? (get-in ev [:payload :auto]))
      (= :ignore (:on-unsatisfied (compiler/routing-rule (:specs m) (:event-type ev) source-type)))))

(defn- context-for [m node requires]
  (let [anc (ancestors m (:node-id node))
        scopes (into #{} (keep (fn [{:keys [constraint scope]}]
                                 (when (= :no-outstanding-effects constraint) (if (= :subtree scope) :subtree :node))))
                     requires)]
    (cond-> {:node node :parent (first anc)
             :ancestors (into {} (map (juxt :node-type identity)) anc)}
      (seq scopes) (assoc :outstanding-effects (outstanding-effects m (:node-id node))))))

(defn- decide-auto
  "Mirror of kernel/decide-auto: the first auto transition out of the node's
  current state whose rules hold."
  [m node]
  (let [candidates (compiler/auto-transitions (:specs m) (:node-type node) (:local-state node))
        ctx (when (seq candidates) (context-for m node (mapcat :requires candidates)))]
    (first (filter #(:ok? (constraint/evaluate-all (:requires %) ctx)) candidates))))

(defn- fire-auto
  "Mirror of kernel/fire-auto!: raise a request to decide at the node, unless
  one is already queued."
  [m node-id ev facts*]
  (let [node (get-in m [:nodes node-id])
        chosen (decide-auto m node)
        pending? (some #(and (= node-id (:target-node-id %)) (true? (get-in % [:payload :auto]))) (:queue m))]
    ;; The request is received in the same transaction as the events the
    ;; transition emitted, so it carries the same :seq and its id decides the
    ;; order among them, as (received_at, event_id) does in the kernel.
    (if (and chosen (not pending?))
      (update m :queue conj {:event-id (facts/next-id facts*) :target-node-id node-id
                             :event-type (:on chosen)
                             :payload {:auto true :source-node-id node-id :source-node-type (:node-type node)}
                             :causation-id (:event-id ev) :seq (:seq m) :attempts 0})
      m)))

;; --- validation, mirroring validate-plan-against-artifact! ----------------------------------

(defn- validate-plan! [m node t p]
  (let [specs (:specs m)
        nt (:node-type node)
        nt-spec (compiler/node-type-spec specs nt)
        permitted (set (:may-emit-effects nt-spec))
        declared-events (set (:events specs))
        plan-children (into #{} (map :node-id) (:child p))]
    (when-not (= (:to t) (:state p))
      (throw (ex-info "plan targets a state other than the declared :to" {:type :invalid-target-state})))
    (when-not (contains? (set (:states nt-spec)) (:state p))
      (throw (ex-info "plan targets an undeclared state" {:type :invalid-target-state})))
    (doseq [{:keys [effect]} (concat (:call p) (:store p))]
      (when-not (contains? permitted effect)
        (throw (ex-info "effect not permitted" {:type :effect-not-permitted}))))
    (doseq [{:keys [node-type]} (:child p)]
      (when-not (contains? (set (:may-contain nt-spec)) node-type)
        (throw (ex-info "illegal child type" {:type :illegal-child-type}))))
    (doseq [{:keys [event-type target-node-id]} (:emit p)]
      (when-not (contains? declared-events event-type)
        (throw (ex-info "undeclared event" {:type :undeclared-event})))
      (when (and target-node-id
                 (not (contains? plan-children target-node-id))
                 (nil? (get-in m [:nodes target-node-id])))
        (throw (ex-info "unknown emit target" {:type :unknown-emit-target}))))
    p))

(defn- parent-allows? [specs parent child-type child-state]
  (if-let [allowed (and parent (compiler/child-states-allowed specs (:node-type parent)
                                                              (:local-state parent) child-type))]
    (contains? (set allowed) child-state)
    true))

(defn- counter-deltas [specs parent-type child-type from to]
  (let [leaving (set (compiler/counters-watching specs parent-type child-type from))
        entering (set (compiler/counters-watching specs parent-type child-type to))]
    (concat (map (fn [c] (list 'inc-in [:counters c] -1)) (sort (remove entering leaving)))
            (map (fn [c] (list 'inc-in [:counters c] 1)) (sort (remove leaving entering))))))

;; --- one event ------------------------------------------------------------------------------

(defn- persist
  "Mirror of persist-plan!, including the order identifiers are minted in."
  [m node parent ev facts* p]
  (let [specs (:specs m)
        ops (cond-> (vec (:patch p))
              (seq (:child p)) (conj (list 'inc-in [:child-count] (count (:child p)))))
        new-data (patch/apply-patch (:local-data node) ops)
        to-state (:state p)
        terminal? (compiler/terminal? specs (:node-type node) to-state)
        n-effects (+ (count (:call p)) (count (:store p)))
        ;; ids, in the kernel's order: one per effect, the transition id, one outbox
        ;; row per effect, the timeout, then one per emitted event.
        effect-ids (vec (repeatedly n-effects #(facts/next-id facts*)))
        _ (facts/next-id facts*)
        _ (dotimes [_ n-effects] (facts/next-id facts*))
        _ (when (:timeout p) (facts/next-id facts*))
        emitted (mapv (fn [{:keys [event-type payload target-node-id]}]
                        {:event-id (facts/next-id facts*)
                         :tree-id (:tree-id m)
                         :target-node-id target-node-id
                         :event-type event-type
                         :payload (assoc payload
                                         :source-node-id (:node-id node)
                                         :source-node-type (:node-type node)
                                         :source-from-state (:local-state node)
                                         :source-to-state to-state)
                         :seq (:seq m)
                         :attempts 0})
                      (:emit p))
        m (assoc-in m [:nodes (:node-id node)]
                    (assoc node :local-state to-state :local-data new-data
                           :status (if terminal? status-terminal status-active)))
        m (reduce (fn [m {:keys [node-id node-type]}]
                    (when (contains? (:nodes m) node-id)
                      ;; state_node's primary key: the kernel's insert fails and the
                      ;; whole transition rolls back.
                      (throw (ex-info "duplicate node id" {:type :duplicate-node :node-id node-id})))
                    (assoc-in m [:nodes node-id]
                              {:node-id node-id :node-type node-type :parent-node-id (:node-id node)
                               :local-state (get-in specs [:node-types node-type :initial-state])
                               :local-data (initial-data) :status status-active}))
                  m (:child p))
        m (if terminal?
            ;; mirror of effect/cancel-live-by-owner!: only what is still live is cancelled
            (update m :effects #(mapv (fn [e] (if (and (= (:node-id node) (:owner-node-id e)) (live-effect? e))
                                                (assoc e :status effect-cancelled) e)) %))
            m)
        m (update m :effects into (map (fn [id {:keys [effect request]}]
                                         {:id id :owner-node-id (:node-id node) :effect-name effect
                                          :request request :status "CREATED"})
                                       effect-ids (concat (:call p) (:store p))))
        m (update m :queue into emitted)
        m (if parent
            (let [deltas (vec (counter-deltas specs (:node-type parent) (:node-type node)
                                              (:local-state node) to-state))]
              (if (seq deltas)
                (update-in m [:nodes (:node-id parent) :local-data] patch/apply-patch deltas)
                m))
            m)
        m (fire-auto m (:node-id node) ev facts*)
        m (reduce (fn [m {:keys [node-id]}] (fire-auto m node-id ev facts*)) m (:child p))]
    [m {:outcome :transitioned :node-id (:node-id node) :from-state (:local-state node) :to-state to-state}]))

(defn process-event
  "Mirror of kernel/process-event!. Returns [model outcome]; a thrown handler,
  validation or hierarchy error is an outcome of :failed, as drain! reports it."
  [m handlers ev]
  (let [specs (:specs m)
        target (resolve-target m ev)
        source-type (get-in ev [:payload :source-node-type])]
    (cond
      (nil? target)
      [m (if (ignorable? m ev source-type)
           {:outcome :ignored :reason "no target node"}
           {:outcome :rejected :reason "no target node for event"})]

      :else
      (let [t (if (true? (get-in ev [:payload :auto]))
                (decide-auto m target)
                (compiler/transition-for specs (:node-type target) (:local-state target) (:event-type ev)))]
        (cond
          (nil? t)
          [m (if (or (ignorable? m ev source-type)
                     (compiler/ignores? specs (:node-type target) (:local-state target) (:event-type ev)))
               {:outcome :ignored :node-id (:node-id target)}
               {:outcome :rejected :reason (format "%s has no transition from %s on %s"
                                                   (:node-type target) (:local-state target) (:event-type ev))})]

          :else
          (let [anc (ancestors m (:node-id target))
                parent (first anc)
                scopes (into #{} (keep (fn [{:keys [constraint scope]}]
                                         (when (= :no-outstanding-effects constraint) (if (= :subtree scope) :subtree :node))))
                             (:requires t))
                ctx (cond-> {:node target :parent parent
                             :ancestors (into {} (map (juxt :node-type identity)) anc)}
                      (seq scopes) (assoc :outstanding-effects (outstanding-effects m (:node-id target))))
                {:keys [ok? failures]} (constraint/evaluate-all (:requires t) ctx)]
            (if-not ok?
              [m (if (ignorable? m ev source-type)
                   {:outcome :ignored :node-id (:node-id target) :blocked-by failures}
                   {:outcome :rejected :blocked-by failures})]
              (try
                (let [handler (or (handler/resolve handlers t)
                                  (when (:handler t) (throw (ex-info "missing handler" {:type :missing-handler})))
                                  (fn [_ _] (plan/state (plan/plan) (:to t))))
                      facts* (facts/facts (Instant/now) (:event-id ev))
                      p (-> (handler (assoc ctx :facts facts* :event ev) ev)
                            plan/validate!
                            (assoc :handler (handler/name-of t)))
                      _ (validate-plan! m target t p)
                      _ (when-not (parent-allows? specs parent (:node-type target) (:state p))
                          (throw (ex-info "child state not allowed" {:type :child-state-not-allowed})))]
                  (persist m target parent ev facts* p))
                (catch Exception e
                  [m {:outcome :failed :error (ex-message e)}])))))))))

;; --- the queue -------------------------------------------------------------------------------

(defn submit
  "Queue an external event. Events are processed oldest first; emitted events
  are ordered by id within the step that emitted them -- the kernel's
  (received_at, event_id) order."
  [m ev]
  (let [m (update m :seq inc)]
    (update m :queue conj (assoc ev :seq (:seq m) :attempts 0))))

(defn eligible-events
  "Queued events a kernel could claim now, oldest first: not failed in this pass,
  and not behind an older queued event for the same target node (decision D19)."
  [m failed]
  (let [queue (:queue m)
        older-for-same-target? (fn [ev]
                                 (and (:target-node-id ev)
                                      (some #(and (not= (:event-id %) (:event-id ev))
                                                  (= (:target-node-id %) (:target-node-id ev))
                                                  (neg? (compare [(:seq %) (:event-id %)] [(:seq ev) (:event-id ev)])))
                                            queue)))]
    (sort-by (juxt :seq :event-id)
             (remove #(or (contains? failed (:event-id %)) (older-for-same-target? %)) queue))))

(defn step-event
  "Process one queued event: remove it, run it, re-queue it with its attempts
  if it failed and has budget left (parked otherwise). Returns [model outcome]."
  [m handlers max-attempts ev]
  (let [m (-> m (update :queue #(vec (remove (fn [q] (= (:event-id q) (:event-id ev))) %)))
                (update :seq inc))
        [m outcome] (process-event m handlers ev)
        failed? (= :failed (:outcome outcome))
        attempts (inc (:attempts ev))
        m (if (and failed? (< attempts max-attempts))
            (update m :queue conj (assoc ev :attempts attempts))
            m)]
    [m (assoc outcome :event-id (:event-id ev))]))

(defn drain
  "Mirror of kernel/drain!: process until nothing eligible remains. A failed
  event is skipped for the rest of this pass and retried next time, parked once
  it has used `max-attempts`; while it waits, younger events addressed to the
  same node wait behind it (decision D19). Returns [model outcomes]."
  [m handlers max-attempts]
  (loop [m m failed #{} outcomes []]
    (if-let [ev (first (eligible-events m failed))]
      (let [[m outcome] (step-event m handlers max-attempts ev)]
        (recur m (if (= :failed (:outcome outcome)) (conj failed (:event-id ev)) failed)
               (conj outcomes outcome)))
      [m outcomes])))

;; --- effect outcomes (what a dispatcher, rail or callback would do) ------------------

(def ^:private outcome->status
  {:dispatched ["DISPATCHED" :dispatch-event]
   :acknowledged ["ACKNOWLEDGED" :ack-event]
   :completed ["COMPLETED" :completion-event]
   :failed ["FAILED" :failure-event]})

(defn effect-spec [m effect] (compiler/effect-spec (:specs m) (:effect-name effect)))

(defn deliver-outcome
  "Mirror of the dispatcher's apply-outcome!: record the outcome on the effect
  and queue the event it implies, addressed at the owner. A terminal effect
  refuses it, as the kernel does."
  [m effect-id outcome payload]
  (let [e (first (filter #(= effect-id (:id %)) (:effects m)))
        [status event-key] (outcome->status outcome)
        event-type (get (effect-spec m e) event-key)]
    (if (or (nil? e) (not (live-effect? e)))
      m
      (cond-> (update m :effects #(mapv (fn [x] (if (= effect-id (:id x)) (assoc x :status status) x)) %))
        event-type
        (-> (update :seq inc)
            (as-> m* (update m* :queue conj
                             {:event-id (subs (voucha.codec/sha256 (str effect-id ":" event-type)) 0 32)
                              :tree-id (:tree-id m*)
                              :target-node-id (:owner-node-id e)
                              :event-type event-type
                              :payload (merge payload {:effect-instance-id effect-id})
                              :seq (:seq m*)
                              :attempts 0})))))))

(defn nodes
  "The comparable view: node-id -> type, parent, state, status, data."
  [m]
  (into {} (map (fn [[id n]] [id (select-keys n [:node-type :parent-node-id :local-state :status :local-data])]))
        (:nodes m)))
