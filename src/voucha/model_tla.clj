(ns voucha.model-tla
  "A TLA+ specification of a compiled definition, for TLC, the exhaustive
  bounded model checker, and on the way to proof (docs/design/handler-ir.md).

  The same model as `voucha.model-p`, written as a TLA+ action system: the tree,
  the queue and the effects are the variables; one step processes any eligible
  event in per-node receipt order or applies any outcome to any live effect; the
  definition's tables are operators in the module, and every transition that is
  a plan is translated exactly into an operator over the node's data and the
  event's payload, both dynamic values (functions from field name to value,
  with a sentinel for absence). TLC then enumerates every reachable state of the
  instance the driver describes, with deduplication, and reports the exact
  count, which is the bound the simulator and the P checker cannot give.

  Checked as invariants: no event the kernel would reject, no child in a state
  its parent forbids, and no quiescent tree whose root is not terminal. As
  action and temporal properties, optionally: a terminal node never moves, and
  every tree finishes under weak fairness.

  Transitions on code handlers are abstracted as in the P model: every emit
  alternative is a separate step, bounded by `max-repeat`."
  (:require [clojure.string :as str]
            [voucha.compiler :as compiler]))

;; --- TLA+ text ---------------------------------------------------------------------

(defn- q [s] (str "\"" (str/replace (str s) "\"" "'") "\""))
(defn- tla-bool [b] (if b "TRUE" "FALSE"))
(defn- tla-seq [xs] (if (empty? xs) "<<>>" (str "<<" (str/join ", " xs) ">>")))
(defn- tla-set [xs] (str "{" (str/join ", " xs) "}"))
(defn- tla-record [pairs] (str "[" (str/join ", " (map (fn [[k v]] (str k " |-> " v)) pairs)) "]"))
(defn- tla-map
  "A function from string keys: `(\"k\" :> v) @@ ...`, or the empty map."
  [pairs]
  (if (empty? pairs)
    "EmptyMap"
    (str/join " @@ " (map (fn [[k v]] (str "(" (q k) " :> " v ")")) pairs))))
(defn- ident [s] (str/replace (str s) #"[^A-Za-z0-9_]" "_"))

;; --- the tables ----------------------------------------------------------------------

(defn- constraint-record [specs nt c]
  (let [state-of (fn [s] (q s))
        kind (case (:constraint c)
               :parent-state "parent" :ancestor-state "ancestor" :counter "counter"
               :counters-cover-children "cover" :no-outstanding-effects "noeffects"
               (throw (ex-info (str "constraint not modelled: " (:constraint c)) {:constraint c})))]
    (tla-record [["kind" (q kind)]
                 ["ntype" (q (or (:node-type c) ""))]
                 ["allowed" (tla-set (map state-of (:allowed c)))]
                 ["forbidden" (tla-set (map state-of (:forbidden c)))]
                 ["cname" (q (or (some-> (:counter c) name) ""))]
                 ["op" (q (or (some-> (:op c) name) ""))]
                 ["value" (str (long (or (:value c) 0)))]
                 ["cnames" (tla-seq (map (comp q name) (:counters c)))]
                 ["subtree" (tla-bool (= :subtree (:scope c)))]])))

(defn- transition-record [specs i {:keys [node-type from on to auto plan effects emit creates commands requires timeout]}]
  (tla-record [["idx" (str (inc i))]
               ["ntype" (q node-type)] ["src" (q from)] ["evt" (q on)] ["dst" (q to)]
               ["auto" (tla-bool auto)] ["hasPlan" (tla-bool (some? plan))]
               ["effects" (tla-seq (map q effects))]
               ["emits" (tla-seq (map q emit))]
               ["spawns" (tla-seq (map q creates))]
               ["cmds" (tla-seq (map q commands))]
               ["guards" (tla-seq (map #(constraint-record specs node-type %) requires))]
               ["timeout" (q (or (:event timeout) ""))]]))

(defn- tables [specs]
  (let [nts (sort-by key (:node-types specs))]
    (str
     "T == " (tla-seq (map-indexed #(transition-record specs %1 %2) (:transitions specs))) "\n\n"
     "Initial == " (tla-map (for [[nt s] nts] [nt (q (:initial-state s))])) "\n"
     "TerminalOf == " (tla-map (for [[nt s] nts] [nt (tla-set (map q (:terminal-states s)))])) "\n\n"
     "Counters == " (tla-seq (for [[nt {:keys [counters]}] nts, [cname {:keys [child-type when-child-enters]}] (sort-by key counters)]
                               (tla-record [["ntype" (q nt)] ["cname" (q (name cname))] ["childType" (q child-type)]
                                            ["states" (tla-set (map q when-child-enters))]]))) "\n"
     "Allowed == " (tla-seq (for [[nt {:keys [child-states-allowed]}] nts, [ps by-child] (sort-by key child-states-allowed), [ct states] (sort-by key by-child)]
                              (tla-record [["ntype" (q nt)] ["pstate" (q ps)] ["childType" (q ct)] ["states" (tla-set (map q states))]]))) "\n"
     "Ignored == " (tla-set (for [[nt {:keys [ignores]}] nts, [s evs] (sort-by key ignores), e evs]
                              (tla-seq [(q nt) (q s) (q e)]))) "\n"
     "Routes == " (tla-seq (for [{:keys [event from-node-type to-node-type on-unsatisfied]} (:routing specs)]
                             (tla-record [["evt" (q event)] ["fromType" (q from-node-type)] ["toType" (q to-node-type)]
                                          ["ignoreUnsat" (tla-bool (= :ignore on-unsatisfied))]]))) "\n"
     "Especs == " (tla-map (for [[n e] (sort-by key (:effects specs))]
                             [n (tla-record [["dispatch" (q (or (:dispatch-event e) ""))]
                                             ["completion" (q (or (:completion-event e) ""))]
                                             ["failure" (q (or (:failure-event e) ""))]
                                             ["timeout" (q (or (:timeout-event e) ""))]])])) "\n")))

;; --- plans: the IR as operators --------------------------------------------------------

(def ^:dynamic ^:private *aux* nil)     ; top-level operators generated for loops and sums
(def ^:dynamic ^:private *id-vars* nil) ; variables bound to an id built with :str, already <<base, suffix>>
(def ^:dynamic ^:private *fresh* nil)   ; a counter for names

(defn- fresh [prefix] (str prefix (swap! *fresh* inc)))

(def ^:private base-params ["node" "d0" "payload" "ns"])

(declare expr->tla ops->tla)

(def ^:private bool-ops #{:= :< :<= :> :>= :and :not :nil? :opaque})

(defn- cond->tla [x scope]
  (if (and (vector? x) (bool-ops (first x)))
    (expr->tla x scope)
    (str "Truthy(" (expr->tla x scope) ")")))

(defn- get-chain [base ks] (reduce (fn [acc k] (str "Get(" acc ", " (q (name k)) ")")) base ks))

(defn- expr->tla
  "A plan expression as TLA+. `scope` is the vector of bound variable names,
  which generated operators take as parameters."
  [x scope]
  (cond
    (vector? x)
    (let [[op & args] x
          a (fn [i] (expr->tla (nth args i) scope))]
      (case op
        :data (get-chain "d0" args)
        :payload (get-chain "payload" args)
        :node-id "node"
        :counter (str "CounterValueIn(ns, node, " (q (name (first args))) ")")
        :var (str "v_" (ident (name (first args))))
        :get (str "Get(" (a 0) ", " (q (name (second args))) ")")
        :select (let [e (a 0)] (tla-map (map (fn [k] [(name k) (str "Get(" e ", " (q (name k)) ")")]) (second args))))
        :count (str "Count(" (a 0) ")")
        :sum (if (= 2 (count args))
               (str "SumField(" (a 0) ", " (q (name (second args))) ")")
               (let [[sym coll term] args
                     nm (fresh "Sum_")
                     v (str "v_" (ident (name sym)))
                     params (concat base-params (map #(str "v_" (ident (name %))) scope))
                     body (expr->tla term (conj scope sym))]
                 (swap! *aux* conj
                        (str "RECURSIVE " nm "(_" (apply str (repeat (count params) ", _")) ")\n"
                             nm "(S, " (str/join ", " params) ") ==\n"
                             "  IF S = {} THEN 0\n"
                             "  ELSE LET " v " == CHOOSE x \\in S : TRUE IN ToInt(" body ") + " nm "(S \\ {" v "}, " (str/join ", " params) ")\n"))
                 (str nm "(ToSet(" (expr->tla coll scope) "), " (str/join ", " params) ")")))
        :map (str "MapField(" (a 0) ", " (q (name (second args))) ")")
        :+ (str "(ToInt(" (a 0) ") + ToInt(" (a 1) "))")
        :- (str "(ToInt(" (a 0) ") - ToInt(" (a 1) "))")
        :* (str "(ToInt(" (a 0) ") * ToInt(" (a 1) "))")
        ;; Node ids are pairs <<base, suffix>>: the base a string or a model value (a symmetric
        ;; node), the suffix a string. A string built from parts starting with an id extends
        ;; its suffix; starting with anything else, that is the base. So every id compares with
        ;; every other in TLC, and a permutation of the bases keeps them ids.
        :str (let [[first* & rest*] args
                   part (fn [x] (if (or (number? x)
                                        (and (vector? x) (#{:+ :- :* :count :sum :counter} (first x)))
                                        (and (vector? x) (= :or (first x)) (number? (last x))))
                                  (str "ToString(ToInt(" (expr->tla x scope) "))")
                                  (expr->tla x scope)))
                   suffix (if (seq rest*) (str/join " \\o " (map part rest*)) "\"\"")
                   id? (or (= [:node-id] first*)
                           (and (vector? first*) (= :var (first first*)) (contains? @*id-vars* (second first*))))]
               (if id?
                 (let [f (expr->tla first* scope)] (str "<<" f "[1], " f "[2] \\o " suffix ">>"))
                 (str "<<" (expr->tla first* scope) ", " suffix ">>")))
        := (str "Eq(" (a 0) ", " (a 1) ")")
        :< (str "Lt(" (a 0) ", " (a 1) ")")
        :<= (str "Le(" (a 0) ", " (a 1) ")")
        :> (str "Gt(" (a 0) ", " (a 1) ")")
        :>= (str "Ge(" (a 0) ", " (a 1) ")")
        :and (str "(" (str/join " /\\ " (map #(cond->tla % scope) args)) ")")
        :or (reduce (fn [acc e] (str "Or2(" e ", " acc ")")) (expr->tla (last args) scope) (map #(expr->tla % scope) (reverse (butlast args))))
        :not (str "(~" (cond->tla (first args) scope) ")")
        :nil? (str "IsNil(" (a 0) ")")
        :if (str "IfVal(" (cond->tla (first args) scope) ", " (a 1) ", " (a 2) ")")
        :opaque (throw (ex-info "opaque functions are not modelled in TLA+" {:type ::unsupported :expr x}))
        (throw (ex-info (str "unknown expression " op) {:type ::unsupported :expr x}))))
    (map? x) (tla-map (map (fn [[k v]] [(name k) (expr->tla v scope)]) x))
    (string? x) (q x)
    (nil? x) "Nil"
    (boolean? x) (tla-bool x)
    (number? x) (str (long x))
    :else (throw (ex-info (str "literal not modelled: " (pr-str x)) {:type ::unsupported}))))

(defn- payload->tla [m scope]
  (cond
    (nil? m) "EmptyMap"
    (map? m) (expr->tla m scope)
    (and (vector? m) (= :select (first m))) (expr->tla m scope)
    :else (str "ToMap(" (expr->tla m scope) ")")))

(defn- timeout-of [specs t eff]
  (or (get-in specs [:effects eff :timeout-event]) (get-in t [:timeout :event]) ""))

(defn- op->tla
  "The accumulator after one op, as an expression over the accumulator `acc`."
  [specs t op acc scope]
  (let [[kind & args] op
        e #(expr->tla % scope)]
    (case kind
      :set (let [[path x] args]
             (case (count path)
               1 (str "[" acc " EXCEPT !.data = Put(@, " (q (name (first path))) ", " (e x) ")]")
               2 (str "[" acc " EXCEPT !.data = Put2(@, " (q (name (first path))) ", " (q (name (second path))) ", " (e x) ")]")
               (throw (ex-info "a :set path deeper than two keys is not modelled" {:type ::unsupported :op op}))))
      :inc (let [[path x] args k (q (name (first path)))]
             (str "[" acc " EXCEPT !.data = Put(@, " k ", ToInt(Get(@, " k ")) + ToInt(" (e x) "))]"))
      (:call :store) (str "[" acc " EXCEPT !.effects = Append(@, [spec |-> " (q (first args)) ", timeout |-> " (q (timeout-of specs t (first args))) "])]")
      (:await :timeout) acc
      :emit (str "[" acc " EXCEPT !.events = Append(@, [target |-> EmitTarget(ns, node, " (q (first args)) "), evt |-> " (q (first args))
                 ", source |-> node, sourceType |-> ns[node].type, payload |-> " (payload->tla (second args) scope) "])]")
      :emit-to (let [[target event payload] args
                     tgt (cond (= :self target) "node"
                               (and (vector? target) (= :var (first target)) (contains? @*id-vars* (second target))) (e target)
                               :else (str "<<" (e target) ", \"\">>"))]
                 (str "[" acc " EXCEPT !.events = Append(@, [target |-> " tgt ", evt |-> " (q event)
                      ", source |-> node, sourceType |-> ns[node].type, payload |-> " (payload->tla payload scope) "])]"))
      :child (let [x (first args)
                   id (if (and (vector? x) (= :var (first x)) (contains? @*id-vars* (second x)))
                        (e x)
                        (str "<<" (e x) ", \"\">>"))]
               (str "[" acc " EXCEPT !.children = Append(@, [id |-> " id ", type |-> " (q (second args)) "])]"))
      :each (let [[sym coll & body] args
                  nm (fresh "Each_")
                  v (str "v_" (ident (name sym)))
                  params (concat base-params (map #(str "v_" (ident (name %))) scope))
                  inner (ops->tla specs t (vec body) "acc" (conj scope sym))]
              (swap! *aux* conj
                     (str "RECURSIVE " nm "(_, _" (apply str (repeat (count params) ", _")) ")\n"
                          nm "(acc, S, " (str/join ", " params) ") ==\n"
                          "  IF S = {} THEN acc\n"
                          "  ELSE LET " v " == CHOOSE x \\in S : TRUE IN " nm "(" inner ", S \\ {" v "}, " (str/join ", " params) ")\n"))
              (str nm "(" acc ", ToSet(" (e coll) "), " (str/join ", " params) ")"))
      :let (let [[bindings & body] args
                 scope* (into scope (keys bindings))
                 _ (doseq [[sym x] bindings :when (and (vector? x) (= :str (first x)))] (swap! *id-vars* conj sym))]
             (str "(LET " (str/join " " (map (fn [[sym x]] (str "v_" (ident (name sym)) " == " (expr->tla x scope))) bindings))
                  " IN " (ops->tla specs t (vec body) acc scope*) ")"))
      :when (let [[c & body] args]
              (str "(IF " (cond->tla c scope) " THEN " (ops->tla specs t (vec body) acc scope) " ELSE " acc ")"))
      :if (let [[c then else] args]
            (str "(IF " (cond->tla c scope) " THEN " (ops->tla specs t then acc scope) " ELSE " (ops->tla specs t else acc scope) ")"))
      (throw (ex-info (str "unknown op " kind) {:type ::unsupported :op op})))))

(defn- ops->tla
  "The accumulator after a sequence of ops: a chain of LETs."
  [specs t ops acc scope]
  (if (empty? ops)
    acc
    (let [names (repeatedly (count ops) #(fresh "a"))
          steps (map vector names ops (cons acc names))]
      (str "(" (str/join " " (map (fn [[nm op prev]] (str "LET " nm " == " (op->tla specs t op prev scope) " IN")) steps))
           " " (last names) ")"))))

(defn- prop->tla
  "A business invariant (docs/design/invariants.md), compiled and inlined, as
  TLA+ over the model's state: a node variable is a node id, an effect
  variable an index into `effects`. `scope` is the vector of bound variable
  names, for the sum operators generated alongside."
  [x scope]
  (let [v (fn [sym] (str "p_" (ident (name sym))))
        e (fn [y] (prop->tla y scope))
        quals (fn [qs] (apply hash-map qs))
        coll-set (fn [coll]
                   (let [[kind x & qs] coll qs (quals qs)]
                     (case kind
                       :nodes (str "{n_ \\in DOMAIN nodes : nodes[n_].type = " (q x)
                                   (when (contains? qs :under) (str " /\\ DescendsIn(nodes, n_, " (e (:under qs)) ")"))
                                   (when (contains? qs :children-of) (str " /\\ nodes[n_].parent = " (e (:children-of qs))))
                                   (when (contains? qs :in) (str " /\\ nodes[n_].state \\in " (tla-set (map q (:in qs)))))
                                   "}")
                       :effects (str "{j_ \\in 1..Len(effects) : effects[j_].spec = " (q x)
                                     (when (contains? qs :owner) (str " /\\ effects[j_].owner = " (e (:owner qs))))
                                     (when (contains? qs :owner-under) (let [a (e (:owner-under qs))] (str " /\\ (effects[j_].owner = " a " \\/ DescendsIn(nodes, effects[j_].owner, " a "))")))
                                     (when (contains? qs :status) (str " /\\ effects[j_].status \\in " (tla-set (map q (:status qs)))))
                                     "}"))))
        set-coll? (fn [coll] (and (vector? coll) (#{:nodes :effects} (first coll))))
        over (fn [coll] (if (set-coll? coll) (coll-set coll) (str "ToSet(" (e coll) ")")))
        bool (fn [y] (let [t (e y)] (if (and (vector? y) (#{:forall :exists :none :at-most-one :exactly-one :implies :when-quiescent :root? := :< :<= :> :>= :and :or :not :nil?} (first y))) t (str "Truthy(" t ")"))))
        satisfying (fn [sym coll body] (if (nil? body) (over coll) (str "{" (v sym) " \\in " (over coll) " : " (prop->tla body (conj scope sym)) "}")))]
    (cond
      (vector? x)
      (let [[op & args] x]
        (case op
          (:nodes :effects) (coll-set x)
          :forall (let [[sym coll body] args] (str "(\\A " (v sym) " \\in " (over coll) " : " (prop->tla body (conj scope sym)) ")"))
          :exists (let [[sym coll body] args] (if (nil? body) (str "(" (over coll) " /= {})") (str "(\\E " (v sym) " \\in " (over coll) " : " (prop->tla body (conj scope sym)) ")")))
          :none (let [[sym coll body] args] (str "(" (satisfying sym coll body) " = {})"))
          :at-most-one (let [[sym coll body] args] (str "(Cardinality(" (satisfying sym coll body) ") <= 1)"))
          :exactly-one (let [[sym coll body] args] (str "(Cardinality(" (satisfying sym coll body) ") = 1)"))
          :implies (str "(" (bool (first args)) " => " (bool (second args)) ")")
          :when-quiescent (str "(Quiescent => " (bool (first args)) ")")
          :state-of (str "nodes[" (e (first args)) "].state")
          :type-of (str "nodes[" (e (first args)) "].type")
          :root? (str "(nodes[" (e (first args)) "].parent = <<>>)")
          :parent-of (str "nodes[" (e (first args)) "].parent")
          :data-of (let [[n & path] args] (get-chain (str "nodes[" (e n) "].data") path))
          :counter-of (str "CounterValueIn(nodes, " (e (first args)) ", " (q (name (second args))) ")")
          :owner-of (str "effects[" (e (first args)) "].owner")
          :status-of (str "effects[" (e (first args)) "].status")
          :effect-of (str "effects[" (e (first args)) "].spec")
          :count (if (set-coll? (first args)) (str "Cardinality(" (coll-set (first args)) ")") (str "Count(" (e (first args)) ")"))
          :sum (let [[sym coll term] args
                     nm (fresh "PSum_")
                     params (map v scope)
                     body (prop->tla term (conj scope sym))]
                 (swap! *aux* conj
                        (str "RECURSIVE " nm "(_" (apply str (repeat (count params) ", _")) ")\n"
                             nm "(S" (apply str (map #(str ", " %) params)) ") ==\n"
                             "  IF S = {} THEN 0\n"
                             "  ELSE LET " (v sym) " == CHOOSE x \\in S : TRUE IN ToInt(" body ") + " nm "(S \\ {" (v sym) "}" (apply str (map #(str ", " %) params)) ")\n"))
                 (str nm "(" (over coll) (apply str (map #(str ", " %) params)) ")"))
          :var (v (first args))
          :get (str "Get(" (e (first args)) ", " (q (name (second args))) ")")
          := (str "Eq(" (e (first args)) ", " (e (second args)) ")")
          :< (str "Lt(" (e (first args)) ", " (e (second args)) ")")
          :<= (str "Le(" (e (first args)) ", " (e (second args)) ")")
          :> (str "Gt(" (e (first args)) ", " (e (second args)) ")")
          :>= (str "Ge(" (e (first args)) ", " (e (second args)) ")")
          :+ (str "(ToInt(" (e (first args)) ") + ToInt(" (e (second args)) "))")
          :- (str "(ToInt(" (e (first args)) ") - ToInt(" (e (second args)) "))")
          :* (str "(ToInt(" (e (first args)) ") * ToInt(" (e (second args)) "))")
          :and (str "(" (str/join " /\\ " (map bool args)) ")")
          :or (str "(" (str/join " \\/ " (map bool args)) ")")
          :not (str "(~" (bool (first args)) ")")
          :nil? (str "IsNil(" (e (first args)) ")")
          :if (str "IfVal(" (bool (first args)) ", " (e (second args)) ", " (e (nth args 2)) ")")
          (throw (ex-info (str "property form not modelled in TLA+: " (pr-str op)) {:type ::unsupported :expr x}))))
      (string? x) (q x)
      (boolean? x) (tla-bool x)
      (number? x) (str (long x))
      :else (throw (ex-info (str "literal not modelled: " (pr-str x)) {:type ::unsupported})))))

(defn- invariant-name [n] (str "Inv_" (ident (name n))))

(defn- invariant-operators
  "The definition's business invariants as TLC invariants, one operator each."
  [specs]
  (binding [*aux* (atom []) *fresh* (atom 0)]
    (let [ops (mapv (fn [[n {:keys [property class]}]]
                      (str "\\* " (name n) " (" (name class) ")\n" (invariant-name n) " == " (prop->tla property []) "\n"))
                    (sort-by key (:invariants specs)))]
      (str (str/join "\n" @*aux*) (when (seq @*aux*) "\n") (str/join "\n" ops)))))

(defn- plan-operators [specs]
  (let [planned (keep-indexed (fn [i t] (when (:plan t) [(inc i) t])) (:transitions specs))
        ops (binding [*aux* (atom []) *fresh* (atom 0) *id-vars* (atom #{})]
              (let [bodies (mapv (fn [[i t]]
                                   (str "\\* " (:node-type t) "." (:from t) " -on " (:on t) "-> " (:to t) "\n"
                                        "Plan_" i "(node, d0, payload, ns) == " (ops->tla specs t (:plan t) "Acc0(d0)" []) "\n"))
                                 planned)]
                (str (str/join "\n" @*aux*) "\n" (str/join "\n" bodies))))]
    (str ops "\n"
         "RunPlan(ti, node, d0, payload, ns) ==\n"
         (if (empty? planned)
           "  Acc0(d0)\n"
           (str "  CASE " (str/join "\n    [] " (map (fn [[i _]] (str "ti = " i " -> Plan_" i "(node, d0, payload, ns)")) planned))
                "\n    [] OTHER -> Acc0(d0)\n")))))

;; --- the module ----------------------------------------------------------------------

(def ^:private helpers "
\\* --- dynamic values: node data and payloads are functions from field name, the model value Nil for absence.
\\* TLC cannot ask a value its type, but a model value compares with anything and ToString prints anything,
\\* which is what these use; a plan that compares values of different types is a plan bug, and TLC says so.
EmptyMap == [x \\in {} |-> x]
IsNil(v) == v = Nil
Get(m, k) == IF m = Nil THEN Nil ELSE IF k \\in DOMAIN m THEN m[k] ELSE Nil
Truthy(v) == ToString(v) \\notin {\"Nil\", \"FALSE\"}
Or2(a, b) == IF Truthy(a) THEN a ELSE b
ToInt(v) == IF v = Nil THEN 0 ELSE v
ToSet(v) == IF v = Nil THEN {} ELSE v
ToMap(v) == IF v = Nil THEN EmptyMap ELSE v
Put(m, k, v) == [x \\in (DOMAIN m) \\cup {k} |-> IF x = k THEN v ELSE m[x]]
Put2(m, k1, k2, v) == Put(m, k1, Put(ToMap(Get(m, k1)), k2, v))
Count(v) == Cardinality(ToSet(v))
Eq(a, b) == IF a = Nil \\/ b = Nil THEN a = b ELSE ToString(a) = ToString(b)
Lt(a, b) == a /= Nil /\\ b /= Nil /\\ a < b
Le(a, b) == a /= Nil /\\ b /= Nil /\\ a <= b
Gt(a, b) == a /= Nil /\\ b /= Nil /\\ a > b
Ge(a, b) == a /= Nil /\\ b /= Nil /\\ a >= b
IfVal(c, a, b) == IF c THEN a ELSE b
MapField(v, k) == {Get(x, k) : x \\in ToSet(v)}
RECURSIVE SumFieldR(_, _)
SumFieldR(S, k) == IF S = {} THEN 0 ELSE LET x == CHOOSE x \\in S : TRUE IN ToInt(Get(x, k)) + SumFieldR(S \\ {x}, k)
SumField(v, k) == SumFieldR(ToSet(v), k)
Remove(s, i) == SubSeq(s, 1, i - 1) \\o SubSeq(s, i + 1, Len(s))
Acc0(d0) == [data |-> d0, children |-> <<>>, events |-> <<>>, effects |-> <<>>]
")

(def ^:private runtime-rules "
\\* --- the tree ---
ChildrenIn(ns, n) == {c \\in DOMAIN ns : ns[c].parent = n}
RECURSIVE AncestorIn(_, _, _)
AncestorIn(ns, n, t) ==
  LET p == ns[n].parent IN
  IF p = <<>> THEN <<>> ELSE IF ns[p].type = t THEN p ELSE AncestorIn(ns, p, t)
RECURSIVE DescendsIn(_, _, _)
DescendsIn(ns, n, a) ==
  LET p == ns[n].parent IN
  IF p = <<>> THEN FALSE ELSE IF p = a THEN TRUE ELSE DescendsIn(ns, p, a)
IsTerminalIn(ns, n) == ns[n].state \\in TerminalOf[ns[n].type]

\\* Counters are current membership: how many children sit in the counted states.
CounterValueIn(ns, n, cname) ==
  LET specs == {j \\in 1..Len(Counters) : Counters[j].ntype = ns[n].type /\\ Counters[j].cname = cname}
  IN Cardinality({c \\in ChildrenIn(ns, n) :
                   \\E j \\in specs : ns[c].type = Counters[j].childType /\\ ns[c].state \\in Counters[j].states})

ParentAllowsIn(ns, parent, childType, childState) ==
  parent = <<>> \\/
  \\A j \\in 1..Len(Allowed) :
    (Allowed[j].ntype = ns[parent].type /\\ Allowed[j].pstate = ns[parent].state /\\ Allowed[j].childType = childType)
      => childState \\in Allowed[j].states

Live == {\"CREATED\", \"DISPATCHED\"}
LiveEffectsIn(ns, efs, n, subtree) ==
  Cardinality({j \\in 1..Len(efs) : efs[j].status \\in Live /\\ (efs[j].owner = n \\/ (subtree /\\ DescendsIn(ns, efs[j].owner, n)))})

\\* --- hierarchy rules ---
RECURSIVE CountersSum(_, _, _, _)
CountersSum(ns, n, cnames, i) == IF i > Len(cnames) THEN 0 ELSE CounterValueIn(ns, n, cnames[i]) + CountersSum(ns, n, cnames, i + 1)

GuardHoldsIn(ns, efs, g, n) ==
  CASE g.kind \\in {\"parent\", \"ancestor\"} ->
         (LET other == IF g.kind = \"parent\" THEN ns[n].parent ELSE AncestorIn(ns, n, g.ntype)
          IN other /= <<>>
             /\\ (g.allowed = {} \\/ ns[other].state \\in g.allowed)
             /\\ (g.forbidden = {} \\/ ns[other].state \\notin g.forbidden))
    [] g.kind = \"counter\" ->
         (LET v == CounterValueIn(ns, n, g.cname)
          IN (CASE g.op = \"<\" -> v < g.value [] g.op = \"<=\" -> v <= g.value [] g.op = \"=\" -> v = g.value
                [] g.op = \">=\" -> v >= g.value [] g.op = \">\" -> v > g.value [] g.op = \"!=\" -> v /= g.value))
    [] g.kind = \"cover\" ->
         (CountersSum(ns, n, g.cnames, 1) = Cardinality(ChildrenIn(ns, n)))
    [] g.kind = \"noeffects\" -> (LiveEffectsIn(ns, efs, n, g.subtree) = 0)
GuardsHoldIn(ns, efs, ti, n) == \\A j \\in 1..Len(T[ti].guards) : GuardHoldsIn(ns, efs, T[ti].guards[j], n)

TransitionsFor(nt, st, ev) == {ti \\in 1..Len(T) : T[ti].ntype = nt /\\ T[ti].src = st /\\ T[ti].evt = ev /\\ ~T[ti].auto}
\\* The first declared auto transition out of the node's state whose rules hold, or 0.
DecideAutoIn(ns, efs, n) ==
  LET S == {ti \\in 1..Len(T) : T[ti].ntype = ns[n].type /\\ T[ti].src = ns[n].state /\\ T[ti].auto /\\ GuardsHoldIn(ns, efs, ti, n)}
  IN IF S = {} THEN 0 ELSE CHOOSE ti \\in S : \\A u \\in S : ti <= u

Ignores(nt, st, ev) == <<nt, st, ev>> \\in Ignored
RouteFor(ev, fromType) == {j \\in 1..Len(Routes) : Routes[j].evt = ev /\\ Routes[j].fromType = fromType}
RouteIgnorable(ev, fromType) == \\E j \\in RouteFor(ev, fromType) : Routes[j].ignoreUnsat
\\* An emitted event goes up to the nearest ancestor its routing rule names, else to the node;
\\* \"\" drops it (no ancestor, ignorable), \"!\" is an error (no ancestor, not ignorable).
EmitTarget(ns, node, ev) ==
  LET R == RouteFor(ev, ns[node].type) IN
  IF R = {} THEN node
  ELSE LET r == Routes[CHOOSE j \\in R : TRUE]
           a == AncestorIn(ns, node, r.toType)
       IN IF a /= <<>> THEN a ELSE IF r.ignoreUnsat THEN <<>> ELSE <<\"!\", \"!\">>

")

(def ^:private runtime-steps "
\\* --- the handler is code: everything the definition says it may do (one emit per step) ---
RECURSIVE SetToSeq(_)
SetToSeq(S) == IF S = {} THEN <<>> ELSE LET x == CHOOSE x \\in S : TRUE IN <<x>> \\o SetToSeq(S \\ {x})
RECURSIVE Spawn(_, _, _, _)
Spawn(acc, node, spawns, i) ==
  IF i > Len(spawns) THEN acc
  ELSE LET n == IF spawns[i] \\in DOMAIN ChildCount THEN ChildCount[spawns[i]] ELSE 1
           kids == [k \\in 1..n |-> [id |-> <<node[1], node[2] \\o \"-\" \\o spawns[i] \\o ToString(k)>>, type |-> spawns[i]]]
       IN Spawn([acc EXCEPT !.children = @ \\o kids], node, spawns, i + 1)
Abstract(ti, node, ns, ev) ==
  LET t == T[ti]
      a1 == Spawn(Acc0(ns[node].data), node, t.spawns, 1)
      targets == IF Len(a1.children) > 0 THEN [j \\in 1..Len(a1.children) |-> a1.children[j].id]
                 ELSE LET C == ChildrenIn(ns, node) IN SetToSeq(C)
      cmds == [k \\in 1..(Len(t.cmds) * Len(targets)) |->
                 [target |-> targets[((k - 1) % Len(targets)) + 1], evt |-> t.cmds[((k - 1) \\div Len(targets)) + 1],
                  source |-> node, sourceType |-> ns[node].type, payload |-> EmptyMap]]
      a2 == [a1 EXCEPT !.events = cmds,
                       !.effects = [j \\in 1..Len(t.effects) |-> [spec |-> t.effects[j], timeout |-> t.timeout]]]
  IN IF ev = \"\" THEN a2
     ELSE [a2 EXCEPT !.events = Append(@, [target |-> EmitTarget(ns, node, ev), evt |-> ev, source |-> node,
                                             sourceType |-> ns[node].type, payload |-> EmptyMap])]

\\* --- the state ---
VARIABLES nodes, queue, effects, bad, fired, injected
vars == <<nodes, queue, effects, bad, fired, injected>>

Quiescent == queue = <<>> /\\ \\A j \\in 1..Len(effects) : effects[j].status \\notin Live
\\* The queue is in receipt order: an event is eligible when no earlier one waits for the same node (D19).
Eligible(i) == \\A j \\in 1..(i - 1) : queue[j].target /= queue[i].target

\\* Auto requests for the nodes in `ids`, one pending per node at most.
RECURSIVE Pokes(_, _, _, _)
Pokes(ns, efs, q, ids) ==
  IF ids = <<>> THEN q
  ELSE LET n == Head(ids)
           ti == DecideAutoIn(ns, efs, n)
           pending == \\E j \\in 1..Len(q) : q[j].target = n /\\ q[j].auto
       IN IF ti = 0 \\/ pending THEN Pokes(ns, efs, q, Tail(ids))
          ELSE Pokes(ns, efs, Append(q, [target |-> n, evt |-> T[ti].evt, auto |-> TRUE,
                                          source |-> n, sourceType |-> ns[n].type, payload |-> EmptyMap]),
                     Tail(ids))

Apply(id, ti, payload, rest, ev) ==
  LET t == T[ti]
      n == nodes[id]
      r == IF t.hasPlan THEN RunPlan(ti, id, n.data, payload, nodes) ELSE Abstract(ti, id, nodes, ev)
      nodes1 == [nodes EXCEPT ![id] = [@ EXCEPT !.state = t.dst, !.data = r.data]]
      newIds == {r.children[j].id : j \\in 1..Len(r.children)}
      dup == \\E j \\in 1..Len(r.children) : r.children[j].id \\in DOMAIN nodes
      typeOf(c) == r.children[CHOOSE j \\in 1..Len(r.children) : r.children[j].id = c].type
      nodes2 == nodes1 @@ [c \\in newIds |-> [type |-> typeOf(c), state |-> Initial[typeOf(c)], parent |-> id, data |-> EmptyMap]]
      effects1 == IF t.dst \\in TerminalOf[n.type]
                  THEN [j \\in 1..Len(effects) |-> IF effects[j].owner = id /\\ effects[j].status \\in Live
                                                     THEN [effects[j] EXCEPT !.status = \"CANCELLED\"] ELSE effects[j]]
                  ELSE effects
      effects2 == effects1 \\o [j \\in 1..Len(r.effects) |-> [owner |-> id, spec |-> r.effects[j].spec, status |-> \"CREATED\", timeout |-> r.effects[j].timeout]]
      kept == SelectSeq(r.events, LAMBDA e : e.target /= <<>>)
      unknownTarget == \\E j \\in 1..Len(kept) : kept[j].target = <<\"!\", \"!\">> \\/ kept[j].target \\notin DOMAIN nodes2
      evs == [j \\in 1..Len(kept) |-> [target |-> kept[j].target, evt |-> kept[j].evt, auto |-> FALSE,
                                      source |-> kept[j].source, sourceType |-> kept[j].sourceType, payload |-> kept[j].payload]]
      queue1 == rest \\o evs
      key == <<id, ti>>
  IN /\\ nodes' = nodes2
     /\\ queue' = Pokes(nodes2, effects2, queue1, <<id>> \\o [j \\in 1..Len(r.children) |-> r.children[j].id])
     /\\ effects' = effects2
     /\\ fired' = IF t.hasPlan THEN fired ELSE Put(fired, key, ToInt(Get(fired, key)) + 1)
     /\\ injected' = injected
     /\\ bad' = IF ~ParentAllowsIn(nodes, n.parent, n.type, t.dst) THEN \"child state the parent forbids: \" \\o ToString(id) \\o \" to \" \\o t.dst
               ELSE IF dup THEN \"node id created twice by \" \\o ToString(id)
               ELSE IF unknownTarget THEN \"emit to an unknown node from \" \\o ToString(id)
               ELSE bad

\\* The emit alternatives a code handler's step may take: one per step; after MaxRepeat firings, the last.
EmitChoices(ti, id) ==
  LET t == T[ti] key == <<id, ti>> IN
  IF t.hasPlan \\/ t.emits = <<>> THEN {\"\"}
  ELSE IF ToInt(Get(fired, key)) >= MaxRepeat THEN {t.emits[Len(t.emits)]}
  ELSE {t.emits[j] : j \\in 1..Len(t.emits)}

ProcessEvent(i) ==
  LET q == queue[i]
      rest == Remove(queue, i)
      n == nodes[q.target]
      S == TransitionsFor(n.type, n.state, q.evt)
      ti == IF q.auto THEN DecideAutoIn(nodes, effects, q.target) ELSE IF S = {} THEN 0 ELSE CHOOSE x \\in S : TRUE
      ignorable == q.auto \\/ Ignores(n.type, n.state, q.evt) \\/ RouteIgnorable(q.evt, q.sourceType)
  IN IF ti = 0
     THEN /\\ queue' = rest
          /\\ bad' = IF ignorable THEN bad ELSE \"rejected: \" \\o n.type \\o \" \" \\o ToString(q.target) \\o \" has no transition from \" \\o n.state \\o \" on \" \\o q.evt
          /\\ UNCHANGED <<nodes, effects, fired, injected>>
     ELSE IF ~q.auto /\\ ~GuardsHoldIn(nodes, effects, ti, q.target)
     THEN /\\ queue' = rest
          /\\ bad' = IF RouteIgnorable(q.evt, q.sourceType) THEN bad ELSE \"rejected: \" \\o n.type \\o \" \" \\o ToString(q.target) \\o \" in \" \\o n.state \\o \" on \" \\o q.evt \\o \": a hierarchy rule fails\"
          /\\ UNCHANGED <<nodes, effects, fired, injected>>
     ELSE \\E ev \\in EmitChoices(ti, q.target) : Apply(q.target, ti, q.payload, rest, ev)

\\* --- effects: what a dispatcher, a rail or a callback would do ---
Outcome(i, status, ev) ==
  /\\ effects' = [effects EXCEPT ![i].status = status]
  /\\ queue' = IF ev = \"\" THEN queue
              ELSE Append(queue, [target |-> effects[i].owner, evt |-> ev, auto |-> FALSE,
                                  source |-> <<>>, sourceType |-> \"\", payload |-> EmptyMap])
  /\\ UNCHANGED <<nodes, bad, fired, injected>>
EffectMove(i) ==
  LET e == effects[i] es == Especs[e.spec] IN
  \\/ e.status = \"CREATED\" /\\ es.dispatch /= \"\" /\\ Outcome(i, \"DISPATCHED\", es.dispatch)
  \\/ /\\ e.status \\in Live /\\ ~(e.status = \"CREATED\" /\\ es.dispatch /= \"\")
     /\\ \\/ Outcome(i, \"COMPLETED\", es.completion)
        \\/ es.failure /= \"\" /\\ Outcome(i, \"FAILED\", es.failure)
        \\/ AllowDrop /\\ Outcome(i, \"LOST\", e.timeout)

Init ==
  /\\ nodes = (RootId :> [type |-> RootType, state |-> Initial[RootType], parent |-> <<>>, data |-> EmptyMap])
  /\\ queue = << [target |-> RootId, evt |-> FirstEvent, auto |-> FALSE, source |-> <<>>, sourceType |-> \"\", payload |-> FirstPayload] >>
  /\\ effects = <<>>
  /\\ bad = \"\"
  /\\ fired = EmptyMap
  /\\ injected = {}

\\* --- the outside world: an event nothing in the definition produces, an operator's for instance,
\\* injected once per node and event where the node's state handles it ---
Inject(n, ev) ==
  /\\ <<n, ev>> \\notin injected
  /\\ TransitionsFor(nodes[n].type, nodes[n].state, ev) /= {}
  /\\ queue' = Append(queue, [target |-> n, evt |-> ev, auto |-> FALSE, source |-> <<>>, sourceType |-> \"\", payload |-> EmptyMap])
  /\\ injected' = injected \\cup {<<n, ev>>}
  /\\ UNCHANGED <<nodes, effects, bad, fired>>

Next ==
  /\\ bad = \"\"
  /\\ \\/ \\E i \\in 1..Len(queue) : Eligible(i) /\\ ProcessEvent(i)
     \\/ \\E i \\in 1..Len(effects) : EffectMove(i)
     \\/ \\E n \\in DOMAIN nodes, ev \\in External : Inject(n, ev)

Spec == Init /\\ [][Next]_vars /\\ WF_vars(Next)

\\* --- what must hold ---
NoRejection == bad = \"\"
ChildrenAllowed == \\A n \\in DOMAIN nodes : ParentAllowsIn(nodes, nodes[n].parent, nodes[n].type, nodes[n].state)
NotDead == Quiescent => IsTerminalIn(nodes, RootId)
TerminalStays == [][\\A n \\in DOMAIN nodes : IsTerminalIn(nodes, n) => nodes'[n].state = nodes[n].state]_vars
Termination == <>(IsTerminalIn(nodes, RootId))
")

;; --- the driver: the first event's payload as a TLA+ value -------------------------------

(def ^:dynamic ^:private *symbolic* nil)   ; {string -> model value name}, for symmetric ids

(defn- value->tla [v]
  (cond
    (map? v) (tla-map (map (fn [[k x]] [(name k) (value->tla x)]) v))
    (sequential? v) (tla-set (map value->tla v))
    (string? v) (or (get *symbolic* v) (q v))
    (keyword? v) (q (name v))
    (nil? v) "Nil"
    (boolean? v) (tla-bool v)
    (number? v) (str (long v))
    :else (throw (ex-info (str "payload value not modelled: " (pr-str v)) {:type ::unsupported}))))

(defn- symbolic-values
  "The string values of the payload fields named in `symmetric`, as model value
  names: those nodes are interchangeable, and TLC may identify states that
  differ only by a permutation of them."
  [payload symmetric]
  (let [fields (set (map keyword symmetric))
        found (atom [])]
    (letfn [(walk [v] (cond (map? v) (doseq [[k x] v] (if (and (fields k) (string? x)) (swap! found conj x) (walk x)))
                            (sequential? v) (run! walk v)))]
      (walk payload))
    (into {} (map (fn [s] [s (str "sym_" (ident s))])) (distinct @found))))

(defn- driver [specs {:keys [root-type root-id first-event first-payload children max-repeat allow-drop symmetric]}]
  (str "RootType == " (q root-type) "\n"
       "RootId == <<" (q root-id) ", \"\">>\n"
       "FirstEvent == " (q first-event) "\n"
       "FirstPayload == " (binding [*symbolic* (symbolic-values first-payload symmetric)] (value->tla (or first-payload {}))) "\n"
       "ChildCount == " (tla-map (for [[nt n] (sort-by key children)] [nt (str (long n))])) "\n"
       "MaxRepeat == " (long (or max-repeat 3)) "\n"
       "AllowDrop == " (tla-bool allow-drop) "\n"
       "\\* the events nothing in the definition produces: an operator's, a caller's; injected by Next\n"
       "External == " (tla-set (map q (remove #{first-event} (compiler/external-events specs)))) "\n"))

;; --- the project -------------------------------------------------------------------------

(defn module-name [specs]
  (ident (or (get-in specs [:manifest :profile :name]) "voucha")))

(defn generate
  "The TLA+ module and TLC configuration for an artifact and a run configuration
  `{:root-type :root-id :first-event :first-payload :children :max-repeat
  :allow-drop :liveness}`: a map of file name to contents."
  [{:keys [artifact-id specs]} {:keys [root-type first-event liveness] :as config}]
  (when-not (get-in specs [:node-types root-type])
    (throw (ex-info (str "unknown root node type " root-type) {:type ::bad-config})))
  (when-not (contains? (set (:events specs)) first-event)
    (throw (ex-info (str "unknown event " first-event) {:type ::bad-config})))
  (let [name* (module-name specs)
        config (update config :root-id #(or % "root"))
        symbols (vals (symbolic-values (:first-payload config) (:symmetric config)))]
    {(str name* ".tla")
     (str "---- MODULE " name* " ----\n"
          "\\* Generated by voucha from artifact " artifact-id "\n"
          "\\* Profile: " (get-in specs [:manifest :profile :name]) " " (get-in specs [:manifest :profile :version])
          ". Regenerate on every change.\n"
          "EXTENDS Integers, Sequences, FiniteSets, TLC\n"
          "CONSTANTS " (str/join ", " (cons "Nil" symbols)) "\n"
          (when (seq symbols) (str "Symm == Permutations(" (tla-set symbols) ")\n"))
          helpers "\n"
          "\\* --- the definition's tables ---\n"
          (tables specs) "\n"
          "\\* --- the run ---\n"
          (driver specs config) "\n"
          runtime-rules
          "\\* --- the handlers that are plans ---\n"
          (plan-operators specs) "\n"
          runtime-steps
          (when (seq (:invariants specs))
            (str "\n\\* --- the definition's business invariants (docs/design/invariants.md) ---\n" (invariant-operators specs)))
          "====\n")
     (str name* ".cfg")
     (str (if liveness "SPECIFICATION Spec\n" "INIT Init\nNEXT Next\n")
          "CONSTANTS\n  Nil = Nil\n" (str/join (map #(str "  " % " = " % "\n") symbols))
          (when (seq symbols) "SYMMETRY Symm\n")
          "INVARIANTS NoRejection ChildrenAllowed NotDead" (str/join (map #(str " " (invariant-name %)) (sort (keys (:invariants specs))))) "\n"
          (when liveness "PROPERTIES TerminalStays Termination\n"))}))

(defn write!
  [dir files]
  (doall (for [[rel content] (sort-by key files)
               :let [f (java.io.File. ^String dir ^String rel)]]
           (do (.mkdirs (.getParentFile f))
               (spit f content)
               (.getPath f)))))

;; --- running TLC ---------------------------------------------------------------------------

(defn tla2tools
  "The TLA+ tools jar: `TLA2TOOLS_JAR`, else the usual places."
  []
  (some #(when (and % (.isFile (java.io.File. ^String %))) %)
        [(System/getenv "TLA2TOOLS_JAR")
         "/opt/tla/tla2tools.jar"
         (str (System/getProperty "user.home") "/.local/opt/tla/tla2tools.jar")]))

(defn check!
  "Run TLC over the module in `dir`. Returns {:ok? :states :distinct :out :error
  :trace}: the counts TLC reports, and on a violation the trace it prints."
  [dir name* {:keys [workers] :or {workers "auto"}}]
  (if-let [jar (tla2tools)]
    (let [pb (doto (ProcessBuilder. ^java.util.List
                                    ["java" "-XX:+UseParallelGC" "-cp" jar "tlc2.TLC"
                                     "-workers" (str workers) "-deadlock" "-cleanup"
                                     "-config" (str name* ".cfg") (str name* ".tla")])
               (.directory (java.io.File. ^String dir))
               (.redirectErrorStream true))
          proc (.start pb)
          out (slurp (.getInputStream proc))
          exit (.waitFor proc)
          [_ generated distinct*] (re-find #"(\d+) states generated, (\d+) distinct states found" out)
          error (second (re-find #"(?m)^Error: (.*)$" out))
          trace (when error (second (re-find #"(?s)(State 1:.*?)(?:\n\d+ states generated|\z)" out)))]
      {:ok? (and (zero? exit) (nil? error) (str/includes? out "No error has been found"))
       :states (some-> generated parse-long)
       :distinct (some-> distinct* parse-long)
       :error error
       :trace trace
       :out out})
    {:ok? false :error "tla2tools.jar not found: set TLA2TOOLS_JAR or install it under /opt/tla" :out ""}))
