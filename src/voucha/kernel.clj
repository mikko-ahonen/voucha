(ns voucha.kernel
  "The transactional execution kernel (design 52).

  One event, one database transaction, one checkpoint. Inside the transaction
  the kernel loads the node and exactly the hierarchical context the transition
  declares it needs, validates local and hierarchical rules against the pinned
  artifact, runs the deterministic handler, and persists everything the handler
  decided: the state patch, internal events, effect intents, timeouts, outbox
  rows, and the transition log. It performs no external I/O.

  Concurrency (design 54). A node's own row is guarded by a `lock_version` CAS.
  The ancestors a transition validates against are guarded by row locks taken in
  `load-context`, because a constraint checked against a parent that moves before
  the child commits was never checked at all. Locks run shallowest-first and a
  transition never locks a descendant, which is what makes the ordering global
  and the kernel deadlock-free.

  The kernel is generic. It reads node types, states, constraints, counters and
  routes out of the artifact. It has no idea what a Batch is."
  (:require [clojure.string :as str]
            [voucha.codec :as codec]
            [voucha.compiler :as compiler]
            [voucha.constraint :as constraint]
            [voucha.db :as db]
            [voucha.facts :as facts]
            [voucha.intercept :as intercept]
            [voucha.patch :as patch]
            [voucha.handler :as handler]
            [voucha.plan :as plan]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.repo.outbox :as outbox]
            [voucha.repo.transition-log :as tlog]
            [voucha.repo.tree :as tree])
  (:import (java.sql Timestamp)
           (java.time Instant OffsetDateTime)))

(def ^:private aggregate-handler "kernel/aggregate")
(def ^:private child-added-handler "kernel/child-added")

(defn- ->instant
  [t]
  (condp instance? t
    Instant t
    Timestamp (.toInstant ^Timestamp t)
    OffsetDateTime (.toInstant ^OffsetDateTime t)
    (throw (ex-info "Unsupported timestamp type" {:value t :class (class t)}))))

;; --- counters (decision D6) --------------------------------------------------

(defn- counter-deltas
  "Counter changes a parent must apply because a child moved between states.

  A counter is the *current* number of children in a set of states, so a child
  leaving a counted state decrements and a child entering one increments. Were
  it increment-only, a transaction that went ENRICHED and later FAILED would be
  counted twice and its batch would never reconcile."
  [specs parent-type child-type from-state to-state]
  (let [leaving (set (compiler/counters-watching specs parent-type child-type from-state))
        entering (set (compiler/counters-watching specs parent-type child-type to-state))]
    (concat (map (fn [c] (list 'inc-in [:counters c] -1)) (sort (remove entering leaving)))
            (map (fn [c] (list 'inc-in [:counters c] 1)) (sort (remove leaving entering))))))

;; --- context loading ---------------------------------------------------------

(defn- effect-scope-needed
  "Effect counts are the expensive part of context loading, so only the
  transitions whose constraints ask for them pay (design 53)."
  [constraints]
  (reduce (fn [acc {:keys [constraint scope]}]
            (if (= :no-outstanding-effects constraint)
              (conj acc (if (= :subtree scope) :subtree :node))
              acc))
          #{} constraints))

(defn- ancestor-lock-modes
  "Which ancestor rows this transition must hold, and how firmly (design 54).

  The parent is always locked: `parent-state-allows-child?` consults it on every
  transition, not only on those declaring a :parent-state constraint. Higher
  ancestors are locked only when an :ancestor-state constraint names them, so a
  transition still pays only for the context it declares (design 53).

  A transition that will fold counter deltas into its parent takes FOR UPDATE
  there from the start rather than upgrading a FOR SHARE at commit. Two siblings
  upgrading the same share lock deadlock each other; two siblings queueing on
  FOR UPDATE merely serialise, which the counter CAS already did.

  The mode is read off the artifact's declared :to state, before the handler
  runs. That is sound only because `validate-plan-against-artifact!` refuses a
  plan landing anywhere but that :to -- otherwise a handler could reach a counted
  state this lock was not chosen for, and upgrade."
  [specs node t ancestors]
  (let [parent (first ancestors)
        read-types (into #{}
                         (comp (filter #(= :ancestor-state (:constraint %)))
                               (map :node-type))
                         (:requires t))]
    (into {(:node-id parent)
           (if (seq (counter-deltas specs (:node-type parent) (:node-type node)
                                    (:from t) (:to t)))
             :update
             :share)}
          (comp (filter #(contains? read-types (:node-type %)))
                (map (juxt :node-id (constantly :share))))
          (rest ancestors))))

(defn- load-context
  "Load, and lock, exactly the hierarchical context a transition validates against.

  Deadlock freedom: ancestors are locked root-first and the node's own row is
  written last, so every transaction touching one root-to-leaf path takes those
  rows in the same order -- shallowest first. A transition locks its ancestors
  and writes itself; it never locks another node's descendants, so no two
  transactions can each hold a row the other is waiting for.

  Known gap: a :no-outstanding-effects constraint with :subtree scope counts
  effects owned by descendants, and a descendant's own transition can create one
  concurrently without locking this node. The count is therefore not stable
  across the commit, in the same way ancestor states were before they were
  locked. No profile declares a :subtree constraint today; closing it means
  locking the subtree, which is the opposite of what design 53 asks for."
  [conn specs node t]
  (let [scopes (effect-scope-needed (:requires t))
        chain (when (:parent-node-id node)
                (node/ancestors conn (:tree-id node) (:node-id node)))
        modes (when (seq chain) (ancestor-lock-modes specs node t chain))
        ancestors (vec (reverse
                        (mapv (fn [a]
                                (if-let [mode (get modes (:node-id a))]
                                  (node/lock! conn (:tree-id node) (:node-id a) mode)
                                  a))
                              (reverse chain))))]
    (cond-> {:node node
             :parent (first ancestors)
             :ancestors (into {} (map (juxt :node-type identity)) ancestors)}
      (contains? scopes :node)
      (assoc-in [:outstanding-effects :node]
                (effect/outstanding-count conn (:tree-id node) (:node-id node)))
      (contains? scopes :subtree)
      (assoc-in [:outstanding-effects :subtree]
                (effect/outstanding-count-subtree conn (:tree-id node) (:node-id node))))))

;; --- plan application --------------------------------------------------------

(defn- effect-intent
  [specs {:keys [tree-id node-id artifact-id]} facts* {:keys [effect request]} kind]
  (let [spec (compiler/effect-spec specs effect)
        ctx {:tree-id tree-id :node-id node-id}
        ;; A key component names a context field or a request field. Request
        ;; fields let a node that legitimately re-issues an effect -- a retry
        ;; cycle -- mint a distinct intent (e.g. `:attempt`), while a *replayed*
        ;; transition, which carries the same request, still collides with the
        ;; intent it already created (design 25).
        idem (->> (:idempotency-key spec)
                  (map #(get (merge request ctx) % (name %)))
                  (str/join ":"))]
    (cond-> {:effect-instance-id (facts/next-id facts*)
             :tree-id tree-id
             :owner-node-id node-id
             :effect-kind kind
             :effect-name effect
             :effect-version (:version spec)
             :artifact-id artifact-id
             :idempotency-key (str effect ":" (:version spec) ":" idem)
             :correlation-key (str (get ctx (:correlation-key spec)))
             :uncertainty-policy (:uncertainty-policy spec)
             :participant (some-> (:participant spec) name)
             :request-payload request}
      ;; The deadline is the effect's own, not the transition's: it must outlive
      ;; the state that armed it, because acknowledgement moves the node on while
      ;; the external system is still working (design 27).
      (:completion-timeout-ms spec)
      (assoc :timeout-at (Timestamp/from (.plusMillis ^Instant (facts/transaction-time facts*)
                                                      (long (:completion-timeout-ms spec))))))))

(defn- validate-plan-against-artifact!
  "A handler decides; the artifact rules. Everything a plan asks for is checked
  against the pinned artifact before any of it is written (design 31, 65).

  Emissions are checked here rather than only at compile time because the plan a
  handler returns need not match the `:emit` list its transition declared. An
  undeclared event type would otherwise land in `event_log`, be routed by nothing,
  and be indistinguishable from an event the kernel failed to process."
  [conn specs node t p]
  (let [nt (:node-type node)
        nt-spec (compiler/node-type-spec specs nt)
        permitted (set (:may-emit-effects nt-spec))
        declared-events (set (:events specs))
        ;; A downward command may address a child this very plan creates, which the
        ;; database will not know about until `persist-plan!` runs.
        plan-children (into #{} (map :node-id) (:child p))]
    ;; The artifact declares the edge, not just its endpoints. A handler landing
    ;; anywhere else makes everything derived from the declared edge a lie -- the
    ;; reachability analysis, the graph, the chain view -- and, since the kernel
    ;; picks its parent row lock from the declared :to before the handler runs, it
    ;; would leave that transition holding the wrong lock (design 15, 54).
    (when-not (= (:to t) (:state p))
      (throw (ex-info (format "Plan targets state %s, but %s.%s -on %s-> declares %s"
                              (:state p) nt (:from t) (:on t) (:to t))
                      {:type ::invalid-target-state :node-type nt
                       :state (:state p) :declared (:to t)})))
    (when-not (contains? (set (:states nt-spec)) (:state p))
      (throw (ex-info (format "Plan targets state %s, which %s does not define" (:state p) nt)
                      {:type ::invalid-target-state :node-type nt :state (:state p)})))
    (doseq [{:keys [effect]} (concat (:call p) (:store p))]
      (when-not (contains? permitted effect)
        ;; Invariant (design 65): no effect emitted where the artifact does not permit it.
        (throw (ex-info (format "%s may not emit effect %s" nt effect)
                        {:type ::effect-not-permitted :node-type nt :effect effect}))))
    (doseq [{:keys [node-type]} (:child p)]
      (when-not (contains? (set (:may-contain nt-spec)) node-type)
        (throw (ex-info (format "%s may not contain %s" nt node-type)
                        {:type ::illegal-child-type :node-type nt :child-type node-type}))))
    (doseq [{:keys [event-type target-node-id]} (:emit p)]
      (when-not (contains? declared-events event-type)
        (throw (ex-info (format "Plan emits %s, which the artifact does not declare" event-type)
                        {:type ::undeclared-event :node-type nt :event-type event-type})))
      (when (and target-node-id
                 (not (contains? plan-children target-node-id))
                 (nil? (node/fetch conn (:tree-id node) target-node-id)))
        (throw (ex-info (format "Plan emits %s to node %s, which does not exist in tree %s"
                                event-type target-node-id (:tree-id node))
                        {:type ::unknown-emit-target :event-type event-type
                         :target-node-id target-node-id}))))
    p))

(defn- parent-state-allows-child?
  "Design 16: a parent state constrains which states its children may occupy.

  `parent` is the row `load-context` locked, never a fresh unlocked read: the
  allowance is a hierarchical constraint like any other, and reading it outside
  the lock would reopen the race the lock exists to close (design 54)."
  [specs parent child-to-state]
  (if-let [allowed (and parent
                        (compiler/child-states-allowed specs (:node-type parent)
                                                       (:local-state parent)
                                                       (:node-type child-to-state)))]
    (contains? (set allowed) (:state child-to-state))
    true))

;; --- the transition ----------------------------------------------------------

(defn- update-parent-counters!
  "Fold a child's state change into its parent's aggregate counters, in the same
  transaction as the child's own transition.

  The kernel does this itself rather than relying on a handler to emit a
  bubbling event: counters are runtime-owned state, and a domain author who
  forgot to emit would silently produce a parent that can never reconcile. The
  parent row's lock_version still serialises concurrent siblings -- one wins,
  the other retries against fresh counters.

  The update is a state change, so it is checkpointed like any other."
  [conn specs parent child ev facts* from-state to-state]
  (when parent
    (let [deltas (counter-deltas specs (:node-type parent) (:node-type child) from-state to-state)]
      (when (seq deltas)
        (let [ops (vec deltas)
              new-data (patch/apply-patch (:local-data parent) ops)]
          (node/update-state! conn (assoc parent :local-data new-data))
          (tlog/append! conn {:transition-id (facts/next-id facts*)
                              :tree-id (:tree-id parent)
                              :node-id (:node-id parent)
                              :artifact-id (:artifact-id ev)
                              :triggering-event-id (:event-id ev)
                              :from-state (:local-state parent)
                              :to-state (:local-state parent)
                              :handler-name aggregate-handler
                              :state-patch ops}))))))

(defn- persist-plan!
  [conn specs node parent ev facts* p read-set]
  (let [;; A child creation changes the parent's :child-count, so it rides in the
        ;; parent's own patch rather than being a second, unlogged write.
        ops (cond-> (vec (:patch p))
              (seq (:child p)) (conj (list 'inc-in [:child-count] (count (:child p)))))
        new-data (patch/apply-patch (:local-data node) ops)
        to-state (:state p)
        terminal? (compiler/terminal? specs (:node-type node) to-state)
        effects (mapv #(effect-intent specs (assoc node :artifact-id (:artifact-id ev))
                                      facts* % :external)
                      (:call p))
        stores (mapv #(effect-intent specs (assoc node :artifact-id (:artifact-id ev))
                                     facts* % :store)
                     (:store p))
        transition-id (facts/next-id facts*)]

    ;; 1. runtime-owned node state, under optimistic lock
    (node/update-state! conn (assoc node
                                    :local-state to-state
                                    :local-data new-data
                                    :status (if terminal? node/status-terminal node/status-active)))

    ;; 2. children this transition decided to create
    (doseq [{:keys [node-id node-type]} (:child p)]
      (node/insert! conn {:tree-id (:tree-id node)
                          :node-id node-id
                          :parent-node-id (:node-id node)
                          :node-type node-type
                          :local-state (get-in specs [:node-types node-type :initial-state])
                          :local-data {:child-count 0 :counters {}}}))

    ;; 3. leaving a state disarms the timeouts that state armed. Unconditional: the
    ;;    contract is about whatever armed them, not only the artifact's own timed
    ;;    transitions (a test or a tool may arm one), so the round trip stays.
    (when (not= to-state (:local-state node))
      (outbox/cancel-for-node! conn (:tree-id node) (:node-id node)))

    ;; 3b. a terminal node owes nothing: no transition remains that could consume
    ;;     an outcome, so its outstanding effects are resolved rather than
    ;;     stranded (design 16). Before the plan's own effects are created, so a
    ;;     plan cannot cancel what it just decided to do.
    (when terminal?
      (effect/cancel-outstanding-for-node! conn (:tree-id node) (:node-id node)))

    ;; 4. durable effect intents; dispatchers run them only after commit
    (doseq [e (concat effects stores)]
      (effect/insert! conn e)
      (outbox/enqueue! conn {:outbox-id (facts/next-id facts*)
                             :tree-id (:tree-id node)
                             :node-id (:node-id node)
                             :type (str "effect:" (name (:effect-kind e)))
                             :payload {:effect-instance-id (:effect-instance-id e)}}))

    ;; 5. timeout policy
    (when-let [{:keys [after-ms event]} (:timeout p)]
      (outbox/schedule-timeout!
       conn {:timeout-id (facts/next-id facts*)
             :tree-id (:tree-id node)
             :node-id (:node-id node)
             :timeout-kind "transition"
             :event-type event
             :fire-at (Timestamp/from (.plusMillis ^Instant (facts/transaction-time facts*)
                                                     (long after-ms)))}))

    ;; 6. internal events. An event with a target is a downward command to a
    ;;    named node; one without bubbles upward along the artifact's routes.
    (doseq [{:keys [event-type payload target-node-id]} (:emit p)]
      (event/insert! conn {:event-id (facts/next-id facts*)
                           :tree-id (:tree-id node)
                           :target-node-id target-node-id
                           :event-type event-type
                           :artifact-id (:artifact-id ev)
                           :causation-id (:event-id ev)
                           :correlation-key (:node-id node)
                           :payload (assoc payload
                                           :source-node-id (:node-id node)
                                           :source-node-type (:node-type node)
                                           :source-from-state (:local-state node)
                                           :source-to-state to-state)}))

    ;; 7. the parent's aggregate view of this child
    (update-parent-counters! conn specs parent node ev facts* (:local-state node) to-state)

    ;; 8. the checkpoint. If a dev-only Fault transform rewrote this transition's
    ;;    input or plan, its footprint rides along so the row can never present an
    ;;    injected value as genuine (design: testing-api.md).
    (tlog/append! conn {:transition-id transition-id
                        :tree-id (:tree-id node)
                        :node-id (:node-id node)
                        :artifact-id (:artifact-id ev)
                        :triggering-event-id (:event-id ev)
                        :from-state (:local-state node)
                        :to-state to-state
                        :handler-name (str (:handler p))
                        :context-read-set read-set
                        :state-patch ops
                        :emitted-events (mapv :event-type (:emit p))
                        :created-effects (mapv :effect-name effects)
                        :created-store-effects (mapv :effect-name stores)
                        :debug-provenance (intercept/provenance)})

    {:outcome :transitioned
     :transition-id transition-id
     :node-id (:node-id node)
     :from-state (:local-state node)
     :to-state to-state
     :created-effects (mapv :effect-instance-id effects)
     :created-store-effects (mapv :effect-instance-id stores)
     :created-children (mapv :node-id (:child p))
     ;; the rows as this transaction left them, for the auto decisions that follow
     ;; without reading them back
     :node (assoc node :local-state to-state :local-data new-data
                  :status (if terminal? node/status-terminal node/status-active)
                  :lock-version (inc (or (:lock-version node) 0)))
     :children (mapv (fn [{:keys [node-id node-type]}]
                       {:tree-id (:tree-id node) :node-id node-id :parent-node-id (:node-id node)
                        :node-type node-type :status node/status-active
                        :local-state (get-in specs [:node-types node-type :initial-state])
                        :local-data {:child-count 0 :counters {}}})
                     (:child p))}))

;; --- event routing -----------------------------------------------------------

(defn- resolve-target
  "Resolve the node an event addresses. An event names its node directly, or
  bubbles from a source node to the nearest ancestor of the routed type
  (design 31)."
  [conn specs ev]
  (if-let [nid (:target-node-id ev)]
    (node/fetch conn (:tree-id ev) nid)
    (let [src-id (get-in ev [:payload :source-node-id])
          src-type (get-in ev [:payload :source-node-type])
          rule (compiler/routing-rule specs (:event-type ev) src-type)]
      (when (and rule src-id (= :upward (:scope rule)))
        (->> (node/ancestors conn (:tree-id ev) src-id)
             (filter #(= (:to-node-type rule) (:node-type %)))
             first)))))

(defn- ignorable?
  "Aggregating routes advance the parent only when its constraints are met. A
  batch that is not yet fully enriched simply does not move when one of its
  transactions finishes; that is progress, not an error. An event the kernel
  itself raised for an auto transition is ignorable too: the node may already
  have moved on by the time it is processed."
  [specs ev source-node-type]
  (or (true? (get-in ev [:payload :auto]))
      (= :ignore (:on-unsatisfied (compiler/routing-rule specs (:event-type ev) source-node-type)))))

(defn- handler-for
  "What runs for a transition: its plan, its registered function, or, for one
  that declares neither and therefore only moves the node, the kernel's own."
  [handlers t]
  (or (handler/resolve handlers t)
      (when (:handler t)
        (throw (ex-info (format "No handler registered for %s" (:handler t))
                        {:type ::missing-handler :handler (:handler t)})))
      (fn [_ctx _ev] (plan/state (plan/plan) (:to t)))))

(defn- auto-event? [ev] (true? (get-in ev [:payload :auto])))

(defn- decide-auto
  "The auto transition a node takes now, if any: the first out of its current
  state whose rules hold, evaluated against freshly loaded, locked context."
  [conn specs node]
  (let [candidates (compiler/auto-transitions specs (:node-type node) (:local-state node))]
    (when (seq candidates)
      (let [ctx (load-context conn specs node {:requires (vec (mapcat :requires candidates))
                                               :from (:local-state node) :to (:local-state node)})]
        (first (filter #(:ok? (constraint/evaluate-all (:requires %) ctx)) candidates))))))

(defn- fire-auto!
  "A state's auto transitions fire on entry: if the first whose rules hold is
  found, an event is raised at the node, inside this same transaction, to be
  processed like any other. The event is a request to decide, not the decision:
  when it is processed the rules are evaluated again, against whatever the
  counters say by then, so a decision that went stale while queued is simply
  re-taken. Only one such event is kept pending per node."
  [conn specs node ev facts*]
  (let [{:keys [tree-id node-id]} node]
    (when-let [chosen (decide-auto conn specs node)]
      (when-not (event/pending-auto? conn tree-id node-id (:event-id ev))
        (event/insert! conn {:event-id (facts/next-id facts*)
                             :tree-id tree-id
                             :target-node-id node-id
                             :event-type (:on chosen)
                             :artifact-id (:artifact-id ev)
                             :causation-id (:event-id ev)
                             :correlation-key node-id
                             :payload {:auto true
                                       :source-node-id node-id
                                       :source-node-type (:node-type node)}})))))

;; --- public API --------------------------------------------------------------

(declare process-locked!)

(defn process-event!
  "Process one event inside one kernel transaction. Returns an outcome map.

  Outcomes:
    :transitioned  the node moved and a checkpoint was written
    :aggregated    the node's counters moved but no transition applied
    :ignored       nothing applied, and the route permits that
    :rejected      a rule forbade the transition; the reason is recorded
    :already-processed  another worker took this event first"
  [conn runtime event-id]
  (let [ev (event/lock! conn event-id)]
    (when-not ev (throw (ex-info "Unknown event" {:type ::unknown-event :event-id event-id})))
    (process-locked! conn runtime ev)))

(defn process-locked!
  "Process an event already locked for this transaction, as `event/lock!` or
  `event/claim-one!` return it: with its tree's artifact and the transaction's
  clock reading, so neither is read again (docs/design/performance.md)."
  [conn {:keys [handlers] :as _runtime} ev]
  (binding [intercept/*provenance* (atom [])]
   (let [event-id (:event-id ev)
        _ (when-not (:tree-artifact-id ev) (throw (ex-info "Unknown tree" {:type ::unknown-tree :tree-id (:tree-id ev)})))
        ;; Execution is pinned: the tree's artifact governs, not the newest one (design 33).
        specs (:specs (artifact/fetch conn (:tree-artifact-id ev)))
        ev (assoc ev :artifact-id (:tree-artifact-id ev))
        ;; One clock reading per kernel transaction, taken from the database with the lock.
        tx-time (->instant (:now ev))
        facts* (facts/facts tx-time event-id)
        target (resolve-target conn specs ev)
        source-type (get-in ev [:payload :source-node-type])]

    (cond
      ;; Another worker held the row and has already settled this event. Its node
      ;; has moved on, so re-evaluating here would find no transition from the new
      ;; state and record a spurious rejection over the real outcome.
      (not= event/status-received (:status ev))
      {:outcome :already-processed :status (:status ev)}

      (nil? target)
      (if (ignorable? specs ev source-type)
        (do (event/mark! conn event-id event/status-ignored)
            {:outcome :ignored :reason "no target node"})
        (do (event/mark-rejected! conn event-id "no target node for event")
            {:outcome :rejected :reason "no target node for event"}))

      :else
      (let [t (if (auto-event? ev)
                ;; Decide now, from the current state and counters: the event
                ;; named a choice that may have gone stale while it waited.
                (decide-auto conn specs target)
                (compiler/transition-for specs (:node-type target) (:local-state target) (:event-type ev)))]
        (cond
          (nil? t)
          (if (or (ignorable? specs ev source-type)
                  (compiler/ignores? specs (:node-type target) (:local-state target) (:event-type ev)))
            (do (event/mark! conn event-id event/status-ignored)
                {:outcome :ignored :node-id (:node-id target)
                 :reason "no transition for state and event"})
            (let [reason (format "%s has no transition from %s on %s"
                                 (:node-type target) (:local-state target) (:event-type ev))]
              (event/mark-rejected! conn event-id reason)
              {:outcome :rejected :reason reason}))

          :else
          (let [ctx (load-context conn specs target t)
                ;; Seam: ancestors are loaded and locked. Blocking here holds those
                ;; locks open, which is how a parent-move race is made deterministic.
                _ (intercept/hit! :transition/context-loaded {:node target :ctx ctx :event ev})
                ;; Decide seam: the hierarchical verdict is computed, then a Level-2
                ;; policy may fold an authorization denial into it, deciding over the
                ;; claims frozen onto the event. Its evidence joins the read-set and
                ;; is checkpointed with the transition, so the decision replays. The
                ;; seam carries the transition and specs so the enforcer can resolve
                ;; the pinned policy; with nothing installed, `hit!` returns the base
                ;; verdict unchanged.
                {:keys [ok? failures read-set]}
                (intercept/hit! :transition/constraints-checked
                                (assoc (constraint/evaluate-all (:requires t) ctx)
                                       :node target :transition t :specs specs
                                       :event ev :ctx ctx))]
            (if-not ok?
              (let [reason (str "hierarchical constraint(s) not satisfied: "
                                (str/join "; " (map :reason failures)))]
                (if (ignorable? specs ev source-type)
                  ;; A bubbled child completion that does not yet satisfy the parent's
                  ;; aggregate is progress, not an error: the batch simply waits.
                  (do (event/mark! conn event-id event/status-ignored)
                      {:outcome :ignored :node-id (:node-id target)
                       :reason reason :blocked-by failures})
                  (do (event/mark-rejected! conn event-id reason)
                      {:outcome :rejected :reason reason :blocked-by failures})))

              (let [handler (handler-for handlers t)
                    ;; Before-transition seam: may rewrite the input the handler sees.
                    {ctx* :ctx ev* :event} (intercept/hit! :transition/before-handler
                                                           {:ctx (assoc ctx :facts facts* :event ev)
                                                            :event ev})
                    p0 (-> (handler ctx* ev*)
                           plan/validate!
                           (assoc :handler (handler/name-of t)))
                    ;; After-transition seam: may rewrite the plan before it is
                    ;; validated against the artifact, so a rewritten plan is still
                    ;; held to every artifact rule.
                    p (intercept/hit! :transition/after-handler p0)
                    _ (validate-plan-against-artifact! conn specs target t p)
                    _ (when-not (parent-state-allows-child?
                                 specs (:parent ctx)
                                 {:node-type (:node-type target) :state (:state p)})
                        (throw (ex-info (format "parent %s.%s does not allow child %s state %s"
                                                (:node-type (:parent ctx)) (:local-state (:parent ctx))
                                                (:node-type target) (:state p))
                                        {:type ::child-state-not-allowed})))
                    result (persist-plan! conn specs target (:parent ctx) ev facts* p read-set)
                    ;; The state just entered, and each child just created, may have
                    ;; an auto transition whose rules now hold; the rows are known
                    ;; here as this transaction wrote them.
                    _ (fire-auto! conn specs (:node result) ev facts*)
                    _ (doseq [child (:children result)]
                        (fire-auto! conn specs child ev facts*))
                    ;; Before-commit seam: everything is written but the transaction
                    ;; has not returned. Crashing here rolls the whole thing back.
                    ;; `:conn` is the transaction connection: an Observe hook may
                    ;; enqueue telemetry on it, atomically with the transition, for
                    ;; a dispatcher to ship after commit -- never external I/O here.
                    _ (intercept/hit! :transition/before-commit
                                      {:conn conn :node target :plan p :result result})]
                (event/mark! conn event-id event/status-processed)
                result )))))))))

(defn submit-event!
  "Ingest an external event. It is durable before it is processed.

  The `:event/before-submit` seam runs in the I/O zone, before persistence: this
  is where Level-1 edge auth authenticates the caller and either rejects the
  event or freezes the resolved claims onto it (design: testing-api.md).
  `:credentials` (the raw token/headers) is transient — visible to the
  authenticator, never persisted."
  [conn {:keys [event-id tree-id target-node-id event-type payload correlation-key
                operator-id operator-reason credentials actor claims]}]
  (let [ev (intercept/hit!
            :event/before-submit
            {:event-id (or event-id (codec/sha256 (str tree-id target-node-id event-type
                                                       (pr-str payload))))
             :tree-id tree-id
             :target-node-id target-node-id
             :event-type event-type
             :payload (or payload {})
             :correlation-key correlation-key
             :operator-id operator-id
             :operator-reason operator-reason
             :actor actor
             :claims claims
             :credentials credentials})]
    ;; The credential resolved to actor/claims at the seam; it never reaches storage.
    (event/insert! conn (dissoc ev :credentials))))

(defn drain!
  "Process pending events until none remain, in separate transactions.

  Each event gets its own transaction, so one poisonous event cannot roll back
  the work of its siblings. An event that fails is not retried within the same
  pass: retrying immediately would burn its whole attempt budget in a tight loop
  and park it, when the cause may be transient -- a sibling holding the parent's
  lock, say. It is left for the next pass, and parked only once its attempts are
  exhausted across passes (decision D7).

  The claim and the processing are one transaction: the event is locked once,
  by the claim, and held until the transaction ends (docs/design/performance.md)."
  [ds runtime]
  (loop [outcomes [] failed #{} guard 0]
    (when (> guard 100000)
      (throw (ex-info "Event drain did not converge" {:type ::drain-runaway})))
    (let [claimed (atom nil)
          outcome (try
                    (db/in-tx [tx ds]
                      (when-let [ev (event/claim-one! tx failed)]
                        (reset! claimed (:event-id ev))
                        (intercept/hit! :event/claimed {:event-id (:event-id ev)})
                        (process-locked! tx runtime ev)))
                    (catch Exception e
                      (if-let [event-id @claimed]
                        (do (db/in-tx [tx ds]
                              (event/record-failure! tx event-id (ex-message e)
                                                     (get-in runtime [:event :max-attempts] 5)))
                            {:outcome :failed :error (ex-message e)})
                        (throw e))))
          event-id @claimed]
      (if (nil? event-id)
        outcomes
        (recur (conj outcomes (assoc outcome :event-id event-id))
               (cond-> failed (= :failed (:outcome outcome)) (conj event-id))
               (inc guard))))))

;; --- tree construction -------------------------------------------------------

(defn create-tree!
  [conn {:keys [tree-id artifact-id root-node-id root-node-type profile-name profile-version]}]
  (let [specs (:specs (artifact/fetch conn artifact-id))
        initial (get-in specs [:node-types root-node-type :initial-state])]
    (tree/insert! conn {:tree-id tree-id :artifact-id artifact-id :root-node-id root-node-id
                        :profile-name profile-name :profile-version profile-version})
    (node/insert! conn {:tree-id tree-id :node-id root-node-id :node-type root-node-type
                        :local-state initial :local-data {:child-count 0 :counters {}}})
    tree-id))

(defn add-child!
  "Create a child node and account for it in its parent's :child-count.

  The parent's data changes, so this writes a checkpoint too -- otherwise a
  counter-based completion rule could be satisfied by a child the log never
  mentions."
  [conn {:keys [tree-id parent-node-id node-id node-type]}]
  (let [tree* (tree/fetch conn tree-id)
        specs (:specs (artifact/fetch conn (:artifact-id tree*)))
        parent (node/fetch conn tree-id parent-node-id)
        nt-spec (compiler/node-type-spec specs node-type)]
    (when-not (contains? (set (:may-contain (compiler/node-type-spec specs (:node-type parent)))) node-type)
      (throw (ex-info (format "%s may not contain %s" (:node-type parent) node-type)
                      {:type ::illegal-child-type})))
    (node/insert! conn {:tree-id tree-id :node-id node-id :parent-node-id parent-node-id
                        :node-type node-type :local-state (:initial-state nt-spec)
                        :local-data {:child-count 0 :counters {}}})
    (let [ops ['(inc-in [:child-count] 1)]
          new-data (patch/apply-patch (:local-data parent) ops)]
      (node/update-state! conn (assoc parent :local-data new-data))
      (tlog/append! conn {:transition-id (codec/sha256 (str tree-id node-id "child-added"))
                          :tree-id tree-id
                          :node-id parent-node-id
                          :artifact-id (:artifact-id tree*)
                          :triggering-event-id (str "child-added:" node-id)
                          :from-state (:local-state parent)
                          :to-state (:local-state parent)
                          :handler-name child-added-handler
                          :state-patch ops}))
    node-id))
