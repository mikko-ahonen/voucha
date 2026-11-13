(ns voucha.replay
  "Replay (design 57).

  Replay never re-executes effects or store effects. It reconstructs what the
  runtime did from what the runtime recorded:

    audit         reconstruct the history
    deterministic re-run the handlers from recorded inputs and compare outputs
    investigation explain why a transition was, or was not, valid

  Deterministic replay is what makes the determinism rules (design 58) checkable
  rather than aspirational: a handler that read the wall clock or a random number
  produces a different patch the second time, and replay says so."
  (:require [voucha.codec :as codec]
            [voucha.compiler :as compiler]
            [voucha.constraint :as constraint]
            [voucha.facts :as facts]
            [voucha.patch :as patch]
            [voucha.handler :as handler]
            [voucha.plan :as plan]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.event :as event]
            [voucha.repo.transition-log :as tlog]
            [voucha.repo.tree :as tree]))

(def kernel-handlers
  "Checkpoints the kernel itself writes. They have no handler to re-run."
  #{"kernel/aggregate" "kernel/child-added"})

(defn- specs-of [conn tree-id]
  (:specs (artifact/fetch conn (:artifact-id (tree/fetch conn tree-id)))))

;; --- audit replay ---------------------------------------------------------------

(defn audit
  "Reconstruct one node's execution from its transition log."
  [conn tree-id node-id]
  (let [entries (tlog/history conn tree-id node-id)]
    {:tree-id tree-id
     :node-id node-id
     :transitions (mapv #(select-keys % [:from-state :to-state :handler-name
                                         :triggering-event-id :state-patch
                                         :created-effects :created-store-effects
                                         :emitted-events :artifact-id :created-at])
                        entries)
     :final-data (reduce (fn [d e] (patch/apply-patch d (:state-patch e))) {} entries)
     :final-state (:to-state (last entries))}))

(defn audit-tree
  [conn tree-id]
  (let [entries (tlog/tree-history conn tree-id)]
    {:tree-id tree-id
     :artifact-id (:artifact-id (tree/fetch conn tree-id))
     :transition-count (count entries)
     :by-node (into {} (map (fn [[nid es]]
                              [nid {:transitions (count es)
                                    :final-state (:to-state (last es))}]))
                    (group-by :node-id entries))}))

;; --- deterministic replay ----------------------------------------------------------

(defn- reconstruct-states
  "Fold a node's patches to recover the local-data it held *before* every
  checkpoint, kernel-written ones included. The log is the only source; node
  rows are never read, so replay of an old tree is unaffected by its present
  state."
  [entries]
  (first
   (reduce (fn [[acc data] e]
             [(conj acc (assoc e :data-before data))
              (patch/apply-patch data (:state-patch e))])
           [[] {}] entries)))

(defn- replay-context
  "Rebuild just enough context for a handler: the node as it was, plus the
  hierarchical facts the transition recorded reading (decision D4)."
  [entry node-id node-type read-set]
  {:node {:node-id node-id
          :node-type node-type
          :local-state (:from-state entry)
          :local-data (:data-before entry)}
   :parent (when-let [ps (:parent-state read-set)] {:local-state ps})
   :ancestors {}
   :read-set read-set})

(defn- expected-patch
  "The patch the kernel recorded includes bookkeeping the handler did not write:
  creating a child bumps the parent's :child-count. Replay must add the same op
  before comparing, or every child-creating handler looks non-deterministic."
  [replayed]
  (cond-> (vec (:patch replayed))
    (seq (:child replayed)) (conj (list 'inc-in [:child-count] (count (:child replayed))))))

(defn verify-deterministic
  "Re-run every recorded handler against its recorded inputs and compare the
  patch, target state, effects and emitted events it produces.

  Effects are never dispatched: a plan is data, and here it is only inspected.

  Node data is reconstructed by folding *all* checkpoints, including the
  kernel's own aggregate and child-added entries. A handler that reads a counter
  must see the counter the kernel had written by then."
  [conn tree-id handlers]
  (let [specs (specs-of conn tree-id)
        all (tlog/tree-history conn tree-id)
        by-node (group-by :node-id all)
        replayable (remove #(kernel-handlers (:handler-name %)) all)
        divergences
        (for [[node-id es] by-node
              entry (reconstruct-states es)
              :when (not (kernel-handlers (:handler-name entry)))
              :let [ev (event/fetch conn (:triggering-event-id entry))
                    handler-sym (symbol (:handler-name entry))
                    t (when ev
                        (first (filter #(and (= (:from-state entry) (:from %))
                                             (= (:event-type ev) (:on %))
                                             (= handler-sym (handler/name-of %)))
                                       (:transitions specs))))
                    handler (when t (handler/resolve handlers t))]
              :when (and ev handler)
              :let [ctx (replay-context entry node-id (:node-type t) (:context-read-set entry))
                    facts* (facts/facts (:created-at entry) (:triggering-event-id entry))
                    replayed (try (plan/validate! (handler (assoc ctx :facts facts* :event ev) ev))
                                  (catch Exception e {:error (ex-message e)}))
                    got-patch (when-not (:error replayed) (expected-patch replayed))
                    want-patch (vec (:state-patch entry))]
              :when (or (:error replayed)
                        (not= (:state replayed) (:to-state entry))
                        (not= got-patch want-patch)
                        (not= (mapv :effect (:call replayed)) (vec (:created-effects entry)))
                        (not= (mapv :effect (:store replayed)) (vec (:created-store-effects entry)))
                        (not= (mapv :event-type (:emit replayed)) (vec (:emitted-events entry))))]
          {:node-id node-id
           :handler (:handler-name entry)
           :triggering-event-id (:triggering-event-id entry)
           :error (:error replayed)
           :expected {:to-state (:to-state entry) :patch want-patch
                      :effects (:created-effects entry)
                      :emitted (:emitted-events entry)}
           :actual {:to-state (:state replayed) :patch got-patch
                    :effects (mapv :effect (:call replayed))
                    :emitted (mapv :event-type (:emit replayed))}})
        divergences (vec divergences)]
    {:ok? (empty? divergences)
     :checked (count replayable)
     :divergences divergences}))

;; --- investigation replay -----------------------------------------------------------

(defn explain-transition
  "Why was this transition valid? Re-evaluate its constraints against the
  hierarchical context recorded at the time, not against the tree as it is now."
  [conn tree-id node-id transition-id]
  (let [specs (specs-of conn tree-id)
        entry (first (filter #(= transition-id (:transition-id %))
                             (tlog/history conn tree-id node-id)))
        ev (event/fetch conn (:triggering-event-id entry))
        t (compiler/transition-for specs
                                   (:node-type (first (filter #(= (:handler-name entry) (str (:handler %)))
                                                              (:transitions specs))))
                                   (:from-state entry) (:event-type ev))
        read-set (:context-read-set entry)
        ctx {:node {:local-data {:counters (dissoc read-set :parent-state :child-count)
                                 :child-count (:child-count read-set)}}
             :parent {:local-state (:parent-state read-set)}
             :ancestors {}
             :outstanding-effects {:node (:outstanding-effects-node read-set 0)
                                   :subtree (:outstanding-effects-subtree read-set 0)}}]
    {:transition-id transition-id
     :artifact-id (:artifact-id entry)
     :from-state (:from-state entry)
     :to-state (:to-state entry)
     :event (:event-type ev)
     :context-recorded read-set
     :context-hash (:hierarchical-context-hash entry)
     :context-hash-verified? (= (:hierarchical-context-hash entry)
                                (codec/content-hash read-set))
     :constraints (mapv (fn [c]
                          (let [r (constraint/evaluate c ctx)]
                            {:constraint c :satisfied? (:ok? r) :reason (:reason r)}))
                        (:requires t))}))

(defn explain-rejection
  "Why can this node not advance right now? Uses live context, unlike
  `explain-transition`, which uses the context recorded at the time."
  [conn tree-id node-id]
  (require 'voucha.inspect)
  ((resolve 'voucha.inspect/why-stuck) conn tree-id node-id))
