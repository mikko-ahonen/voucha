(ns voucha.model-p
  "A complete P model of a compiled definition, for the P model checker
  (https://p-org.github.io/P/).

  The model is the reference model `voucha.model` written in P: one `Kernel`
  machine holding the tree, the event queue and the effects, which at every
  step chooses nondeterministically among everything the real kernel could do
  next -- any eligible event, in per-node receipt order, or any outcome of any
  live effect -- so that the checker explores the interleavings. The
  definition's tables are generated into it: node types and states, the
  transitions with their guards, counters, `:child-states-allowed`, `:ignores`,
  routing and effect specs.

  A transition that carries a `:plan` (docs/design/handler-ir.md) is translated
  exactly: its ops become a P function over the node's data and the event's
  payload, both held as dynamic values (`map[string, any]`), so the model does
  what the handler does. A transition on a code handler is abstracted to what
  the definition declares it may do, and the checker tries every way of doing
  it: it moves to its `:to`, creates every effect it declares, creates the
  children its `:creates` names (as many as the run's `--children` says), sends
  its `:commands` to those children (or to every child, when it creates none),
  and emits one of its `:emit` events; fired more than `max-repeat` times on
  one node it emits only the last alternative, which bounds retry loops.

  The driver creates the root and sends it the first event with the payload the
  run gives, so the tree takes its shape from the data, as in production.

  What the checker reports as bugs: an event the kernel would reject (no
  transition, a hierarchy rule that fails and is not declared ignorable, a child
  state the parent forbids, an emit to a node that does not exist); a node that
  moves after reaching a terminal state; a tree in which nothing can move while
  the root is not terminal. With `allow-drop`, an effect may also be lost, which
  an effect or transition timeout recovers from and anything else does not."
  (:require [clojure.string :as str]))

;; --- identifiers ----------------------------------------------------------------

(defn- ident [s] (str/replace (str s) #"[^A-Za-z0-9_]" "_"))
(defn- type-id [nt] (str "NT_" (ident nt)))
(defn- state-id [nt s] (str "S_" (ident nt) "_" (ident s)))
(defn- event-id [e] (str "E_" (ident e)))
(defn- p-str [s] (str "\"" (str/replace (str s) "\"" "'") "\""))
(defn- p-bool [b] (if b "true" "false"))

(defn- seq-lines
  "Lines that build the P sequence `v` of `elem-type` from `elems` (P has no
  sequence literals)."
  [v elem-type elems]
  (into [(format "%s = default(seq[%s]);" v elem-type)]
        (map-indexed (fn [i e] (format "%s += (%d, %s);" v i e)) elems)))

;; --- the tables -------------------------------------------------------------------

(defn- effect-index [specs] (into {} (map-indexed (fn [i n] [n i])) (sort (keys (:effects specs)))))

(defn- constraint-kind [c]
  (case (:constraint c)
    :parent-state "CK_PARENT_STATE"
    :ancestor-state "CK_ANCESTOR_STATE"
    :counter "CK_COUNTER"
    :counters-cover-children "CK_COUNTERS_COVER"
    :no-outstanding-effects "CK_NO_EFFECTS"
    (throw (ex-info (str "constraint not modelled: " (:constraint c)) {:constraint c}))))

(defn- guard-lines
  "Lines appending one guard record to `gl`, using the temporaries sl, fl, cl."
  [specs nt c]
  (let [state-of (fn [s] (if-let [t (:node-type c)] (state-id t s) (state-id nt s)))
        ;; a parent-state rule names the parent type's states: find it by the state name
        parent-types (for [[pt spec] (:node-types specs) :when (some #{nt} (:may-contain spec))] pt)
        parent-state (fn [s] (or (some (fn [pt] (when (contains? (set (:states (get-in specs [:node-types pt]))) s)
                                                   (state-id pt s)))
                                       parent-types)
                                 (state-id nt s)))
        sid (case (:constraint c) :parent-state parent-state :ancestor-state state-of (fn [s] (state-id nt s)))]
    (concat (seq-lines "sl" "tState" (map sid (:allowed c)))
            (seq-lines "fl" "tState" (map sid (:forbidden c)))
            (seq-lines "cl" "string" (map (comp p-str name) (:counters c)))
            [(format "gl += (sizeof(gl), (ckind = %s, ntype = %s, allowed = sl, forbidden = fl, cname = %s, cop = %s, cvalue = %d, cnames = cl, subtree = %s));"
                     (constraint-kind c)
                     (if-let [t (:node-type c)] (type-id t) "NT_NONE")
                     (p-str (some-> (:counter c) name))
                     (p-str (some-> (:op c) name))
                     (long (or (:value c) 0))
                     (p-bool (= :subtree (:scope c))))])))

(defn- transition-lines [specs eidx {:keys [node-type from on to auto effects emit creates commands requires timeout plan]}]
  (concat (seq-lines "el" "int" (map eidx effects))
          (seq-lines "em" "tEvent" (map event-id emit))
          (seq-lines "sp" "tNodeType" (map type-id creates))
          (seq-lines "cm" "tEvent" (map event-id commands))
          ["gl = default(seq[tGuard]);"]
          (mapcat #(guard-lines specs node-type %) requires)
          [(format "trs += (sizeof(trs), (ntype = %s, src = %s, evt = %s, dst = %s, isAuto = %s, hasPlan = %s, effects = el, emits = em, spawns = sp, cmds = cm, guards = gl, timeoutEvt = %s));"
                   (type-id node-type) (state-id node-type from) (event-id on) (state-id node-type to)
                   (p-bool auto) (p-bool (some? plan)) (if-let [e (:event timeout)] (event-id e) "E_NONE"))]))

(defn- load-tables [specs]
  (let [eidx (effect-index specs)
        nts (sort-by key (:node-types specs))]
    (concat
     (for [[nt {:keys [initial-state]}] nts] (format "initialOf[%s] = %s;" (type-id nt) (state-id nt initial-state)))
     (for [[nt {:keys [terminal-states]}] nts, s terminal-states] (format "terminal[%s] = true;" (state-id nt s)))
     (for [[nt {:keys [counters]}] nts, [cname {:keys [child-type when-child-enters]}] (sort-by key counters)
           line (concat (seq-lines "sl" "tState" (map #(state-id child-type %) when-child-enters))
                        [(format "counters += (sizeof(counters), (ntype = %s, cname = %s, childType = %s, states = sl));"
                                 (type-id nt) (p-str (name cname)) (type-id child-type))])]
       line)
     (for [[nt {:keys [child-states-allowed]}] nts, [ps by-child] (sort-by key child-states-allowed), [ct states] (sort-by key by-child)
           line (concat (seq-lines "sl" "tState" (map #(state-id ct %) states))
                        [(format "allowed += (sizeof(allowed), (ntype = %s, pstate = %s, childType = %s, states = sl));"
                                 (type-id nt) (state-id nt ps) (type-id ct))])]
       line)
     (for [[nt {:keys [ignores]}] nts, [s evs] (sort-by key ignores), e evs]
       (format "ignores += (sizeof(ignores), (ntype = %s, nstate = %s, evt = %s));" (type-id nt) (state-id nt s) (event-id e)))
     (for [{:keys [event from-node-type to-node-type on-unsatisfied]} (:routing specs)]
       (format "routes += (sizeof(routes), (evt = %s, fromType = %s, toType = %s, ignoreUnsat = %s));"
               (event-id event) (type-id from-node-type) (type-id to-node-type) (p-bool (= :ignore on-unsatisfied))))
     (for [[n _] (sort-by val eidx) :let [e (get-in specs [:effects n])]]
       (format "especs += (sizeof(especs), (ename = %s, dispatchEvent = %s, completionEvent = %s, failureEvent = %s, timeoutEvt = %s));"
               (p-str n)
               (if-let [x (:dispatch-event e)] (event-id x) "E_NONE")
               (if-let [x (:completion-event e)] (event-id x) "E_NONE")
               (if-let [x (:failure-event e)] (event-id x) "E_NONE")
               (if-let [x (:timeout-event e)] (event-id x) "E_NONE")))
     (mapcat #(transition-lines specs eidx %) (:transitions specs)))))

(defn- name-fun [fname typ pairs]
  (str "fun " fname "(x: " typ "): string {\n"
       (str/join (map (fn [[id s]] (format "  if (x == %s) return %s;\n" id (p-str s))) pairs))
       "  return \"?\";\n}\n"))

(defn- enums-and-names [specs]
  (let [nts (sort (keys (:node-types specs)))
        states (for [nt nts, s (get-in specs [:node-types nt :states])] [(state-id nt s) s])
        events (sort (:events specs))]
    (str "enum tNodeType { " (str/join ", " (concat (map type-id nts) ["NT_NONE"])) " }\n"
         "enum tState { " (str/join ", " (concat (map first states) ["S_NONE"])) " }\n"
         "enum tEvent { " (str/join ", " (concat (map event-id events) ["E_NONE"])) " }\n\n"
         (name-fun "TypeName" "tNodeType" (map (fn [nt] [(type-id nt) nt]) nts))
         (name-fun "StateName" "tState" states)
         (name-fun "EventName" "tEvent" (map (fn [e] [(event-id e) e]) events)))))

;; --- plans: the IR translated to P ---------------------------------------------------

(def ^:private max-map-arity 12)

(defn- map-call
  "A `MapN(k, v, ...)` call building a dynamic map from key-expression pairs."
  [pairs]
  (when (> (count pairs) max-map-arity)
    (throw (ex-info (str "a map in a plan may have at most " max-map-arity " keys") {:type ::unsupported})))
  (if (empty? pairs)
    "EmptyMap()"
    (format "Map%d(%s)" (count pairs) (str/join ", " (mapcat (fn [[k v]] [(p-str (name k)) v]) pairs)))))

(declare expr->p)

;; sums with a term become Kernel functions, collected while a plan is translated
(def ^:dynamic ^:private *sums* nil)

(defn- get-chain [base ks] (reduce (fn [acc k] (format "Get(%s, %s)" acc (p-str (name k)))) base ks))

(def ^:private bool-ops #{:= :< :<= :> :>= :and :not :nil? :opaque})

(defn- cond->p
  "An expression in a condition: a bool."
  [x]
  (if (and (vector? x) (bool-ops (first x)))
    (expr->p x)
    (format "Truthy(%s)" (expr->p x))))

(defn- expr->p
  "A plan expression as a P expression. Values are dynamic (`any`); the
  comparison and logic forms yield `bool`, the arithmetic forms `int`."
  [x]
  (cond
    (vector? x)
    (let [[op & args] x
          a (fn [i] (expr->p (nth args i)))]
      (case op
        :data (get-chain "d0" args)
        :payload (get-chain "payload" args)
        :node-id "node"
        :counter (format "CounterValue(node, %s)" (p-str (name (first args))))
        :var (str "v_" (ident (name (first args))))
        :get (format "Get(%s, %s)" (a 0) (p-str (name (second args))))
        :select (let [e (a 0)] (map-call (map (fn [k] [k (format "Get(%s, %s)" e (p-str (name k)))]) (second args))))
        :count (format "Count(%s)" (a 0))
        :sum (if (= 2 (count args))
               (format "SumField(%s, %s)" (a 0) (p-str (name (second args))))
               (let [[sym coll term] args
                     n (count (swap! *sums* conj {:sym sym :term term}))]
                 (format "Sum_%d(node, d0, payload, ToSeq(%s))" n (expr->p coll))))
        :map (format "MapField(%s, %s)" (a 0) (p-str (name (second args))))
        :+ (format "(ToInt(%s) + ToInt(%s))" (a 0) (a 1))
        :- (format "(ToInt(%s) - ToInt(%s))" (a 0) (a 1))
        :* (format "(ToInt(%s) * ToInt(%s))" (a 0) (a 1))
        :if (format "IfVal(%s, %s, %s)" (cond->p (first args)) (a 1) (a 2))
        :str (format "format(%s, %s)" (p-str (apply str (map #(str "{" % "}") (range (count args)))))
                     (str/join ", " (map expr->p args)))
        := (format "(%s == %s)" (a 0) (a 1))
        :< (format "Lt(%s, %s)" (a 0) (a 1))
        :<= (format "Le(%s, %s)" (a 0) (a 1))
        :> (format "Gt(%s, %s)" (a 0) (a 1))
        :>= (format "Ge(%s, %s)" (a 0) (a 1))
        :and (str "(" (str/join " && " (map cond->p args)) ")")
        :or (reduce (fn [acc e] (format "Or2(%s, %s)" e acc)) (expr->p (last args)) (map expr->p (reverse (butlast args))))
        :not (format "(!%s)" (cond->p (first args)))
        :nil? (format "(%s == null)" (a 0))
        :opaque "$"
        (throw (ex-info (str "unknown expression " op) {:type ::unsupported :expr x}))))
    (map? x) (map-call (map (fn [[k v]] [k (expr->p v)]) x))
    (string? x) (p-str x)
    (nil? x) "null"
    (boolean? x) (p-bool x)
    (number? x) (str (long x))
    :else (throw (ex-info (str "literal not modelled: " (pr-str x)) {:type ::unsupported}))))

(defn- payload->p
  "A payload expression as a typed map: a map literal or a :select build one;
  any other expression is a dynamic value cast to a map."
  [m]
  (cond
    (nil? m) "EmptyMap()"
    (map? m) (expr->p m)
    (and (vector? m) (= :select (first m))) (expr->p m)
    :else (format "ToMap(%s)" (expr->p m))))

(defn- ops->p
  "Statements for a plan's ops. `temps` collects the locals the function needs."
  [ops eidx temps pad]
  (str/join
   (for [[kind & args :as op] ops]
     (case kind
       :set (let [[path e] args]
              (case (count path)
                1 (format "%snodeData[node] = SetIn1(nodeData[node], %s, %s);\n" pad (p-str (name (first path))) (expr->p e))
                2 (format "%snodeData[node] = SetIn2(nodeData[node], %s, %s, %s);\n" pad (p-str (name (first path))) (p-str (name (second path))) (expr->p e))
                (throw (ex-info "a :set path deeper than two keys is not modelled" {:type ::unsupported :op op}))))
       :inc (let [[path e] args]
              (when (not= 1 (count path)) (throw (ex-info "an :inc path deeper than one key is not modelled" {:type ::unsupported :op op})))
              (format "%snodeData[node] = SetIn1(nodeData[node], %s, ToInt(Get(nodeData[node], %s)) + ToInt(%s));\n"
                      pad (p-str (name (first path))) (p-str (name (first path))) (expr->p e)))
       (:call :store) (format "%sAddEffect(node, %d, ti);\n" pad (eidx (first args)))
       :await (format "%s// awaits %s\n" pad (first args))
       :timeout (format "%s// arms a timeout: %s, in the transition's table\n" pad (second args))
       :emit (format "%sEmit(node, %s, %s);\n" pad (event-id (first args)) (payload->p (second args)))
       :emit-to (let [[target event payload] args]
                  (if (= :self target)
                    (format "%sEnqueue(node, %s, false, node, nodeType[node], %s);\n" pad (event-id event) (payload->p payload))
                    (format "%sEnqueueTo(ToStr(%s), %s, node, %s);\n" pad (expr->p target) (event-id event) (payload->p payload))))
       :child (format "%sNewNode(%s, node, ToStr(%s));\n" pad (type-id (second args)) (expr->p (first args)))
       :each (let [[sym coll & body] args
                   n (count (swap! temps conj (str "v_" (ident (name sym)))))
                   s (str "s_" n) i (str "i_" n)]
               (swap! temps conj s i)
               (str (format "%s%s = ToSeq(%s);\n%s%s = 0;\n%swhile (%s < sizeof(%s)) {\n" pad s (expr->p coll) pad i pad i s)
                    (format "%s  v_%s = %s[%s];\n" pad (ident (name sym)) s i)
                    (ops->p (vec body) eidx temps (str pad "  "))
                    (format "%s  %s = %s + 1;\n%s}\n" pad i i pad)))
       :let (let [[bindings & body] args]
              (str (str/join (for [[sym e] bindings]
                               (do (swap! temps conj (str "v_" (ident (name sym))))
                                   (format "%sv_%s = %s;\n" pad (ident (name sym)) (expr->p e)))))
                   (ops->p (vec body) eidx temps pad)))
       :when (let [[c & body] args]
               (str (format "%sif (%s) {\n" pad (cond->p c))
                    (ops->p (vec body) eidx temps (str pad "  "))
                    (format "%s}\n" pad)))
       :if (let [[c then else] args]
             (str (format "%sif (%s) {\n" pad (cond->p c))
                  (ops->p then eidx temps (str pad "  "))
                  (format "%s} else {\n" pad)
                  (ops->p else eidx temps (str pad "  "))
                  (format "%s}\n" pad)))
       (throw (ex-info (str "unknown op " kind) {:type ::unsupported :op op}))))))

(defn- plan-fun
  "A P function for one planned transition, index `ti`."
  [eidx ti t]
  (let [temps (atom [])
        body (ops->p (:plan t) eidx temps "    ")
        decls (for [v (distinct @temps)]
                (format "    var %s: %s;\n" v (cond (str/starts-with? v "s_") "seq[any]" (str/starts-with? v "i_") "int" :else "any")))]
    (str (format "  // %s.%s -on %s-> %s\n" (:node-type t) (:from t) (:on t) (:to t))
         (format "  fun Plan_%d(node: string, payload: map[string, any], ti: int) {\n" ti)
         "    var d0: map[string, any];\n"
         (str/join decls)
         "    d0 = nodeData[node];\n"
         body
         "  }\n")))

(defn- sum-fun
  "A Kernel function summing a term over a collection, for `[:sum :x coll term]`."
  [n {:keys [sym term]}]
  (str (format "  fun Sum_%d(node: string, d0: map[string, any], payload: map[string, any], s: seq[any]): int {\n" n)
       "    var i: int;\n    var t: int;\n"
       (format "    var v_%s: any;\n" (ident (name sym)))
       "    i = 0;\n    t = 0;\n    while (i < sizeof(s)) {\n"
       (format "      v_%s = s[i];\n" (ident (name sym)))
       (format "      t = t + ToInt(%s);\n" (expr->p term))
       "      i = i + 1;\n    }\n    return t;\n  }\n"))

(defn- plan-funs [specs]
  (let [eidx (effect-index specs)
        planned (keep-indexed (fn [i t] (when (:plan t) [i t])) (:transitions specs))
        sums (atom [])
        funs (binding [*sums* sums] (mapv (fn [[i t]] (plan-fun eidx i t)) planned))]
    (str (str/join "\n" funs)
         (str/join (map-indexed (fn [i s] (sum-fun (inc i) s)) @sums))
         "\n  fun RunPlan(ti: int, node: string, payload: map[string, any]) {\n"
         (str/join (map (fn [[i _]] (format "    if (ti == %d) { Plan_%d(node, payload, ti); return; }\n" i i)) planned))
         "    assert false, format(\"no plan for transition {0}\", ti);\n  }\n")))

(defn- helpers-source []
  (str "
// --- dynamic values: node data and payloads are maps of any, as in the kernel ---

fun EmptyMap(): map[string, any] { var m: map[string, any]; return m; }
fun Get(m: any, k: string): any {
  var mm: map[string, any];
  if (m == null) return null;
  mm = m as map[string, any];
  if (k in mm) return mm[k];
  return null;
}
fun Truthy(v: any): bool {
  if (v == null) return false;
  if (v == false) return false;
  return true;
}
fun Or2(a: any, b: any): any { if (Truthy(a)) return a; return b; }
fun IfVal(c: bool, a: any, b: any): any { if (c) return a; return b; }
fun ToInt(v: any): int { if (v == null) return 0; return v as int; }
fun ToStr(v: any): string { if (v == null) return \"\"; return format(\"{0}\", v); }
fun ToSeq(v: any): seq[any] { var e: seq[any]; if (v == null) return e; return v as seq[any]; }
fun ToMap(v: any): map[string, any] { var e: map[string, any]; if (v == null) return e; return v as map[string, any]; }
fun Count(v: any): int { return sizeof(ToSeq(v)); }
fun Lt(a: any, b: any): bool { if (a == null || b == null) return false; return (a as int) < (b as int); }
fun Le(a: any, b: any): bool { if (a == null || b == null) return false; return (a as int) <= (b as int); }
fun Gt(a: any, b: any): bool { if (a == null || b == null) return false; return (a as int) > (b as int); }
fun Ge(a: any, b: any): bool { if (a == null || b == null) return false; return (a as int) >= (b as int); }
fun SumField(v: any, k: string): int {
  var s: seq[any]; var i: int; var t: int;
  s = ToSeq(v); i = 0; t = 0;
  while (i < sizeof(s)) { t = t + ToInt(Get(s[i], k)); i = i + 1; }
  return t;
}
fun MapField(v: any, k: string): seq[any] {
  var s: seq[any]; var r: seq[any]; var i: int;
  s = ToSeq(v); i = 0;
  while (i < sizeof(s)) { r += (sizeof(r), Get(s[i], k)); i = i + 1; }
  return r;
}
fun SetIn1(m: map[string, any], k: string, v: any): map[string, any] { m[k] = v; return m; }
fun SetIn2(m: map[string, any], k1: string, k2: string, v: any): map[string, any] {
  var inner: map[string, any];
  if (k1 in m && m[k1] != null) { inner = m[k1] as map[string, any]; }
  inner[k2] = v;
  m[k1] = inner;
  return m;
}
"
       (str/join (for [n (range 1 (inc max-map-arity))]
                   (str "fun Map" n "(" (str/join ", " (for [i (range n)] (format "k%d: string, v%d: any" i i))) "): map[string, any] {\n"
                        "  var m: map[string, any];\n"
                        (str/join (for [i (range n)] (format "  m[k%d] = v%d;\n" i i)))
                        "  return m;\n}\n")))))

;; --- the runtime: the reference model, in P -------------------------------------------

(def ^:private preamble "
enum tConstraintKind { CK_PARENT_STATE, CK_ANCESTOR_STATE, CK_COUNTER, CK_COUNTERS_COVER, CK_NO_EFFECTS }
enum tEffectStatus { ES_CREATED, ES_DISPATCHED, ES_COMPLETED, ES_FAILED, ES_CANCELLED, ES_LOST }

type tGuard = (ckind: tConstraintKind, ntype: tNodeType, allowed: seq[tState], forbidden: seq[tState],
               cname: string, cop: string, cvalue: int, cnames: seq[string], subtree: bool);
type tTransition = (ntype: tNodeType, src: tState, evt: tEvent, dst: tState, isAuto: bool, hasPlan: bool,
                    effects: seq[int], emits: seq[tEvent], spawns: seq[tNodeType], cmds: seq[tEvent],
                    guards: seq[tGuard], timeoutEvt: tEvent);
type tCounter = (ntype: tNodeType, cname: string, childType: tNodeType, states: seq[tState]);
type tAllowed = (ntype: tNodeType, pstate: tState, childType: tNodeType, states: seq[tState]);
type tIgnore = (ntype: tNodeType, nstate: tState, evt: tEvent);
type tRoute = (evt: tEvent, fromType: tNodeType, toType: tNodeType, ignoreUnsat: bool);
type tEffectSpec = (ename: string, dispatchEvent: tEvent, completionEvent: tEvent, failureEvent: tEvent, timeoutEvt: tEvent);
type tQueued = (qid: int, target: string, evt: tEvent, isAuto: bool, source: string, sourceType: tNodeType, payload: map[string, any]);
type tEffect = (eid: int, owner: string, especRef: int, status: tEffectStatus, timeoutEvt: tEvent);
type tConfig = (rootType: tNodeType, rootId: string, firstEvent: tEvent, firstPayload: map[string, any],
                childCount: map[tNodeType, int], maxRepeat: int, allowDrop: bool);

event eStep;
event eAllDone;
event eTransitioned: (node: string, nstate: tState, isTerminal: bool);
")

(def ^:private kernel-head "
machine Kernel {
  var cfg: tConfig;
  var trs: seq[tTransition];
  var counters: seq[tCounter];
  var allowed: seq[tAllowed];
  var ignores: seq[tIgnore];
  var routes: seq[tRoute];
  var especs: seq[tEffectSpec];
  var initialOf: map[tNodeType, tState];
  var terminal: map[tState, bool];

  var nodeType: map[string, tNodeType];
  var nodeState: map[string, tState];
  var nodeParent: map[string, string];
  var nodeData: map[string, map[string, any]];
  var nodeIds: seq[string];
  var created: seq[string];
  var queue: seq[tQueued];
  var nextQ: int;
  var effects: seq[tEffect];
  var fired: map[string, int];
  var root: string;

  start state Init {
    entry (c: tConfig) {
      cfg = c;
      LoadTables();
      nextQ = 1;
      root = NewNode(cfg.rootType, \"\", cfg.rootId);
      Enqueue(root, cfg.firstEvent, false, \"\", NT_NONE, cfg.firstPayload);
      send this, eStep;
      goto Running;
    }
  }

  state Running {
    on eStep do { Step(); }
  }

  // --- one step: everything the kernel could do next, one of them chosen ---

  fun Step() {
    var moves: seq[int];
    var i: int;
    var e: tEffect;
    var es: tEffectSpec;
    var m: int;
    i = 0;
    while (i < sizeof(queue)) {
      if (Eligible(i)) { moves += (sizeof(moves), i); }
      i = i + 1;
    }
    i = 0;
    while (i < sizeof(effects)) {
      e = effects[i];
      es = especs[e.especRef];
      if (e.status == ES_CREATED && es.dispatchEvent != E_NONE) {
        moves += (sizeof(moves), 100000 + i * 8 + 0);
      } else if (e.status == ES_CREATED || e.status == ES_DISPATCHED) {
        moves += (sizeof(moves), 100000 + i * 8 + 1);
        if (es.failureEvent != E_NONE) { moves += (sizeof(moves), 100000 + i * 8 + 2); }
        if (cfg.allowDrop) { moves += (sizeof(moves), 100000 + i * 8 + 3); }
      }
      i = i + 1;
    }
    if (sizeof(moves) == 0) {
      assert nodeState[root] in terminal,
        format(\"dead tree: nothing can move, but the root is {0}\", StateName(nodeState[root]));
      announce eAllDone;
      return;
    }
    m = choose(moves);
    if (m < 100000) { ProcessEvent(m); }
    else { ApplyEffect((m - 100000) / 8, (m - 100000) % 8); }
    CheckInvariants();
    send this, eStep;
  }

  // An event is eligible when no older event waits for the same node (D19).
  fun Eligible(i: int): bool {
    var j: int;
    j = 0;
    while (j < sizeof(queue)) {
      if (j != i && queue[j].target == queue[i].target && queue[j].qid < queue[i].qid) { return false; }
      j = j + 1;
    }
    return true;
  }

  // --- events ---

  fun ProcessEvent(i: int) {
    var q: tQueued;
    var nt: tNodeType;
    var st: tState;
    var ti: int;
    q = queue[i];
    queue -= (i);
    nt = nodeType[q.target];
    st = nodeState[q.target];
    if (q.isAuto) {
      ti = DecideAuto(q.target);
      if (ti < 0) { return; }          // the decision went stale while queued
    } else {
      ti = TransitionFor(nt, st, q.evt);
      if (ti < 0) {
        if (IgnoresDeclared(nt, st, q.evt) || RouteIgnorable(q)) { return; }
        assert false, format(\"rejected: {0} {1} has no transition from {2} on {3}\",
                             TypeName(nt), q.target, StateName(st), EventName(q.evt));
      }
      if (!GuardsHold(ti, q.target)) {
        if (RouteIgnorable(q)) { return; }
        assert false, format(\"rejected: {0} {1} in {2} on {3}: a hierarchy rule fails\",
                             TypeName(nt), q.target, StateName(st), EventName(q.evt));
      }
    }
    Apply(q.target, ti, q.payload);
  }

  fun Apply(node: string, ti: int, payload: map[string, any]) {
    var t: tTransition;
    var parent: string;
    var cmdTargets: seq[string];
    var i: int;
    var j: int;
    var n: int;
    var c: string;
    var ev: tEvent;
    var isTerm: bool;
    var key: string;
    var e: tEffect;
    t = trs[ti];
    parent = nodeParent[node];
    key = format(\"{0}#{1}\", node, ti);
    if (!(key in fired)) { fired[key] = 0; }
    fired[key] = fired[key] + 1;
    assert ParentAllows(parent, nodeType[node], t.dst),
      format(\"{0} {1} may not enter {2} under its parent in {3}\",
             TypeName(nodeType[node]), node, StateName(t.dst), StateName(nodeState[parent]));
    nodeState[node] = t.dst;
    isTerm = t.dst in terminal;
    announce eTransitioned, (node = node, nstate = t.dst, isTerminal = isTerm);
    if (isTerm) {
      i = 0;
      while (i < sizeof(effects)) {
        e = effects[i];
        if (e.owner == node && (e.status == ES_CREATED || e.status == ES_DISPATCHED)) {
          e.status = ES_CANCELLED;
          effects[i] = e;
        }
        i = i + 1;
      }
    }
    created = default(seq[string]);
    if (t.hasPlan) {
      // the handler is data: run it
      RunPlan(ti, node, payload);
    } else {
      // the handler is code: everything the definition says it may do
      i = 0;
      while (i < sizeof(t.spawns)) {
        n = 1;
        if (t.spawns[i] in cfg.childCount) { n = cfg.childCount[t.spawns[i]]; }
        j = 0;
        while (j < n) {
          c = NewNode(t.spawns[i], node, format(\"{0}-{1}{2}\", node, TypeName(t.spawns[i]), j));
          j = j + 1;
        }
        i = i + 1;
      }
      if (sizeof(created) > 0) { cmdTargets = created; } else { cmdTargets = ChildrenOf(node); }
      i = 0;
      while (i < sizeof(t.cmds)) {
        j = 0;
        while (j < sizeof(cmdTargets)) {
          Enqueue(cmdTargets[j], t.cmds[i], false, node, nodeType[node], EmptyMap());
          j = j + 1;
        }
        i = i + 1;
      }
      i = 0;
      while (i < sizeof(t.effects)) { AddEffect(node, t.effects[i], ti); i = i + 1; }
      if (sizeof(t.emits) > 0) {
        if (fired[key] > cfg.maxRepeat) { ev = t.emits[sizeof(t.emits) - 1]; }
        else { ev = choose(t.emits); }
        Emit(node, ev, EmptyMap());
      }
    }
    FireAuto(node);
    i = 0;
    while (i < sizeof(created)) { FireAuto(created[i]); i = i + 1; }
  }

  fun AddEffect(node: string, especRef: int, ti: int) {
    var ev: tEvent;
    ev = especs[especRef].timeoutEvt;
    if (ev == E_NONE) { ev = trs[ti].timeoutEvt; }
    effects += (sizeof(effects), (eid = sizeof(effects), owner = node, especRef = especRef, status = ES_CREATED, timeoutEvt = ev));
  }

  // An emitted event goes up to the nearest ancestor its routing rule names, or
  // to the node itself when no rule routes it.
  fun Emit(node: string, ev: tEvent, payload: map[string, any]) {
    var r: int;
    var target: string;
    r = RouteFor(ev, nodeType[node]);
    if (r < 0) { Enqueue(node, ev, false, node, nodeType[node], payload); return; }
    target = Ancestor(node, routes[r].toType);
    if (target == \"\") {
      assert routes[r].ignoreUnsat, format(\"{0} from {1} {2} has no {3} ancestor to go to\",
                                           EventName(ev), TypeName(nodeType[node]), node, TypeName(routes[r].toType));
      return;
    }
    Enqueue(target, ev, false, node, nodeType[node], payload);
  }

  // A command to a node by id: the node must exist, as the kernel's plan validation requires.
  fun EnqueueTo(target: string, ev: tEvent, source: string, payload: map[string, any]) {
    assert target in nodeType, format(\"{0} {1} emits {2} to {3}, which does not exist\",
                                      TypeName(nodeType[source]), source, EventName(ev), target);
    Enqueue(target, ev, false, source, nodeType[source], payload);
  }

  fun Enqueue(target: string, ev: tEvent, isAuto: bool, source: string, sourceType: tNodeType, payload: map[string, any]) {
    queue += (sizeof(queue), (qid = nextQ, target = target, evt = ev, isAuto = isAuto, source = source, sourceType = sourceType, payload = payload));
    nextQ = nextQ + 1;
  }

  // A state's auto transitions fire on entry: one request to decide, kept
  // pending per node, re-decided when it is processed.
  fun FireAuto(node: string) {
    var ti: int;
    var i: int;
    ti = DecideAuto(node);
    if (ti < 0) { return; }
    i = 0;
    while (i < sizeof(queue)) {
      if (queue[i].target == node && queue[i].isAuto) { return; }
      i = i + 1;
    }
    Enqueue(node, trs[ti].evt, true, node, nodeType[node], EmptyMap());
  }

  fun DecideAuto(node: string): int {
    var i: int;
    i = 0;
    while (i < sizeof(trs)) {
      if (trs[i].ntype == nodeType[node] && trs[i].src == nodeState[node] && trs[i].isAuto && GuardsHold(i, node)) { return i; }
      i = i + 1;
    }
    return -1;
  }

  fun TransitionFor(nt: tNodeType, st: tState, ev: tEvent): int {
    var i: int;
    i = 0;
    while (i < sizeof(trs)) {
      if (trs[i].ntype == nt && trs[i].src == st && trs[i].evt == ev && !trs[i].isAuto) { return i; }
      i = i + 1;
    }
    return -1;
  }

  fun IgnoresDeclared(nt: tNodeType, st: tState, ev: tEvent): bool {
    var i: int;
    i = 0;
    while (i < sizeof(ignores)) {
      if (ignores[i].ntype == nt && ignores[i].nstate == st && ignores[i].evt == ev) { return true; }
      i = i + 1;
    }
    return false;
  }

  fun RouteFor(ev: tEvent, fromType: tNodeType): int {
    var i: int;
    i = 0;
    while (i < sizeof(routes)) {
      if (routes[i].evt == ev && routes[i].fromType == fromType) { return i; }
      i = i + 1;
    }
    return -1;
  }

  fun RouteIgnorable(q: tQueued): bool {
    var r: int;
    if (q.sourceType == NT_NONE) { return false; }
    r = RouteFor(q.evt, q.sourceType);
    return r >= 0 && routes[r].ignoreUnsat;
  }

  // --- hierarchy rules ---

  fun GuardsHold(ti: int, node: string): bool {
    var i: int;
    i = 0;
    while (i < sizeof(trs[ti].guards)) {
      if (!GuardHolds(trs[ti].guards[i], node)) { return false; }
      i = i + 1;
    }
    return true;
  }

  fun GuardHolds(g: tGuard, node: string): bool {
    var other: string;
    var st: tState;
    var v: int;
    var total: int;
    var i: int;
    if (g.ckind == CK_PARENT_STATE || g.ckind == CK_ANCESTOR_STATE) {
      if (g.ckind == CK_PARENT_STATE) { other = nodeParent[node]; } else { other = Ancestor(node, g.ntype); }
      if (other == \"\") { return false; }
      st = nodeState[other];
      if (sizeof(g.allowed) > 0 && !(st in g.allowed)) { return false; }
      if (sizeof(g.forbidden) > 0 && (st in g.forbidden)) { return false; }
      return true;
    }
    if (g.ckind == CK_COUNTER) {
      v = CounterValue(node, g.cname);
      if (g.cop == \"<\") return v < g.cvalue;
      if (g.cop == \"<=\") return v <= g.cvalue;
      if (g.cop == \"=\") return v == g.cvalue;
      if (g.cop == \">=\") return v >= g.cvalue;
      if (g.cop == \">\") return v > g.cvalue;
      if (g.cop == \"!=\") return v != g.cvalue;
      assert false, format(\"unknown counter operator {0}\", g.cop);
    }
    if (g.ckind == CK_COUNTERS_COVER) {
      total = 0;
      i = 0;
      while (i < sizeof(g.cnames)) { total = total + CounterValue(node, g.cnames[i]); i = i + 1; }
      return total == sizeof(ChildrenOf(node));
    }
    if (g.ckind == CK_NO_EFFECTS) {
      return LiveEffects(node, g.subtree) == 0;
    }
    return false;
  }

  // Counters are current membership: how many children sit in the counted states.
  fun CounterValue(node: string, cname: string): int {
    var i: int;
    var j: int;
    var v: int;
    var kids: seq[string];
    v = 0;
    i = 0;
    while (i < sizeof(counters)) {
      if (counters[i].ntype == nodeType[node] && counters[i].cname == cname) {
        kids = ChildrenOf(node);
        j = 0;
        while (j < sizeof(kids)) {
          if (nodeType[kids[j]] == counters[i].childType && (nodeState[kids[j]] in counters[i].states)) { v = v + 1; }
          j = j + 1;
        }
      }
      i = i + 1;
    }
    return v;
  }

  fun ParentAllows(parent: string, childType: tNodeType, childState: tState): bool {
    var i: int;
    if (parent == \"\") { return true; }
    i = 0;
    while (i < sizeof(allowed)) {
      if (allowed[i].ntype == nodeType[parent] && allowed[i].pstate == nodeState[parent] && allowed[i].childType == childType) {
        return childState in allowed[i].states;
      }
      i = i + 1;
    }
    return true;
  }

  fun LiveEffects(node: string, subtree: bool): int {
    var i: int;
    var n: int;
    n = 0;
    i = 0;
    while (i < sizeof(effects)) {
      if ((effects[i].status == ES_CREATED || effects[i].status == ES_DISPATCHED)
          && (effects[i].owner == node || (subtree && Descends(effects[i].owner, node)))) { n = n + 1; }
      i = i + 1;
    }
    return n;
  }

  // --- the tree ---

  fun NewNode(nt: tNodeType, parent: string, id: string): string {
    assert !(id in nodeType), format(\"node id {0} created twice\", id);
    nodeType[id] = nt;
    nodeState[id] = initialOf[nt];
    nodeParent[id] = parent;
    nodeData[id] = EmptyMap();
    nodeIds += (sizeof(nodeIds), id);
    created += (sizeof(created), id);
    return id;
  }

  fun ChildrenOf(node: string): seq[string] {
    var i: int;
    var kids: seq[string];
    i = 0;
    while (i < sizeof(nodeIds)) {
      if (nodeParent[nodeIds[i]] == node) { kids += (sizeof(kids), nodeIds[i]); }
      i = i + 1;
    }
    return kids;
  }

  fun Ancestor(node: string, nt: tNodeType): string {
    var a: string;
    a = nodeParent[node];
    while (a != \"\") {
      if (nodeType[a] == nt) { return a; }
      a = nodeParent[a];
    }
    return \"\";
  }

  fun Descends(node: string, ancestor: string): bool {
    var a: string;
    a = nodeParent[node];
    while (a != \"\") {
      if (a == ancestor) { return true; }
      a = nodeParent[a];
    }
    return false;
  }

  // --- effects: what a dispatcher, a rail or a callback would do ---

  fun ApplyEffect(i: int, outcome: int) {
    var e: tEffect;
    var es: tEffectSpec;
    e = effects[i];
    es = especs[e.especRef];
    if (outcome == 0) {
      e.status = ES_DISPATCHED;
      effects[i] = e;
      Enqueue(e.owner, es.dispatchEvent, false, \"\", NT_NONE, EmptyMap());
    } else if (outcome == 1) {
      e.status = ES_COMPLETED;
      effects[i] = e;
      if (es.completionEvent != E_NONE) { Enqueue(e.owner, es.completionEvent, false, \"\", NT_NONE, EmptyMap()); }
    } else if (outcome == 2) {
      e.status = ES_FAILED;
      effects[i] = e;
      Enqueue(e.owner, es.failureEvent, false, \"\", NT_NONE, EmptyMap());
    } else {
      // lost: no answer ever comes; a declared timeout is the only way on
      e.status = ES_LOST;
      effects[i] = e;
      if (e.timeoutEvt != E_NONE) { Enqueue(e.owner, e.timeoutEvt, false, \"\", NT_NONE, EmptyMap()); }
    }
  }

  // --- invariants, after every step ---

  fun CheckInvariants() {
    var i: int;
    var n: string;
    i = 0;
    while (i < sizeof(nodeIds)) {
      n = nodeIds[i];
      assert ParentAllows(nodeParent[n], nodeType[n], nodeState[n]),
        format(\"{0} {1} is in {2}, which its parent in {3} forbids\",
               TypeName(nodeType[n]), n, StateName(nodeState[n]), StateName(nodeState[nodeParent[n]]));
      i = i + 1;
    }
  }
")

(def ^:private specs-source "
// A node that reached a terminal state never moves again.
spec TerminalOnce observes eTransitioned {
  var done: map[string, bool];
  start state Watching {
    on eTransitioned do (m: (node: string, nstate: tState, isTerminal: bool)) {
      assert !(m.node in done), format(\"node {0} moved to {1} after reaching a terminal state\", m.node, StateName(m.nstate));
      if (m.isTerminal) { done[m.node] = true; }
    }
  }
}

// Every run ends with the tree finished: the kernel announces it when nothing
// can move and the root is terminal, and asserts the root is terminal first.
spec Liveness observes eAllDone {
  start hot state Running { on eAllDone goto Done; }
  cold state Done { }
}
")

;; --- the driver: the first event's payload as P data ---------------------------------

(defn- value-lines
  "Statements building an EDN value as a dynamic P value. Returns
  [expression declarations statements]; maps and vectors get a variable each."
  [v counter]
  (cond
    (map? v)
    (let [n (swap! counter inc) var* (str "m" n)
          parts (map (fn [[k x]] [(p-str (name k)) (value-lines x counter)]) v)]
      [var*
       (concat [(format "var %s: map[string, any];" var*)] (mapcat (fn [[_ [_ d _]]] d) parts))
       (concat (mapcat (fn [[_ [_ _ s]]] s) parts)
               [(format "%s = default(map[string, any]);" var*)]
               (map (fn [[k [e _ _]]] (format "%s[%s] = %s;" var* k e)) parts))])
    (sequential? v)
    (let [n (swap! counter inc) var* (str "s" n)
          parts (map #(value-lines % counter) v)]
      [var*
       (concat [(format "var %s: seq[any];" var*)] (mapcat second parts))
       (concat (mapcat #(nth % 2) parts)
               [(format "%s = default(seq[any]);" var*)]
               (map-indexed (fn [i [e _ _]] (format "%s += (%d, %s);" var* i e)) parts))])
    (string? v) [(p-str v) [] []]
    (keyword? v) [(p-str (name v)) [] []]
    (nil? v) ["null" [] []]
    (boolean? v) [(p-bool v) [] []]
    (number? v) [(str (long v)) [] []]
    :else (throw (ex-info (str "payload value not modelled: " (pr-str v)) {:type ::unsupported}))))

(defn- driver-source [{:keys [root-type root-id first-event first-payload children max-repeat allow-drop]}]
  (let [counter (atom 0)
        [pexpr decls stmts] (value-lines (or first-payload {}) counter)
        pexpr (if (map? first-payload) pexpr "EmptyMap()")]
    (str "machine Driver {\n  start state Init {\n    entry {\n      var cc: map[tNodeType, int];\n"
         (str/join (map #(str "      " % "\n") decls))
         (str/join (map #(str "      " % "\n") stmts))
         (str/join (for [[nt n] (sort-by key children)] (format "      cc[%s] = %d;\n" (type-id nt) (long n))))
         (format "      new Kernel((rootType = %s, rootId = %s, firstEvent = %s, firstPayload = %s, childCount = cc, maxRepeat = %d, allowDrop = %s));\n"
                 (type-id root-type) (p-str root-id) (event-id first-event) pexpr (long (or max-repeat 3)) (p-bool allow-drop))
         "    }\n  }\n}\n\n"
         "test tcVoucha [main=Driver]: assert TerminalOnce, Liveness in { Driver, Kernel };\n")))

;; --- the project ------------------------------------------------------------------

(defn project-name [specs]
  (ident (or (get-in specs [:manifest :profile :name]) "voucha")))

(def ^:dynamic ^:private *stmts* nil)

(def ^:private p-statuses
  "The effect statuses the model has; a property over another status is vacuous here."
  {"CREATED" "ES_CREATED" "DISPATCHED" "ES_DISPATCHED" "COMPLETED" "ES_COMPLETED" "FAILED" "ES_FAILED" "CANCELLED" "ES_CANCELLED" "LOST" "ES_LOST"})

(defn- prop-fun
  "A business invariant (docs/design/invariants.md), compiled and inlined, as
  a P function returning bool: quantifiers are loops over the node ids or the
  effects, a node variable a node id, an effect variable an effect record."
  [specs n property]
  (let [decls (atom []) fresh (atom 0)
        declare! (fn [prefix type] (let [nm (str prefix (swap! fresh inc))] (swap! decls conj (str "    var " nm ": " type ";")) nm))
        v (fn [sym] (str "p_" (ident (name sym))))
        type-of-var (atom {})]
    (letfn [(value [x env]
              ;; an expression as a P value of type any (or the bool/int/string it is)
              (cond
                (vector? x)
                (let [[op & args] x]
                  (case op
                    :var (v (first args))
                    :state-of (str "nodeState[" (value (first args) env) "]")
                    :type-of (str "nodeType[" (value (first args) env) "]")
                    :root? (str "(nodeParent[" (value (first args) env) "] == \"\")")
                    :parent-of (str "nodeParent[" (value (first args) env) "]")
                    :data-of (let [[e & path] args] (reduce (fn [acc k] (str "Get(" acc ", " (p-str (name k)) ")")) (str "nodeData[" (value e env) "]") path))
                    :counter-of (str "CounterValue(" (value (first args) env) ", " (p-str (name (second args))) ")")
                    :owner-of (str (value (first args) env) ".owner")
                    :status-of (str (value (first args) env) ".status")
                    :effect-of (str "especs[" (value (first args) env) ".especRef].ename")
                    :get (str "Get(" (value (first args) env) ", " (p-str (name (second args))) ")")
                    :+ (str "(ToInt(" (value (first args) env) ") + ToInt(" (value (second args) env) "))")
                    :- (str "(ToInt(" (value (first args) env) ") - ToInt(" (value (second args) env) "))")
                    :* (str "(ToInt(" (value (first args) env) ") * ToInt(" (value (second args) env) "))")
                    :if (str "IfVal(" (truth x env) ", " (value (second args) env) ", " (value (nth args 2) env) ")")
                    (:count :sum :forall :exists :none :at-most-one :exactly-one :implies :when-quiescent := :< :<= :> :>= :and :or :not :nil?)
                    (statement-value x env)
                    (throw (ex-info (str "property form not modelled in P: " (pr-str op)) {:type ::unsupported :expr x}))))
                (string? x) (p-str x)
                (boolean? x) (p-bool x)
                (number? x) (str (long x))
                :else (throw (ex-info (str "literal not modelled: " (pr-str x)) {:type ::unsupported}))))
            (eq-literal [a b env]
              ;; a comparison with a state, a type or a status literal uses the enum
              (let [lit (fn [y other]
                          (cond
                            (and (string? y) (vector? other) (= :state-of (first other)))
                            (let [t (get @type-of-var (second (second other)))]
                              (if t (state-id t y) (throw (ex-info (str "state " y " compared on a node of unknown type") {:type ::unsupported}))))
                            (and (string? y) (vector? other) (= :type-of (first other))) (type-id y)
                            (and (string? y) (vector? other) (= :status-of (first other))) (get p-statuses y "ES_NONE_")
                            :else nil))]
                (or (when-let [l (lit b a)] (str "(" (value a env) " == " l ")"))
                    (when-let [l (lit a b)] (str "(" l " == " (value b env) ")"))
                    (str "(" (value a env) " == " (value b env) ")"))))
            (truth [x env]
              ;; an expression as a P bool
              (if (vector? x)
                (let [[op & args] x]
                  (case op
                    := (eq-literal (first args) (second args) env)
                    :< (str "Lt(" (value (first args) env) ", " (value (second args) env) ")")
                    :<= (str "Le(" (value (first args) env) ", " (value (second args) env) ")")
                    :> (str "Gt(" (value (first args) env) ", " (value (second args) env) ")")
                    :>= (str "Ge(" (value (first args) env) ", " (value (second args) env) ")")
                    :and (str "(" (str/join " && " (map #(truth % env) args)) ")")
                    :or (str "(" (str/join " || " (map #(truth % env) args)) ")")
                    :not (str "(!" (truth (first args) env) ")")
                    :nil? (str "(" (value (first args) env) " == null)")
                    :root? (value x env)
                    :implies (str "(!" (truth (first args) env) " || " (truth (second args) env) ")")
                    :when-quiescent (str "(!Quiescent() || " (truth (first args) env) ")")
                    (:forall :exists :none :at-most-one :exactly-one) (statement-value x env)
                    (str "Truthy(" (value x env) ")")))
                (str "Truthy(" (value x env) ")")))
            (loop-over [coll env body-fn]
              ;; statements iterating a collection, calling body-fn with the element expression
              (let [[kind x & qs] (when (vector? coll) coll)
                    qs (if (#{:nodes :effects} kind) (apply hash-map qs) {})]
                (case kind
                  :nodes (let [i (declare! "i" "int") nm (declare! "n" "string")
                               cond* (str/join " && "
                                              (concat [(str "nodeType[" nm "] == " (type-id x))]
                                                      (when (contains? qs :under) [(str "Descends(" nm ", " (value (:under qs) env) ")")])
                                                      (when (contains? qs :children-of) [(str "nodeParent[" nm "] == " (value (:children-of qs) env))])
                                                      (when (contains? qs :in) [(str "(" (str/join " || " (map #(str "nodeState[" nm "] == " (state-id x %)) (:in qs))) ")")])))]
                           (str "    " i " = 0;\n    while (" i " < sizeof(nodeIds)) {\n      " nm " = nodeIds[" i "];\n      if (" cond* ") {\n"
                                (body-fn nm x) "      }\n      " i " = " i " + 1;\n    }\n"))
                  :effects (let [i (declare! "i" "int") ef (declare! "e" "tEffect")
                                 cond* (str/join " && "
                                                (concat [(str "especs[" ef ".especRef].ename == " (p-str x))]
                                                        (when (contains? qs :owner) [(str ef ".owner == " (value (:owner qs) env))])
                                                        (when (contains? qs :owner-under) (let [a (value (:owner-under qs) env)] [(str "(" ef ".owner == " a " || Descends(" ef ".owner, " a "))")]))
                                                        (when (contains? qs :status) [(str "(" (str/join " || " (map #(str ef ".status == " (get p-statuses % "ES_NONE_")) (:status qs))) ")")])))]
                             (str "    " i " = 0;\n    while (" i " < sizeof(effects)) {\n      " ef " = effects[" i "];\n      if (" cond* ") {\n"
                                  (body-fn ef nil) "      }\n      " i " = " i " + 1;\n    }\n"))
                  ;; a data collection: a seq of values
                  (let [i (declare! "i" "int") sq (declare! "s" "seq[any]") el (declare! "x" "any")]
                    (str "    " sq " = ToSeq(" (value coll env) ");\n    " i " = 0;\n    while (" i " < sizeof(" sq ")) {\n      " el " = " sq "[" i "];\n"
                         (body-fn el nil) "      " i " = " i " + 1;\n    }\n")))))
            (statement-value [x env]
              ;; a quantifier, a count or a sum: computed into a fresh variable by statements
              ;; emitted before the current statement, returned as the variable's name
              (let [[op & args] x]
                (case op
                  (:forall :exists :none :at-most-one :exactly-one :count :sum)
                  (let [[sym coll body] (if (#{:count} op) [nil (first args) nil] args)
                        term (when (= :sum op) (nth args 2))
                        body (if (= :sum op) nil body)
                        acc (if (#{:count :sum :at-most-one :exactly-one} op) (declare! "c" "int") (declare! "b" "bool"))
                        init (case op :forall "true" :exists "false" :none "true" "0")
                        elem-type (when (and (vector? coll) (= :nodes (first coll))) (second coll))
                        _ (when sym (swap! type-of-var assoc sym elem-type))
                        inner (fn [el _]
                                (let [env* (if sym (assoc env sym el) env)
                                      bind (if sym (str "        " (v sym) " = " el ";\n") "")
                                      ;; the body's own statements go inside the loop
                                      stmts (atom "")
                                      test (binding [*stmts* stmts] (when body (truth body env*)))
                                      sum-term (binding [*stmts* stmts] (when term (value term env*)))]
                                  (str bind @stmts
                                       (case op
                                         :forall (str "        if (!" test ") { " acc " = false; }\n")
                                         :exists (str "        if (" (or test "true") ") { " acc " = true; }\n")
                                         :none (str "        if (" (or test "true") ") { " acc " = false; }\n")
                                         (:at-most-one :exactly-one) (str "        if (" (or test "true") ") { " acc " = " acc " + 1; }\n")
                                         :count (str "        " acc " = " acc " + 1;\n")
                                         :sum (str "        " acc " = " acc " + ToInt(" sum-term ");\n")))))
                        _ (when sym (swap! decls conj (str "    var " (v sym) ": " (if elem-type "string" (if (and (vector? coll) (= :effects (first coll))) "tEffect" "any")) ";")))
                        code (str "    " acc " = " init ";\n" (loop-over coll env inner))]
                    (swap! *stmts* str code)
                    (case op
                      :at-most-one (str "(" acc " <= 1)")
                      :exactly-one (str "(" acc " == 1)")
                      acc))
                  (truth x env))))]
      (let [stmts (atom "")
            result (binding [*stmts* stmts] (truth property {}))]
        (str "  fun Inv_" (ident (name n)) "(): bool {\n"
             (str/join "\n" (distinct @decls)) (when (seq @decls) "\n")
             @stmts
             "    return " result ";\n  }\n")))))

(defn- invariant-funs
  "The definition's business invariants as P functions, and the asserts that
  run them after every step."
  [specs]
  {:funs (str/join "\n" (map (fn [[n {:keys [property class]}]] (str "  // " (name n) " (" (name class) ")\n" (prop-fun specs n property)))
                             (sort-by key (:invariants specs))))
   :asserts (str/join (map (fn [[n _]] (str "    assert Inv_" (ident (name n)) "(), \"business invariant " (name n) " fails\";\n"))
                           (sort-by key (:invariants specs))))})

(defn generate
  "The P project for an artifact and a run configuration
  `{:root-type :root-id :first-event :first-payload :children {type n}
  :max-repeat :allow-drop}`: a map of relative file path to contents.
  Deterministic for its inputs."
  [{:keys [artifact-id specs]} {:keys [root-type first-event] :as config}]
  (when-not (get-in specs [:node-types root-type])
    (throw (ex-info (str "unknown root node type " root-type) {:type ::bad-config})))
  (when-not (contains? (set (:events specs)) first-event)
    (throw (ex-info (str "unknown event " first-event) {:type ::bad-config})))
  (let [name* (project-name specs)
        config (update config :root-id #(or % "root"))
        header (str "// Generated by voucha from artifact " artifact-id "\n"
                    "// Profile: " (get-in specs [:manifest :profile :name]) " "
                    (get-in specs [:manifest :profile :version]) ". Regenerate on every change.\n\n")]
    {(str name* ".pproj")
     (str "<Project>\n  <ProjectName>" name* "</ProjectName>\n  <InputFiles>\n"
          "    <PFile>./PSrc/</PFile>\n    <PFile>./PSpec/</PFile>\n    <PFile>./PTst/</PFile>\n"
          "  </InputFiles>\n  <OutputDir>./PGenerated/</OutputDir>\n  <Target>PChecker</Target>\n</Project>\n")
     "PSrc/Definition.p" (str header (enums-and-names specs) preamble (helpers-source))
     "PSrc/Kernel.p" (str header
                          (let [{:keys [funs asserts]} (invariant-funs specs)]
                            (-> kernel-head
                                (str/replace "  // --- invariants, after every step ---\n"
                                             (str "  // --- the definition's business invariants (docs/design/invariants.md) ---\n"
                                                  "  fun Quiescent(): bool {\n    var i: int;\n    if (sizeof(queue) > 0) { return false; }\n"
                                                  "    i = 0;\n    while (i < sizeof(effects)) { if (effects[i].status == ES_CREATED || effects[i].status == ES_DISPATCHED) { return false; } i = i + 1; }\n    return true;\n  }\n"
                                                  funs "\n  // --- invariants, after every step ---\n"))
                                (str/replace "    var n: string;\n    i = 0;" (str "    var n: string;\n" asserts "    i = 0;"))))
                          "\n  // --- the handlers that are plans ---\n"
                          (plan-funs specs)
                          "\n  // --- the definition's tables ---\n  fun LoadTables() {\n"
                          "    var sl: seq[tState];\n    var fl: seq[tState];\n    var cl: seq[string];\n"
                          "    var el: seq[int];\n    var em: seq[tEvent];\n    var sp: seq[tNodeType];\n"
                          "    var cm: seq[tEvent];\n    var gl: seq[tGuard];\n"
                          (str/join (map #(str "    " % "\n") (load-tables specs)))
                          "  }\n}\n")
     "PSpec/Specs.p" (str header specs-source)
     "PTst/Driver.p" (str header (driver-source config))}))

(defn write!
  "Write the project under `dir`; returns the paths written."
  [dir files]
  (doall (for [[rel content] (sort-by key files)
               :let [f (java.io.File. ^String dir ^String rel)]]
           (do (.mkdirs (.getParentFile f))
               (spit f content)
               (.getPath f)))))

;; --- running the checker ---------------------------------------------------------

(defn- run-tool! [dir & cmd]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec cmd))
             (.directory (java.io.File. ^String dir))
             (.redirectErrorStream true))
        proc (.start pb)
        out (slurp (.getInputStream proc))]
    {:exit (.waitFor proc) :out out}))

(defn check!
  "Compile the project in `dir` with `p compile` and run `p check` over
  `schedules` schedules. Returns {:ok? :bugs :out :trace}, the trace being the
  checker's readable trace of the first bug, when there is one."
  [dir name* {:keys [schedules] :or {schedules 1000}}]
  (let [c (run-tool! dir "p" "compile")]
    (if-not (zero? (:exit c))
      {:ok? false :bugs nil :out (:out c) :error "p compile failed"}
      (let [r (run-tool! dir "p" "check" "-tc" "tcVoucha" "--schedules" (str schedules))
            bugs (some->> (re-find #"Found (\d+) bug" (:out r)) second parse-long)
            trace (java.io.File. ^String dir (str "PCheckerOutput/BugFinding/" name* "_0_0.txt"))]
        {:ok? (and (zero? (:exit r)) (= 0 bugs))
         :bugs bugs
         :out (:out r)
         :trace (when (and bugs (pos? bugs) (.isFile trace)) (slurp trace))}))))
