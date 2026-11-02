(ns voucha.constraint
  "The declarative hierarchical constraint vocabulary (decision D2).

  The vocabulary is closed. Every constraint the kernel can evaluate appears
  here, which is what lets the compiler statically check constraints (design 35)
  and lets investigation replay name the exact rule that blocked a transition
  (design 57).

  Authoring-level sugar is expanded at compile time into the runtime forms:

      :all-children-in  ->  :counters-cover-children

  Runtime forms:

    {:constraint :parent-state       :allowed [..]}   ; or :forbidden [..]
    {:constraint :ancestor-state     :node-type \"File\" :forbidden [\"CANCELLED\"]}
    {:constraint :counter            :counter :failed :op :<= :value 5}
    {:constraint :counters-cover-children :counters [:enriched :excluded]}
    {:constraint :no-outstanding-effects  :scope :node}  ; or :subtree

  Evaluation is pure: it reads a context map assembled by the kernel and returns
  a result carrying both the verdict and the fields it read. The read-set is
  what lands in node_transition_log (decision D4)."
  (:require [clojure.string :as str]))

(def sugar-types #{:all-children-in})
(def runtime-types
  #{:parent-state :ancestor-state :counter :counters-cover-children :no-outstanding-effects})
(def all-types (into runtime-types sugar-types))

(def comparison-ops {:<= <= :>= >= :< < :> > := ==})

;; --- evaluation --------------------------------------------------------------

(defn- ok [read-set] {:ok? true :read-set read-set})
(defn- fail [reason read-set] {:ok? false :reason reason :read-set read-set})

(defn- counters [node] (get-in node [:local-data :counters] {}))
(defn- child-count [node] (get-in node [:local-data :child-count] 0))

(defmulti evaluate
  "Evaluate one runtime constraint against `ctx`.

  ctx = {:node .. :parent .. :ancestors {node-type node} :outstanding-effects {:node n :subtree m}}"
  (fn [c _ctx] (:constraint c)))

(defmethod evaluate :parent-state
  [{:keys [allowed forbidden]} {:keys [parent]}]
  (let [state (:local-state parent)
        read-set {:parent-state state}]
    (cond
      (nil? parent) (fail "node has no parent" read-set)
      (and allowed (not (contains? (set allowed) state)))
      (fail (format "parent is %s, must be one of %s" state (str/join ", " (sort allowed))) read-set)
      (and forbidden (contains? (set forbidden) state))
      (fail (format "parent is %s, which is forbidden" state) read-set)
      :else (ok read-set))))

(defmethod evaluate :ancestor-state
  [{:keys [node-type allowed forbidden]} {:keys [ancestors]}]
  (let [anc (get ancestors node-type)
        state (:local-state anc)
        read-set {(keyword (str "ancestor-" node-type "-state")) state}]
    (cond
      (nil? anc) (fail (format "no %s ancestor" node-type) read-set)
      (and allowed (not (contains? (set allowed) state)))
      (fail (format "%s ancestor is %s, must be one of %s"
                    node-type state (str/join ", " (sort allowed))) read-set)
      (and forbidden (contains? (set forbidden) state))
      (fail (format "%s ancestor is %s, which is forbidden" node-type state) read-set)
      :else (ok read-set))))

(defmethod evaluate :counter
  [{:keys [counter op value]} {:keys [node]}]
  (let [actual (get (counters node) counter 0)
        read-set {counter actual}
        cmp (comparison-ops op)]
    (if (cmp actual value)
      (ok read-set)
      (fail (format "counter %s is %s, requires %s %s"
                    (name counter) actual (name op) value)
            read-set))))

(defmethod evaluate :counters-cover-children
  [{cs :counters} {:keys [node]}]
  (let [cnts (counters node)
        covered (reduce + 0 (map #(get cnts % 0) cs))
        total (child-count node)
        read-set (assoc (select-keys cnts cs) :child-count total)]
    (if (= covered total)
      (ok read-set)
      (fail (format "%d of %d children accounted for by %s"
                    covered total (str/join "+" (map name cs)))
            read-set))))

(defmethod evaluate :no-outstanding-effects
  [{:keys [scope]} {:keys [outstanding-effects]}]
  (let [n (get outstanding-effects (or scope :node) 0)
        read-set {(keyword (str "outstanding-effects-" (name (or scope :node)))) n}]
    (if (zero? n)
      (ok read-set)
      (fail (format "%d outstanding effect(s) in %s scope" n (name (or scope :node))) read-set))))

(defmethod evaluate :default
  [c _ctx]
  (throw (ex-info "Unknown constraint type" {:type ::unknown-constraint :constraint c})))

(defn evaluate-all
  "Evaluate constraints in order. Returns
  {:ok? bool :failures [{:constraint .. :reason ..}] :read-set {..}}.

  All constraints are evaluated even after the first failure, so an operator
  asking 'why is this node stuck?' sees every blocking rule, not just the first."
  [constraints ctx]
  (let [results (mapv (fn [c] (assoc (evaluate c ctx) :constraint c)) constraints)]
    {:ok? (every? :ok? results)
     :failures (into [] (comp (remove :ok?)
                              (map #(select-keys % [:constraint :reason])))
                     results)
     :read-set (reduce merge {} (map :read-set results))}))
