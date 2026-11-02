(ns voucha.graph
  "Graph export of a compiled artifact (design 60, `voucha graph`).

  Emits Graphviz DOT: one cluster per node type, terminal states doubled,
  transitions labelled by event. Transitions carrying hierarchical constraints
  are drawn dashed, because those are the edges that can be refused at runtime."
  (:require [clojure.string :as str]))

(defn- quoted [s] (str \" (str/replace (str s) "\"" "\\\"") \"))

(defn- node-id [nt state] (quoted (str nt "." state)))

(defn to-dot
  [specs]
  (let [lines (atom ["digraph artifact {" "  rankdir=LR;" "  node [shape=box];"])
        add! #(swap! lines conj %)]
    (doseq [[nt {:keys [states terminal-states initial-state]}] (:node-types specs)]
      (add! (format "  subgraph %s {" (quoted (str "cluster_" nt))))
      (add! (format "    label=%s;" (quoted nt)))
      (doseq [s states]
        (add! (format "    %s [label=%s%s];"
                      (node-id nt s) (quoted s)
                      (cond
                        (contains? (set terminal-states) s) " peripheries=2"
                        (= initial-state s) " style=bold"
                        :else ""))))
      (add! "  }"))

    (doseq [{:keys [node-type from to on requires effects]} (:transitions specs)]
      (add! (format "  %s -> %s [label=%s%s%s];"
                    (node-id node-type from) (node-id node-type to)
                    (quoted (str on (when (seq effects) (str "\\n+" (str/join "," effects)))))
                    (if (seq requires) " style=dashed" "")
                    (if (seq effects) " color=blue" ""))))

    (doseq [{:keys [event from-node-type to-node-type scope]} (:routing specs)]
      (when (= :upward scope)
        (add! (format "  %s -> %s [label=%s style=dotted color=gray constraint=false];"
                      (quoted (str "cluster_" from-node-type))
                      (quoted (str "cluster_" to-node-type))
                      (quoted event)))))

    (add! "}")
    (str/join "\n" @lines)))

(defn hierarchy-dot
  "The node-type containment hierarchy alone -- the shape a profile defines."
  [specs]
  (str/join "\n"
            (concat ["digraph hierarchy {" "  rankdir=TB;" "  node [shape=box];"]
                    (for [[nt {:keys [may-contain]}] (:node-types specs)
                          child may-contain]
                      (format "  %s -> %s;" (quoted nt) (quoted child)))
                    ["}"])))

;; --- hierarchical state diagrams: Mermaid and PlantUML ------------------------------
;; Two kinds of hierarchy, drawn as one picture:
;;
;;   * Sub-states (`voucha.substates`): a composite state and the states inside
;;     it belong to the same node. Drawn as nested states, and a transition the
;;     author declared on the composite is drawn once, from the composite.
;;   * Containment: a parent node's children live while the parent is in some
;;     state -- a file processes its batches in FILE_PROCESS_BATCH, a batch runs
;;     its transactions in BATCH_PROCESSING. The child type's machine is drawn
;;     inside that state: the one the node type's :hosts declares, or else the
;;     innermost state containing every parent transition driven by an event
;;     the child bubbles up. A hosting state is a plain state of its own
;;     machine, so the hosted machine's start and end are the only ones in it.
;;
;; A child's event that bubbles up is shown on the child's transition as ^Event
;; and appears again as the event of the parent transition it drives.

(defn- root-types
  [specs]
  (let [contained (into #{} (mapcat :may-contain) (vals (:node-types specs)))]
    (remove contained (keys (:node-types specs)))))

(defn- bubbling-events
  [specs nt]
  (into #{} (comp (filter #(and (= nt (:from-node-type %)) (= :upward (:scope %))))
                  (map :event))
        (:routing specs)))

(defn- transition-label
  [{:keys [on effects requires emit auto]} bubbling]
  (let [marks (cond-> [] auto (conj "auto") (seq requires) (conj "guarded"))]
    (str on
         (when (seq effects) (str " / " (str/join ", " effects)))
         (when-let [up (seq (filter bubbling emit))] (str " ^" (str/join " ^" up)))
         (when (seq marks) (str " (" (str/join ", " marks) ")")))))

(defn- label-for
  "A state's label is its id, exactly as the definition names it: the level is
  in the prefix (BATCH_FAILED, TX_FAILED), which is what makes a diagram with
  several levels in one box readable."
  [_prefix s] s)

(defn- ancestry
  "For every state name in a tree, the chain of composites above it, outermost
  first; a composite's own chain stops at its parent."
  [tree]
  (letfn [(walk [entries chain acc]
            (reduce (fn [acc e]
                      (if (map? e)
                        (walk (:states e) (conj chain (:name e)) (assoc acc (:name e) chain))
                        (assoc acc e chain)))
                    acc entries))]
    (walk tree [] {})))

(defn- common-prefix [a b]
  (vec (map first (take-while (fn [[x y]] (= x y)) (map vector a b)))))

(defn- drawn-transitions
  "Transitions as the author declared them: one from a composite, not one per
  leaf; into a composite, not into its initial."
  [specs nt]
  (distinct (map (fn [t] (-> t
                             (assoc :from (or (:from-composite t) (:from t))
                                    :to (or (:to-composite t) (:to t)))
                             (select-keys [:from :to :on :effects :requires :emit :auto])))
                 (filter #(= nt (:node-type %)) (:transitions specs)))))

(declare host-state*)

(defn- host-state
  "The parent state in which children of `child` live: what the node type's
  :hosts declares, or else the innermost state that contains every parent
  transition consuming an event the child bubbles up. Nil when the definition
  gives no such hint."
  [specs parent child anc]
  (or (get-in specs [:node-types parent :hosts child])
      (host-state* specs parent child anc)))

(defn- host-state*
  [specs parent child anc]
  (let [events (into #{} (comp (filter #(and (= child (:from-node-type %)) (= parent (:to-node-type %))))
                               (map :event))
                     (:routing specs))
        froms (distinct (map :from (filter #(and (= parent (:node-type %)) (contains? events (:on %)))
                                           (:transitions specs))))]
    (when (seq froms)
      (if (= 1 (count froms))
        (first froms)
        (let [prefix (reduce common-prefix (map #(anc % []) froms))]
          (last prefix))))))

(defn- indent [pad lines] (map #(str pad %) lines))

;; One soft colour per hierarchy level, so a reader can tell a file's state
;; from a batch's from a transaction's at a glance.
(def ^:private level-colours ["#F2F4F8" "#DDE9FF" "#DDF3E1" "#FFF0D6" "#F3E3F7" "#E6F7F7"])
(defn- colour [depth] (nth level-colours (mod depth (count level-colours))))

(def ^:dynamic ^:private *notation* :plantuml)
(def ^:dynamic ^:private *classes* nil)   ; mermaid: state id -> depth, for classDef lines

(defn- declare-state
  "A state declaration: `state \"Label\" as ID` with the level's colour, in the
  notation's way; `open?` adds the brace of a container."
  [pad label id depth open?]
  (when *classes* (swap! *classes* assoc id depth))
  (str pad "state \"" label "\" as " id
       (when (= *notation* :plantuml) (str " " (colour depth)))
       (when open? " {")))

(defn- tree-lines
  "The states of one tree level, the edges that belong at this level (an edge is
  drawn inside the innermost composite containing both its ends), and the
  machines of child node types inside the states that host them -- directly
  inside, with no container of their own: the hosting state is the container.
  `markers` names this machine's start and end pseudo-states."
  [entries here edges-at hosted label depth markers initial terminal order pad]
  (concat
   (mapcat (fn [s]
             (let [name* (if (map? s) (:name s) s)
                   children (get hosted name*)]
               (cond
                 (map? s)
                 (let [inner (conj here name*)]
                   (concat [(declare-state pad (label name*) name* depth true)
                            (str pad "  [*] --> " (:initial s))]
                           (tree-lines (:states s) inner edges-at hosted label depth markers nil terminal order (str pad "  "))
                           (mapcat #(indent (str pad "  ") %) children)
                           [(str pad "}")]))
                 ;; A leaf that hosts children becomes the container for their machines.
                 (seq children)
                 (concat (cond-> [] (= s initial) (conj (str pad (:start markers) " --> " s)))
                         [(declare-state pad (label name*) name* depth true)]
                         (mapcat #(indent (str pad "  ") %) children)
                         [(str pad "}")]
                         (cond-> [] (contains? terminal s) (conj (str pad s " --> " (:end markers)))))
                 :else
                 (cond-> [(declare-state pad (label name*) name* depth false)]
                   (= s initial) (conj (str pad (:start markers) " --> " s))
                   (contains? terminal s) (conj (str pad s " --> " (:end markers)))))))
           entries)
   (for [t (sort-by (juxt #(order (:from %) -1) #(order (:to %) -1) :on) (edges-at here))]
     (str pad (:from t) " --> " (:to t) " : " (transition-label t (:bubbling (meta edges-at)))))))

(defn- machine-lines
  "One node type's machine at indentation zero: its states with sub-states
  nested, and each contained type's machine inside the state that hosts it. The
  root type is wrapped in a container of its own; a hosted type is not, since
  the hosting state already is one, and its start and end markers are the
  hosted machine's."
  [specs nt depth wrap?]
  (let [{:keys [state-tree states terminal-states initial-state may-contain]} (get-in specs [:node-types nt])
        tree (or state-tree states)
        anc (ancestry tree)
        leaf-order (into {} (map-indexed (fn [i s] [s i])) states)
        composites (letfn [(walk [t] (mapcat #(when (map? %) (cons % (walk (:states %)))) t))] (walk tree))
        order (into leaf-order (map (fn [c] [(:name c) (leaf-order (:initial c) -1)])) composites)
        label (partial label-for nil)
        by-level (group-by (fn [t] (common-prefix (anc (:from t) []) (anc (:to t) [])))
                           (drawn-transitions specs nt))
        edges-at (with-meta (fn [here] (get by-level here [])) {:bubbling (bubbling-events specs nt)})
        placements (map (fn [child] (let [host (host-state specs nt child anc)]
                                      [host (machine-lines specs child (inc depth) (nil? host))]))
                        may-contain)
        hosted (reduce (fn [m [host lines]] (if host (update m host (fnil conj []) lines) m)) {} placements)
        unhosted (keep (fn [[host lines]] (when-not host lines)) placements)
        markers {:start "[*]" :end "[*]"}
        pad (if wrap? "  " "")
        body (concat (tree-lines tree [] edges-at hosted label depth markers initial-state (set terminal-states) order pad)
                     (mapcat #(indent pad %) unhosted))]
    (if wrap?
      (concat [(declare-state "" nt nt depth true)] body ["}"])
      body)))

(defn to-mermaid
  "A Mermaid `stateDiagram-v2`: sub-states nested, each contained node type's
  machine inside the parent state that hosts it, one colour per level."
  [specs]
  (binding [*notation* :mermaid *classes* (atom {})]
    (let [body (vec (mapcat #(indent "  " (machine-lines specs % 0 true)) (root-types specs)))
          depths (distinct (vals @*classes*))]
      (str/join "\n" (concat ["stateDiagram-v2" "  direction TB"]
                             body
                             (for [d (sort depths)] (format "  classDef level%d fill:%s,stroke:#777" d (colour d)))
                             (for [[id d] (sort @*classes*)] (format "  class %s level%d" id d)))))))

(defn to-plantuml
  "The same diagram as PlantUML."
  [specs]
  (binding [*notation* :plantuml *classes* nil]
    (str/join "\n" (concat ["@startuml" "hide empty description"]
                           (mapcat #(machine-lines specs % 0 true) (root-types specs))
                           ["@enduml"]))))
