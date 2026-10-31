(ns voucha.inspect
  "Operational inspection (design 63).

  The system must answer, without a debugger:

      Why is this node stuck?
      Which parent condition blocks it?
      Which effects are outstanding, and what external id did they return?
      Which artifact version governed this transition?

  Every answer here is derived from the same artifact and the same constraint
  evaluator the kernel uses, so an explanation can never drift from the rule
  that actually runs."
  (:require [voucha.compiler :as compiler]
            [voucha.constraint :as constraint]
            [voucha.db :as db]
            [voucha.policy :as policy]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.repo.tree :as tree]))

(defn- specs-of [conn tree-id]
  (:specs (artifact/fetch conn (:artifact-id (tree/fetch conn tree-id)))))

(defn- context-for [conn nd]
  (let [ancestors (when (:parent-node-id nd)
                    (node/ancestors conn (:tree-id nd) (:node-id nd)))]
    {:node nd
     :parent (first ancestors)
     :ancestors (into {} (map (juxt :node-type identity)) ancestors)
     :outstanding-effects
     {:node (effect/outstanding-count conn (:tree-id nd) (:node-id nd))
      :subtree (effect/outstanding-count-subtree conn (:tree-id nd) (:node-id nd))}}))

(defn transitions-from
  "Every transition the artifact declares out of this node's current state,
  each annotated with whether its hierarchical constraints hold right now.

  With an `actor-context` ({:actor .. :claims ..}, the shape Level-1 freezes onto
  an event), each transition's Level-2 policies are evaluated too, over the same
  guard the kernel runs — so the explanation of a policy denial cannot drift from
  the decision that produced it. Without one, policy-guarded transitions are
  reported hierarchical-only and flagged `:requires-actor`."
  ([conn tree-id node-id] (transitions-from conn tree-id node-id nil))
  ([conn tree-id node-id actor-context]
   (let [specs (specs-of conn tree-id)
         nd (node/fetch conn tree-id node-id)
         ctx (context-for conn nd)]
     (->> (:transitions specs)
          (filter #(and (= (:node-type nd) (:node-type %))
                        (= (:local-state nd) (:from %))))
          (mapv (fn [t]
                  (let [{:keys [ok? failures]} (constraint/evaluate-all (:requires t) ctx)
                        guarded? (seq (:policies t))
                        pol (when (and guarded? actor-context)
                              (policy/evaluate-policies specs t {:node nd :ctx ctx
                                                                 :event actor-context}))
                        allowed? (and ok? (or (nil? pol) (:ok? pol)))]
                    (cond-> {:event (:on t)
                             :to (:to t)
                             :handler (:handler t)
                             :allowed? allowed?
                             :blocked-by (into (mapv :reason failures)
                                               (map :reason (:failures pol)))}
                      (and guarded? (nil? actor-context))
                      (assoc :requires-actor true)))))))))

(defn allowed-next-events
  [conn tree-id node-id]
  (into [] (comp (filter :allowed?) (map :event)) (transitions-from conn tree-id node-id)))

(defn blocked-transitions
  [conn tree-id node-id]
  (into [] (remove :allowed?) (transitions-from conn tree-id node-id)))

(defn why-stuck
  "The direct answer to 'why is this node stuck?'

  A node is stuck when the artifact offers it transitions but the hierarchy
  forbids every one of them, or when it is waiting on an effect. Pass an
  `actor-context` to include Level-2 policy verdicts for that actor, answering
  'why can *this actor* not make this move?'"
  ([conn tree-id node-id] (why-stuck conn tree-id node-id nil))
  ([conn tree-id node-id actor-context]
  (let [nd (node/fetch conn tree-id node-id)
        specs (specs-of conn tree-id)
        ts (transitions-from conn tree-id node-id actor-context)
        outstanding (filter #(not (contains? effect/terminal-statuses (:status %)))
                            (effect/by-owner conn tree-id node-id))]
    {:node-id node-id
     :node-type (:node-type nd)
     :local-state (:local-state nd)
     :terminal? (compiler/terminal? specs (:node-type nd) (:local-state nd))
     :counters (get-in nd [:local-data :counters])
     :child-count (get-in nd [:local-data :child-count])
     :allowed-next-events (mapv :event (filter :allowed? ts))
     :blocked-transitions (mapv #(select-keys % [:event :to :blocked-by])
                                (remove :allowed? ts))
     :outstanding-effects (mapv #(select-keys % [:effect-name :status :external-request-id
                                                 :retry-count])
                                outstanding)
     :parked-events (mapv #(select-keys % [:event-id :event-type :last-error])
                          (filter #(= node-id (:target-node-id %)) (event/parked conn tree-id)))})))

(defn node-view
  [conn tree-id node-id]
  (let [nd (node/fetch conn tree-id node-id)]
    (assoc (why-stuck conn tree-id node-id)
           :local-data (:local-data nd)
           :parent-node-id (:parent-node-id nd)
           :lock-version (:lock-version nd))))

(defn tree-view
  [conn tree-id]
  (let [t (tree/fetch conn tree-id)
        nodes (db/execute! conn ["SELECT node_id FROM state_node WHERE tree_id = ? ORDER BY node_id" tree-id])]
    {:tree-id tree-id
     :artifact-id (:artifact-id t)
     :profile {:name (:profile-name t) :version (:profile-version t)}
     :status (:status t)
     :root-node-id (:root-node-id t)
     :nodes (mapv #(node-view conn tree-id (:node_id %)) nodes)}))

(defn history
  [conn tree-id node-id]
  (mapv #(select-keys % [:from-state :to-state :handler-name :triggering-event-id
                         :artifact-id :hierarchical-context-hash :context-read-set
                         :state-patch :created-effects :emitted-events :debug-provenance
                         :created-at])
        (tlog/history conn tree-id node-id)))

;; --- chain view (design 19, 56) ------------------------------------------------

(defn- step-status
  [step state]
  (cond
    (contains? (set (:complete-states step)) state) :complete
    (contains? (set (:failed-states step)) state) :failed
    (contains? (set (:active-states step)) state) :active
    :else :pending))

(defn chain-view
  "A profile-defined projection over the execution tree (design 56).

  This is not a separate execution model: every value here is read out of node
  state, counters and the artifact's chain-step definitions."
  [conn tree-id view-name node-id]
  (let [specs (specs-of conn tree-id)
        view (get-in specs [:chain-views view-name])
        nd (node/fetch conn tree-id node-id)
        counters (get-in nd [:local-data :counters] {})]
    (when-not view
      (throw (ex-info (format "No chain view %s in artifact" view-name)
                      {:type ::unknown-chain-view :view view-name})))
    (when-not (= (:node-type view) (:node-type nd))
      (throw (ex-info (format "Chain view %s is defined over %s, not %s"
                              view-name (:node-type view) (:node-type nd))
                      {:type ::wrong-node-type})))
    {:view view-name
     :node-id node-id
     :node-type (:node-type nd)
     :local-state (:local-state nd)
     :steps (mapv (fn [step]
                    (cond-> {:name (:name step)
                             :status (step-status step (:local-state nd))}
                      (:progress-counters step)
                      (assoc :progress
                             (into {:total (get-in nd [:local-data :child-count] 0)}
                                   (map (juxt identity #(get counters % 0)))
                                   (:progress-counters step)))))
                  (:steps view))}))

(defn chain-views
  [conn tree-id]
  (keys (:chain-views (specs-of conn tree-id))))

(defn profiles
  "Profiles present in stored artifacts. The runtime never resolves a profile;
  it only reports what artifacts were compiled with (decision D12)."
  [conn]
  (mapv (fn [r] {:profile-name (:profile_name r)
                 :profile-version (:profile_version r)
                 :artifact-id (:artifact_id r)})
        (db/execute! conn ["SELECT artifact_id, profile_name, profile_version
                            FROM execution_artifact ORDER BY created_at"])))
