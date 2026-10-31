(ns voucha.substates
  "Nested states, flattened by the compiler.

  A node type's :states may nest: an entry is either a state name or a composite
  `{:name \"ORDER_CHARGING\" :initial \"ORDER_CHARGE_CALLING\" :states [...]}` whose
  :states nest the same way. The runtime knows only leaves -- a node's state is
  always a leaf -- so the compiler flattens the definition first:

    * a transition from a composite applies to every leaf under it, unless a
      transition on the same event is declared on a more specific state, which
      wins (inner overrides outer, as in a statechart);
    * a transition into a composite enters its :initial, recursively, so the
      handler's plan names that leaf;
    * everywhere else a state is named -- terminal states, counters, child-state
      allowances, hierarchy rules, chain views -- a composite stands for all of
      its leaves.

  The original tree is kept on the node type as :state-tree, for diagrams and
  inspection. Expanded transitions carry :from-composite and :to-composite so a
  diagram can draw the one edge the author declared."
  (:require [clojure.string :as str]))

(defn- composite? [s] (map? s))
(defn- state-name [s] (if (composite? s) (:name s) s))

(defn index
  "{:leaves [..] :composites {name {:initial .. :children [names] :leaves [..] :depth n}}}
  for one node type's :states."
  [states]
  (letfn [(walk [entries depth acc]
            (reduce (fn [acc e]
                      (if (composite? e)
                        (let [inner (walk (:states e) (inc depth) {:leaves [] :composites {}})]
                          (-> acc
                              (update :leaves into (:leaves inner))
                              (update :composites merge (:composites inner))
                              (assoc-in [:composites (:name e)]
                                        {:initial (:initial e)
                                         :children (mapv state-name (:states e))
                                         :leaves (:leaves inner)
                                         :depth depth})))
                        (update acc :leaves conj e)))
                    acc entries))]
    (walk states 0 {:leaves [] :composites {}})))

(defn leaves-of
  "The leaves a state name stands for: itself, or all under a composite."
  [idx s]
  (if-let [c (get-in idx [:composites s])] (:leaves c) [s]))

(defn resolve-initial
  "A composite's initial leaf, recursively; a leaf is itself."
  [idx s]
  (if-let [c (get-in idx [:composites s])] (recur idx (:initial c)) s))

(defn- depth-of [idx s] (get-in idx [:composites s :depth] Integer/MAX_VALUE))

;; --- validation of the nesting itself ---------------------------------------------

(defn validate
  "Errors in the nesting: a composite without states or an initial, an initial
  that is not one of its states, a name used twice. Empty when there is nothing
  nested, which is the common case."
  [d]
  (into []
        (for [[nt spec] (:node-types d)
              :let [idx (index (:states spec))
                    names (concat (:leaves idx) (keys (:composites idx)))
                    dupes (keep (fn [[n c]] (when (> c 1) n)) (frequencies names))]
              e (concat
                 (for [n dupes]
                   {:code :duplicate-state
                    :message (format "Node type %s declares state %s more than once (as a leaf or composite)." nt n)})
                 (for [[cname {:keys [initial children]}] (:composites idx)
                       :when (or (empty? children) (nil? initial) (not (some #{initial} children)))]
                   {:code :invalid-composite-state
                    :message (format "Composite state %s.%s must declare :states and an :initial among them (initial %s, states %s)."
                                     nt cname (pr-str initial) (pr-str children))
                    :fixes [(format "set :initial on %s to one of its :states" cname)]}))]
          e)))

;; --- flattening ---------------------------------------------------------------------

(defn- expand-list [idx names] (vec (distinct (mapcat #(leaves-of idx %) names))))

(defn- flatten-node-type
  [nt-name spec indexes]
  (let [idx (indexes nt-name)]
    (-> spec
        (assoc :state-tree (:states spec))
        (assoc :states (:leaves idx))
        (update :initial-state #(resolve-initial idx %))
        (update :terminal-states #(expand-list idx %))
        (update :failure-state #(resolve-initial idx %))
        (update :counters
                (fn [counters]
                  (when counters
                    (into {} (map (fn [[cname c]]
                                    [cname (update c :when-child-enters
                                                   #(expand-list (indexes (:child-type c)) %))]))
                          counters))))
        (update :ignores
                (fn [ig]
                  (when ig
                    (reduce (fn [acc [state events]]
                              (reduce (fn [acc leaf] (update acc leaf #(vec (distinct (concat % events)))))
                                      acc (leaves-of idx state)))
                            {} ig))))
        (update :child-states-allowed
                (fn [csa]
                  (when csa
                    ;; A composite key stands for each of its leaves; an explicit
                    ;; entry for a leaf wins over one inherited from its composite.
                    (let [expanded (for [[parent-state by-child] (sort-by (fn [[s _]] (depth-of idx s)) csa)
                                         leaf (leaves-of idx parent-state)]
                                     [leaf (into {} (map (fn [[ct states]]
                                                           [ct (expand-list (indexes ct) states)]))
                                                 by-child)])]
                      (reduce (fn [acc [leaf m]] (if (contains? acc leaf) acc (assoc acc leaf m)))
                              {}
                              (reverse expanded)))))))))

(defn- containers-of [d nt]
  (keep (fn [[parent spec]] (when (some #{nt} (:may-contain spec)) parent)) (:node-types d)))

(defn- flatten-constraint
  [d indexes nt c]
  (case (:constraint c)
    :parent-state
    (let [idxs (map indexes (containers-of d nt))
          expand (fn [names] (vec (distinct (mapcat (fn [idx] (expand-list idx names)) idxs))))]
      (cond-> c
        (:allowed c) (update :allowed expand)
        (:forbidden c) (update :forbidden expand)))
    :ancestor-state
    (let [idx (indexes (:node-type c))]
      (cond-> c
        (and idx (:allowed c)) (update :allowed #(expand-list idx %))
        (and idx (:forbidden c)) (update :forbidden #(expand-list idx %))))
    c))

(defn- flatten-transitions
  [d indexes]
  (let [ts (:transitions d)
        idx-of #(indexes (:node-type %))
        leaf-from? (fn [t] (not (get-in (idx-of t) [:composites (:from t)])))
        explicit (filter leaf-from? ts)
        claimed (into #{} (map (juxt :node-type :from :on)) explicit)
        ;; Deeper composites first, so an inner declaration claims its leaves
        ;; before an outer one reaches them.
        from-composites (sort-by #(- (depth-of (idx-of %) (:from %))) (remove leaf-from? ts))
        [expanded _] (reduce (fn [[acc claimed] t]
                               (reduce (fn [[acc claimed] leaf]
                                         (if (contains? claimed [(:node-type t) leaf (:on t)])
                                           [acc claimed]
                                           [(conj acc (assoc t :from leaf :from-composite (:from t)))
                                            (conj claimed [(:node-type t) leaf (:on t)])]))
                                       [acc claimed]
                                       (leaves-of (idx-of t) (:from t))))
                             [[] claimed]
                             from-composites)
        finish (fn [t]
                 (let [idx (idx-of t)
                       to (:to t)]
                   (cond-> t
                     (get-in idx [:composites to]) (assoc :to (resolve-initial idx to) :to-composite to)
                     (seq (:requires t)) (update :requires #(mapv (partial flatten-constraint d indexes (:node-type t)) %)))))]
    (mapv finish (into (vec explicit) expanded))))

(defn- flatten-chain-views
  [d indexes]
  (into {}
        (map (fn [[view {:keys [node-type steps] :as v}]]
               (let [idx (indexes node-type)]
                 [view (assoc v :steps
                              (mapv (fn [step]
                                      (reduce (fn [step k]
                                                (if (contains? step k) (update step k #(expand-list idx %)) step))
                                              step [:active-states :complete-states :failed-states]))
                                    steps))])))
        (:chain-views d)))

(defn nested?
  "Does any node type nest its states?"
  [d]
  (some (fn [[_ spec]] (some composite? (:states spec))) (:node-types d)))

(defn flatten-definition
  "The same definition with every composite state expanded into its leaves. A
  definition that nests nothing comes back unchanged."
  [d]
  (if-not (nested? d)
    d
    (let [indexes (into {} (map (fn [[nt spec]] [nt (index (:states spec))])) (:node-types d))]
      (-> d
          (update :node-types (fn [nts] (into {} (map (fn [[nt spec]] [nt (flatten-node-type nt spec indexes)])) nts)))
          (assoc :transitions (flatten-transitions d indexes))
          (assoc :chain-views (flatten-chain-views d indexes))))))
