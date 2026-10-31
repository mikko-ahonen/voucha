(ns voucha.invariants
  "The invariant oracle (docs/testing/infrastructure-grade-test-plan.md §3).

  Ten properties that must hold of any voucha database at any instant -- mid-chaos,
  after a crash, in CI. Implemented once, as queries over the store plus the pinned
  artifacts, so every test tier, the `voucha check` command and (later, D13) the
  runtime monitors ask the same questions. Each check returns violations as data;
  an empty list is the only passing answer.

  The checks are deliberately independent of the kernel's own code paths: they
  read tables and artifacts, never the repositories' in-memory state. An oracle
  that shares assumptions with the thing it checks cannot catch that thing's
  mistakes. Likewise every invariant has a test proving it *detects* a
  hand-corrupted database (invariants_test) -- a checker that cannot fail is not
  a checker."
  (:require [voucha.invariant :as invariant]
            [clojure.string :as str]
            [voucha.codec :as codec]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.replay :as replay])
  (:import (java.sql Timestamp)
           (java.time Instant)))

;; How long an effect may sit in DISPATCHING -- the "we may have sent it" state --
;; before the oracle calls it stranded (I5). A real adapter call finishes in
;; seconds; a dispatcher that died mid-call never records anything.
(def default-dispatching-bound-ms (* 5 60 1000))

;; How long an event may wait in RECEIVED before the oracle calls it stale (I9).
(def default-received-bound-ms (* 5 60 1000))

(def ^:private kernel-handlers
  "Checkpoint rows the kernel writes on its own behalf, not for a transition of
  the row's node: a parent's counter fold, and a child-added bookkeeping entry."
  #{"kernel/aggregate" "kernel/child-added"})

(def ^:private resolution-statuses
  "Attempt rows that record a non-dispatch resolution rather than an adapter call."
  #{effect/status-cancelled effect/status-timed-out})

(defn- violation
  [invariant detail & {:as ctx}]
  (merge {:invariant invariant :detail detail} ctx))

(defn- cutoff ^Timestamp [bound-ms]
  (Timestamp/from (.minusMillis (Instant/now) (long bound-ms))))

;; --- loading ----------------------------------------------------------------------

(defn- tree-ids [conn]
  (mapv :tree_id (db/execute! conn ["SELECT tree_id FROM execution_tree ORDER BY tree_id"])))

(defn- specs-for-tree [conn tree-id]
  (let [{:keys [artifact_id]} (db/execute-one! conn ["SELECT artifact_id FROM execution_tree WHERE tree_id = ?" tree-id])]
    (:specs (artifact/fetch conn artifact_id))))

(defn- nodes-of
  "Every node of a tree, with local-data decoded, in one query."
  [conn tree-id]
  (mapv (fn [r] {:node-id (:node_id r) :parent-node-id (:parent_node_id r)
                 :node-type (:node_type r) :local-state (:local_state r)
                 :status (:status r) :local-data (codec/<-jsonb (:local_data r))})
        (db/execute! conn ["SELECT node_id, parent_node_id, node_type, local_state, status, local_data
                            FROM state_node WHERE tree_id = ?" tree-id])))

;; --- I1 declared states -----------------------------------------------------------

(defn declared-states
  "I1: every node's state is a declared state of its node type in the tree's
  pinned artifact, and every node type is declared."
  [conn]
  (vec (for [tree-id (tree-ids conn)
             :let [specs (specs-for-tree conn tree-id)]
             {:keys [node-id node-type local-state]} (nodes-of conn tree-id)
             :let [nt (compiler/node-type-spec specs node-type)]
             :when (or (nil? nt) (not (contains? (set (:states nt)) local-state)))]
         (violation :I1 (if nt
                          (format "%s is in %s, which %s does not declare" node-id local-state node-type)
                          (format "%s has undeclared node type %s" node-id node-type))
                    :tree-id tree-id :node-id node-id))))

;; --- I2 child states allowed ------------------------------------------------------

(defn child-states-allowed
  "I2: where a parent's state declares which states its children of a type may
  occupy (design 16), every such child is in one of them."
  [conn]
  (vec (for [tree-id (tree-ids conn)
             :let [specs (specs-for-tree conn tree-id)
                   nodes (nodes-of conn tree-id)
                   by-id (into {} (map (juxt :node-id identity)) nodes)]
             {:keys [node-id parent-node-id node-type local-state]} nodes
             :let [parent (get by-id parent-node-id)
                   allowed (when parent
                             (compiler/child-states-allowed specs (:node-type parent)
                                                            (:local-state parent) node-type))]
             :when (and allowed (not (contains? (set allowed) local-state)))]
         (violation :I2 (format "%s is %s under %s %s, which allows only %s"
                                node-id local-state (:node-type parent) (:local-state parent)
                                (str/join "/" allowed))
                    :tree-id tree-id :node-id node-id :parent-node-id parent-node-id))))

;; --- I3 counters --------------------------------------------------------------------

(defn counters
  "I3: every parent counter equals the live count of its children in the counted
  states, and :child-count equals the number of children. Current membership, not
  cumulative visits (decision D6)."
  [conn]
  (vec (for [tree-id (tree-ids conn)
             :let [specs (specs-for-tree conn tree-id)
                   nodes (nodes-of conn tree-id)
                   children-of (group-by :parent-node-id nodes)]
             {:keys [node-id node-type local-data]} nodes
             :let [kids (get children-of node-id [])
                   expected-counters (into {}
                                           (map (fn [[cname {:keys [child-type when-child-enters]}]]
                                                  [cname (count (filter #(and (= child-type (:node-type %))
                                                                              (contains? (set when-child-enters) (:local-state %)))
                                                                        kids))]))
                                           (:counters (compiler/node-type-spec specs node-type)))
                   actual-counters (get local-data :counters {})
                   bad (for [[cname want] expected-counters
                             :let [have (get actual-counters cname 0)]
                             :when (not= want have)]
                         (format "counter %s is %d, live count is %d" (name cname) have want))
                   bad (cond-> (vec bad)
                         (not= (count kids) (get local-data :child-count 0))
                         (conj (format ":child-count is %d, has %d children"
                                       (get local-data :child-count 0) (count kids))))]
             detail bad]
         (violation :I3 (str node-id ": " detail) :tree-id tree-id :node-id node-id))))

;; --- I4 live effects under terminal nodes ----------------------------------------

(defn no-live-effect-under-terminal-node
  "I4: a terminal node owes nothing (design 16, 27). Nothing remains to consume an
  outstanding effect's outcome, so its existence is stranded work."
  [conn]
  (mapv (fn [r] (violation :I4 (format "effect %s (%s) is %s under terminal node %s"
                                       (:effect_instance_id r) (:effect_name r) (:status r) (:owner_node_id r))
                           :tree-id (:tree_id r) :node-id (:owner_node_id r)
                           :effect-instance-id (:effect_instance_id r)))
        (db/execute! conn (into ["SELECT e.effect_instance_id, e.effect_name, e.status, e.tree_id, e.owner_node_id
                                   FROM effect_instance e
                                   JOIN state_node n ON n.tree_id = e.tree_id AND n.node_id = e.owner_node_id
                                  WHERE n.status = ? AND e.status NOT IN (?,?,?,?)"
                                 node/status-terminal]
                                (vec effect/terminal-statuses)))))

;; --- I5 stranded DISPATCHING ------------------------------------------------------

(defn no-stranded-dispatching
  "I5: an effect in DISPATCHING is inside an adapter call right now. One older
  than any call could take belongs to a dispatcher that died between the call and
  recording its outcome -- the known recovery gap, made visible."
  [conn & {:keys [bound-ms] :or {bound-ms default-dispatching-bound-ms}}]
  (mapv (fn [r] (violation :I5 (format "effect %s (%s) has been DISPATCHING since %s"
                                       (:effect_instance_id r) (:effect_name r) (:updated_at r))
                           :tree-id (:tree_id r) :node-id (:owner_node_id r)
                           :effect-instance-id (:effect_instance_id r)))
        (db/execute! conn ["SELECT effect_instance_id, effect_name, tree_id, owner_node_id, updated_at
                             FROM effect_instance WHERE status = ? AND updated_at < ?"
                           effect/status-dispatching (cutoff bound-ms)])))

;; --- I6 event / checkpoint agreement -----------------------------------------------

(defn event-checkpoint-agreement
  "I6: one event, one transaction, one checkpoint (design 52). A PROCESSED event
  has exactly one transition checkpoint of its target node -- none means a lost
  write, two means the double-processing race -- and every checkpoint cites an
  event that was processed. Kernel-owned rows (counter folds, child-added) are
  excluded from the count; they ride on the same event."
  [conn]
  (let [kh (vec kernel-handlers)
        missing (db/execute! conn (into ["SELECT e.event_id, e.tree_id, e.target_node_id FROM event_log e
                                           WHERE e.status = ?
                                             AND NOT EXISTS (SELECT 1 FROM node_transition_log l
                                                              WHERE l.triggering_event_id = e.event_id
                                                                AND l.handler_name NOT IN (?,?))"
                                         event/status-processed] kh))
        doubled (db/execute! conn (into ["SELECT e.event_id, e.tree_id, e.target_node_id, COUNT(*) AS n
                                            FROM event_log e
                                            JOIN node_transition_log l ON l.triggering_event_id = e.event_id
                                           WHERE e.status = ? AND l.handler_name NOT IN (?,?)
                                           GROUP BY e.event_id, e.tree_id, e.target_node_id HAVING COUNT(*) > 1"
                                          event/status-processed] kh))
        orphaned (db/execute! conn ["SELECT l.transition_id, l.tree_id, l.node_id, l.triggering_event_id, e.status
                                       FROM node_transition_log l
                                       LEFT JOIN event_log e ON e.event_id = l.triggering_event_id
                                      WHERE l.handler_name <> ?
                                        AND (e.event_id IS NULL OR e.status <> ?)"
                                    "kernel/child-added" event/status-processed])]
    (-> []
        (into (map (fn [r] (violation :I6 (format "event %s is PROCESSED but has no checkpoint" (:event_id r))
                                      :tree-id (:tree_id r) :node-id (:target_node_id r) :event-id (:event_id r))))
              missing)
        (into (map (fn [r] (violation :I6 (format "event %s produced %d checkpoints; processed twice" (:event_id r) (:n r))
                                      :tree-id (:tree_id r) :node-id (:target_node_id r) :event-id (:event_id r))))
              doubled)
        (into (map (fn [r] (violation :I6 (format "checkpoint %s cites event %s, which is %s"
                                                  (:transition_id r) (:triggering_event_id r) (or (:status r) "missing"))
                                      :tree-id (:tree_id r) :node-id (:node_id r) :event-id (:triggering_event_id r))))
              orphaned))))

;; --- I7 single dispatch for query / manual effects --------------------------------

(defn single-dispatch
  "I7: an effect whose uncertainty policy forbids blind resend (decision D8) was
  dispatched at most once. Resolution rows (cancellation, timeout) are not calls."
  [conn]
  (mapv (fn [r] (violation :I7 (format "effect %s (%s, %s) was dispatched %d times"
                                       (:effect_instance_id r) (:effect_name r) (:uncertainty_policy r) (:n r))
                           :tree-id (:tree_id r) :node-id (:owner_node_id r)
                           :effect-instance-id (:effect_instance_id r)))
        (db/execute! conn (into ["SELECT e.effect_instance_id, e.effect_name, e.uncertainty_policy, e.tree_id,
                                         e.owner_node_id, COUNT(*) AS n
                                    FROM effect_instance e
                                    JOIN effect_attempt a ON a.effect_instance_id = e.effect_instance_id
                                   WHERE e.uncertainty_policy IN ('query','manual')
                                     AND a.status NOT IN (?,?)
                                   GROUP BY e.effect_instance_id, e.effect_name, e.uncertainty_policy, e.tree_id, e.owner_node_id
                                  HAVING COUNT(*) > 1"]
                                (vec resolution-statuses)))))

;; --- I8 idempotency keys ------------------------------------------------------------

(defn unique-idempotency-keys
  "I8: one intent per (effect, version, idempotency key). The schema has a unique
  index for this; the oracle checks it anyway, because a migration can drop an
  index and nothing else would notice."
  [conn]
  (mapv (fn [r] (violation :I8 (format "%d effects share idempotency key %s for %s %s"
                                       (:n r) (:idempotency_key r) (:effect_name r) (:effect_version r))
                           :effect-name (:effect_name r) :idempotency-key (:idempotency_key r)))
        (db/execute! conn ["SELECT effect_name, effect_version, idempotency_key, COUNT(*) AS n
                             FROM effect_instance
                            GROUP BY effect_name, effect_version, idempotency_key HAVING COUNT(*) > 1"])))

;; --- I9 stale events ----------------------------------------------------------------

(defn no-stale-events
  "I9: no event waits in RECEIVED longer than the processing bound. With workers
  alive this is a liveness failure; without them it is the harness's problem, so
  the bound is the caller's to set."
  [conn & {:keys [bound-ms] :or {bound-ms default-received-bound-ms}}]
  (mapv (fn [r] (violation :I9 (format "event %s (%s) has waited in RECEIVED since %s"
                                       (:event_id r) (:event_type r) (:received_at r))
                           :tree-id (:tree_id r) :node-id (:target_node_id r) :event-id (:event_id r)))
        (db/execute! conn ["SELECT event_id, event_type, tree_id, target_node_id, received_at
                             FROM event_log WHERE status = ? AND received_at < ?"
                           event/status-received (cutoff bound-ms)])))

;; --- I10 replay fidelity ------------------------------------------------------------

(defn replay-fidelity
  "I10: every tree replays to its own transition log exactly (design 9 / D9).
  Needs the handlers; with none given it is reported as skipped, never as passed."
  [conn handlers]
  (vec (for [tree-id (tree-ids conn)
             d (:divergences (replay/verify-deterministic conn tree-id handlers))]
         (violation :I10 (format "%s/%s replayed %s to %s, log says %s"
                                 tree-id (:node-id d) (:handler d)
                                 (pr-str (:actual d)) (pr-str (:expected d)))
                    :tree-id tree-id :node-id (:node-id d) :event-id (:triggering-event-id d)))))

;; --- I11 liveness: no dead tree ------------------------------------------------------

;; How long a tree may be quiescent -- nothing pending, nothing in flight -- while
;; still unfinished, before the oracle calls it dead.
(def default-quiescence-bound-ms (* 5 60 1000))

(defn no-dead-trees
  "I11: a tree whose root is not terminal must have *something* that can still
  produce an event -- a pending event, a live effect (including one parked for an
  operator), or a scheduled timeout. One that has none, and has not changed in
  longer than the bound, is dead: no node in it will ever move again.

  Found by the chaos harness: a response consumed before the send it answered
  left an Attempt waiting forever with every other invariant intact. Checked at
  the tree, not the node, because a node legitimately waits on its parent's
  command while siblings are still moving."
  [conn & {:keys [bound-ms] :or {bound-ms default-quiescence-bound-ms}}]
  (mapv (fn [r] (violation :I11 (format "tree %s is dead: root %s is %s, nothing pending or in flight, quiet since %s"
                                        (:tree_id r) (:root_node_id r) (:local_state r) (:quiet_since r))
                           :tree-id (:tree_id r) :node-id (:root_node_id r)))
        (db/execute! conn (into ["SELECT t.tree_id, t.root_node_id, root.local_state,
                                         (SELECT MAX(n.updated_at) FROM state_node n WHERE n.tree_id = t.tree_id) AS quiet_since
                                    FROM execution_tree t
                                    JOIN state_node root ON root.tree_id = t.tree_id AND root.node_id = t.root_node_id
                                   WHERE root.status <> ?
                                     AND NOT EXISTS (SELECT 1 FROM event_log e
                                                      WHERE e.tree_id = t.tree_id AND e.status IN (?,?))
                                     AND NOT EXISTS (SELECT 1 FROM effect_instance x
                                                      WHERE x.tree_id = t.tree_id AND x.status NOT IN (?,?,?,?))
                                     AND NOT EXISTS (SELECT 1 FROM timeout_schedule s
                                                      WHERE s.tree_id = t.tree_id AND s.status = 'PENDING')
                                     AND (SELECT MAX(n.updated_at) FROM state_node n WHERE n.tree_id = t.tree_id) < ?"
                                 node/status-terminal event/status-received event/status-parked]
                                (concat (vec effect/terminal-statuses) [(cutoff bound-ms)])))))

;; --- all of it --------------------------------------------------------------------------

;; --- the definition's business invariants ------------------------------------------------

(defn- effects-of
  "Every effect of a tree, as the evaluator reads them."
  [conn tree-id]
  (mapv (fn [r] {:id (:effect_instance_id r) :owner-node-id (:owner_node_id r)
                 :effect-name (:effect_name r) :status (:status r)})
        (db/execute! conn ["SELECT effect_instance_id, owner_node_id, effect_name, status
                            FROM effect_instance WHERE tree_id = ?" tree-id])))

(defn business-invariants
  "The properties the definition declares (`:invariants`), evaluated over each
  tree's nodes and effects as the database holds them: what was checked in the
  models, enforced on the rows. A `:when-quiescent` property is evaluated only
  for a tree with no pending event and no live effect."
  [conn]
  (vec (for [tree-id (tree-ids conn)
             :let [specs (specs-for-tree conn tree-id)]
             :when (seq (:invariants specs))
             :let [nodes (nodes-of conn tree-id)
                   effects (effects-of conn tree-id)
                   pending (:n (db/execute-one! conn ["SELECT count(*) AS n FROM event_log WHERE tree_id = ? AND status = ?" tree-id "RECEIVED"]))
                   world {:nodes (into {} (map (juxt :node-id identity)) nodes) :effects effects
                          :quiescent? (and (zero? (or pending 0)) (not-any? #(not (contains? (set effect/terminal-statuses) (:status %))) effects))}]
             v (invariant/violations specs world)]
         (violation (:property v) (:detail v) :tree-id tree-id :node-id (:node-id v)))))

(def invariant-ids [:I1 :I2 :I3 :I4 :I5 :I6 :I7 :I8 :I9 :I10 :I11])

(defn check-all
  "Run every invariant. Returns {:ok? bool :violations [..] :checked [..] :skipped [..]}.

  Options: :handlers (enables I10), :dispatching-bound-ms (I5), :received-bound-ms (I9),
  :quiescence-bound-ms (I11)."
  [conn & {:keys [handlers dispatching-bound-ms received-bound-ms quiescence-bound-ms]
           :or {dispatching-bound-ms default-dispatching-bound-ms
                received-bound-ms default-received-bound-ms
                quiescence-bound-ms default-quiescence-bound-ms}}]
  (let [violations (-> []
                       (into (declared-states conn))
                       (into (child-states-allowed conn))
                       (into (counters conn))
                       (into (no-live-effect-under-terminal-node conn))
                       (into (no-stranded-dispatching conn :bound-ms dispatching-bound-ms))
                       (into (event-checkpoint-agreement conn))
                       (into (single-dispatch conn))
                       (into (unique-idempotency-keys conn))
                       (into (no-stale-events conn :bound-ms received-bound-ms))
                       (into (if handlers (replay-fidelity conn handlers) []))
                       (into (no-dead-trees conn :bound-ms quiescence-bound-ms))
                       (into (business-invariants conn)))]
    {:ok? (empty? violations)
     :violations violations
     :checked (if handlers invariant-ids (vec (remove #{:I10} invariant-ids)))
     :skipped (if handlers [] [:I10])}))

(defn report
  "Human-readable lines for a check result."
  [{:keys [ok? violations checked skipped]}]
  (if ok?
    [(format "invariants OK: %s hold%s" (str/join " " (map name checked))
             (if (seq skipped) (str " (skipped: " (str/join " " (map name skipped)) ")") ""))]
    (into [(format "INVARIANT VIOLATIONS: %d" (count violations))]
          (map (fn [{:keys [invariant detail tree-id]}]
                 (format "  [%s] %s%s" (name invariant) (if tree-id (str tree-id ": ") "") detail)))
          violations)))
