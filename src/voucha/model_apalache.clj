(ns voucha.model-apalache
  "The typed, abstracted TLA+ specification of a compiled definition, for
  Apalache: the symbolic checker, and the induction check that is the step
  from a bounded instance to every size (docs/design/handler-ir.md).

  The same model as `voucha.model-tla`, typed as Apalache requires, and
  abstracted to what a proof reads. Node ids are integers allocated fresh.
  Node data is a record of the scalar fields of the node types' `:data`
  schemas, a payload a record of the scalar fields of the `:payloads`
  schemas; collections are not carried in the state. A plan that creates one
  child per record of a collection creates instead some number of children,
  chosen nondeterministically up to a bound, each commanded with an arbitrary
  record of the scalars its schema declares, which over-approximates every
  file and is sound for the properties. A count or a sum over a collection is
  an arbitrary integer for the same reason. There is no absent value: a field
  has its type's default, and a field a handler asks about with `:nil?` is
  declared `[:maybe T]` and carried with a `some` flag. Counting rules become
  quantifiers; loops are folds; the ancestor walk is unrolled to the depth of
  the hierarchy; there is no recursion and no CHOOSE. The definition is
  specialised: one action per transition with literal states, guards,
  destination and emits, and no table indexed by a symbolic value, which is
  what keeps a step cheap for the solver.

  The invariant the induction starts from is well-formedness as the runtime
  guarantees it, derived from the definition (which types create which, where
  a creator, a sender or an effect's owner can be, who may have sent a queued
  event, one auto request per node, creation commands first, every node
  accepting its queued events in order), the properties, and the
  application's lemmas.

  Two uses. `check!` runs the bounded symbolic check: every behaviour of up to
  `length` steps from the initial state. `induction!` runs the three checks of
  an inductive invariant: the initial state satisfies it, one step from any
  state satisfying it stays in it, and it implies the properties. The
  invariant is the well-formedness of the state, the properties, and the
  application's lemmas; when a step breaks it, Apalache prints the state it
  broke from, which is what to strengthen the lemmas from. A proof by
  induction holds for every size, which no enumeration does."
  (:require [clojure.set]
            [clojure.string :as str]))

;; --- names ---------------------------------------------------------------------------

(defn- q [s] (str "\"" (str/replace (str s) "\"" "'") "\""))
(defn- tla-bool [b] (if b "TRUE" "FALSE"))
(defn- tla-seq [xs] (if (empty? xs) "<<>>" (str "<<" (str/join ", " xs) ">>")))
(defn- tla-set [xs] (str "{" (str/join ", " xs) "}"))
(defn- tla-record [pairs] (str "[" (str/join ", " (map (fn [[k v]] (str k " |-> " v)) pairs)) "]"))
(defn- fname
  "A schema field as a TLA+ record field: an identifier."
  [k] (str/replace (name k) #"[^A-Za-z0-9_]" "_"))
(defn- ident [s] (str/replace (str s) #"[^A-Za-z0-9_]" "_"))

(defn- fail [what & [x]]
  (throw (ex-info (str what (when x (str ": " (pr-str x)))) {:type ::unsupported :expr x})))

;; --- schemas ---------------------------------------------------------------------------

(defn- maybe? [t] (and (vector? t) (= :maybe (first t))))
(defn- set-type? [t] (and (vector? t) (= :set (first t))))
(defn- scalar? [t] (or (#{:int :str :bool} t) (maybe? t)))

(defn- scalars
  "A record schema reduced to its scalar fields: what the typed state carries."
  [schema]
  (into {} (filter (fn [[_ t]] (scalar? t)) schema)))

(defn- tla-type [t]
  (cond
    (= :int t) "Int"
    (= :str t) "Str"
    (= :bool t) "Bool"
    (maybe? t) (str "{ some: Bool, v: " (tla-type (second t)) " }")
    (map? t) (str "{ " (str/join ", " (map (fn [[k v]] (str (fname k) ": " (tla-type v))) (sort-by (comp name key) t))) " }")
    :else (fail "schema type not carried in the typed state" t)))

(defn- default-of [t]
  (cond
    (= :int t) "0"
    (= :str t) "\"\""
    (= :bool t) "FALSE"
    (maybe? t) (str "[some |-> FALSE, v |-> " (default-of (second t)) "]")
    (map? t) (tla-record (map (fn [[k v]] [(fname k) (default-of v)]) (sort-by (comp name key) t)))
    :else (fail "schema type not carried in the typed state" t)))

(defn- union-schema [schemas what]
  (reduce (fn [acc [k t]]
            (if (and (contains? acc k) (not= (get acc k) t))
              (fail (str what " field " k " has two types") [(get acc k) t])
              (assoc acc k t)))
          {} (mapcat seq schemas)))

(defn- schemas
  "The scalar data record (the union over node types), the scalar payload
  record (the union over events), and the full per-type and per-event
  schemas, which say what a loop variable over a collection is."
  [specs]
  (let [by-type (into {} (map (fn [[nt s]] [nt (or (:data s) {})])) (:node-types specs))
        by-event (or (:payloads specs) {})]
    (when (every? empty? (vals by-type))
      (fail "the definition declares no :data schemas; the typed specification needs them"))
    {:data (scalars (union-schema (vals by-type) "data"))
     :payload (scalars (union-schema (vals by-event) "payload"))
     :by-type by-type
     :by-event by-event}))

;; --- plans: typed, abstracted translation ---------------------------------------------

(declare expr->apa)

(defn- type-of
  "The schema type of an expression: a scalar type, a record schema (a loop
  variable over a collection), `[:set T]` for a collection, :id for a node
  id, or nil when unknown."
  [x env]
  (cond
    (vector? x)
    (let [[op & args] x]
      (case op
        :data (reduce (fn [t k] (when (map? t) (or (get t k) (get (:data-union env) k)))) (:data-type env) args)
        :payload (reduce (fn [t k] (when (map? t) (or (get t k) (get (:payload-union env) k)))) (:payload-type env) args)
        :var (get-in env [:vars (first args)])
        :get (let [t (type-of (first args) env)] (when (map? t) (get t (second args))))
        :select :payload
        :map (let [t (type-of (first args) env)] (when (set-type? t) [:set (get (second t) (second args))]))
        (:count :sum :+ :- :* :counter) :int
        (:= :< :<= :> :>= :and :not :nil? :opaque) :bool
        :or (let [t (type-of (first args) env)] (if (maybe? t) (second t) t))
        :if (type-of (second args) env)
        (:str :node-id) :id
        nil))
    (string? x) :str
    (number? x) :int
    (boolean? x) :bool
    :else nil))

(defn- present [x env]
  (if (maybe? (type-of x env))
    (str "(" (expr->apa x env) ").v")
    (expr->apa x env)))

(defn- collection? [x env] (set-type? (type-of x env)))

(defn- expr->apa
  [x env]
  (cond
    (vector? x)
    (let [[op & args] x
          a (fn [i] (present (nth args i) env))]
      (case op
        :data (let [t (type-of x env)]
                (if (or (nil? t) (set-type? t))
                  (fail (str "data field " (vec args) " is a collection or undeclared: not carried in the typed state") x)
                  (str/join "." (cons "d0" (map fname args)))))
        :payload (let [t (type-of x env)]
                   (if (or (nil? t) (set-type? t))
                     (fail (str "payload field " (vec args) " is a collection or undeclared: not carried in the typed state") x)
                     (str/join "." (cons "payload" (map fname args)))))
        :node-id "node"
        :counter (let [cname (first args)]
                   (when-not (get-in env [:counters cname]) (fail (str "counter " cname " is not a counter of " (:node-type env)) x))
                   (str "CounterValue_" (ident (:node-type env)) "_" (ident (name cname)) "(ns, node)"))
        :var (str "v_" (ident (name (first args))))
        :get (str "(" (expr->apa (first args) env) ")." (fname (second args)))
        :select (let [e (expr->apa (first args) env) t (type-of (first args) env)
                      shared (for [k (second args) :when (and (map? t) (scalar? (get t k)) (contains? (:payload-union env) k))] k)]
                  (if (empty? shared) "DefaultPayload"
                      (str "[DefaultPayload EXCEPT " (str/join ", " (map #(str "!." (fname %) " = (" e ")." (fname %)) shared)) "]")))
        ;; a count or a sum over a collection the state does not carry: any integer
        :count (if (collection? (first args) env) "Gen(1)" (fail ":count of something that is not a collection" x))
        :sum (if (collection? (if (= 2 (count args)) (first args) (second args)) env) "Gen(1)" (fail ":sum over something that is not a collection" x))
        :map (fail "a projection of a collection is not carried in the typed state" x)
        :+ (str "(" (a 0) " + " (a 1) ")")
        :- (str "(" (a 0) " - " (a 1) ")")
        :* (str "(" (a 0) " * " (a 1) ")")
        ;; an id a handler builds names the child it creates next: the next fresh id
        :str (str (:acc env) ".next")
        := (str "(" (a 0) " = " (a 1) ")")
        :< (str "(" (a 0) " < " (a 1) ")")
        :<= (str "(" (a 0) " <= " (a 1) ")")
        :> (str "(" (a 0) " > " (a 1) ")")
        :>= (str "(" (a 0) " >= " (a 1) ")")
        :and (str "(" (str/join " /\\ " (map #(expr->apa % env) args)) ")")
        :or (let [[f & more] args t (type-of f env)
                  rest* (if (= 1 (count more)) (first more) (into [:or] more))]
              (cond
                (= :bool t) (str "(" (str/join " \\/ " (map #(expr->apa % env) args)) ")")
                (maybe? t) (str "(IF (" (expr->apa f env) ").some THEN (" (expr->apa f env) ").v ELSE " (present rest* env) ")")
                (nil? t) (fail "the type of the first alternative of :or is unknown" x)
                :else (str "(IF " (expr->apa f env) " /= " (default-of t) " THEN " (expr->apa f env) " ELSE " (present rest* env) ")")))
        :not (str "(~" (expr->apa (first args) env) ")")
        :nil? (let [t (type-of (first args) env)]
                (cond (maybe? t) (str "(~(" (expr->apa (first args) env) ").some)")
                      (nil? t) (fail "the type of a :nil? operand is unknown" x)
                      :else (str "(" (expr->apa (first args) env) " = " (default-of t) ")")))
        :if (str "(IF " (expr->apa (first args) env) " THEN " (a 1) " ELSE " (a 2) ")")
        :opaque (fail "opaque functions are not modelled" x)
        (fail "unknown expression" x)))
    (string? x) (q x)
    (nil? x) (fail "nil has no type; declare the field [:maybe T] and test it with :nil?")
    (boolean? x) (tla-bool x)
    (number? x) (str (long x))
    :else (fail "literal not modelled" x)))

(defn- payload->apa [m env]
  (cond
    (nil? m) "DefaultPayload"
    (map? m) (if (empty? m) "DefaultPayload"
                 (str "[DefaultPayload EXCEPT "
                      (str/join ", " (for [[k v] m]
                                       (do (when-not (contains? (:payload-union env) k)
                                             (fail (str "payload field " k " is not in any :payloads schema (or is a collection)") m))
                                           (when (= :id (type-of v env))
                                             (fail (str "payload field " k " would carry a node id, which the typed state does not") m))
                                           (str "!." (fname k) " = " (present v env))))) "]"))
    (and (vector? m) (= :select (first m))) (expr->apa m env)
    :else (let [t (type-of m env)]
            (if (map? t)
              (let [shared (filter #(and (scalar? (get t %)) (contains? (:payload-union env) %)) (sort-by name (keys t)))
                    e (expr->apa m env)]
                (if (empty? shared) "DefaultPayload"
                    (str "[DefaultPayload EXCEPT " (str/join ", " (map (fn [k] (str "!." (fname k) " = (" e ")." (fname k))) shared)) "]")))
              (fail "a payload must be a map, a :select, or a record from the schemas" m)))))

(defn- timeout-of [specs t eff]
  (or (get-in specs [:effects eff :timeout-event]) (get-in t [:timeout :event]) ""))

(declare ops->apa)

(defn- op->apa [specs t op acc env]
  (let [env (assoc env :acc acc)
        [kind & args] op
        data-type (:data-type env)]
    (case kind
      :set (let [[path x] args k (first path) ft (or (get data-type k) (get (:data-union env) k))]
             (when (not= 1 (count path)) (fail "a :set path deeper than one key is not modelled in the typed specification" op))
             (cond
               (nil? ft) (fail (str "data field " k " is not in any :data schema") op)
               (set-type? ft) acc        ; collections are not carried
               (maybe? ft) (str "[" acc " EXCEPT !.data = [@ EXCEPT !." (fname k) " = "
                                (if (maybe? (type-of x env)) (expr->apa x env) (str "[some |-> TRUE, v |-> " (present x env) "]")) "]]")
               :else (str "[" acc " EXCEPT !.data = [@ EXCEPT !." (fname k) " = " (present x env) "]]")))
      :inc (let [[path x] args k (first path)]
             (when-not (= :int (or (get data-type k) (get (:data-union env) k))) (fail (str "data field " k " is not an :int in the schema") op))
             (str "[" acc " EXCEPT !.data = [@ EXCEPT !." (fname k) " = @ + " (present x env) "]]"))
      (:call :store) (str "[" acc " EXCEPT !.effects = Append(@, [spec |-> " (q (first args)) ", timeout |-> " (q (timeout-of specs t (first args))) "])]")
      (:await :timeout) acc
      :emit (str "[" acc " EXCEPT !.events = Append(@, [target |-> EmitTarget(ns, node, " (q (first args)) "), evt |-> " (q (first args))
                 ", eff |-> NoId, auto |-> FALSE, source |-> node, sourceType |-> ns[node].type, payload |-> " (payload->apa (second args) env) "])]")
      :emit-to (let [[target event payload] args
                     tgt (cond (= :self target) "node"
                               (= :id (type-of target env)) (expr->apa target env)
                               ;; a child named by a field of its record: the child created just before
                               :else (str "(" acc ".next - 1)"))]
                 (str "[" acc " EXCEPT !.events = Append(@, [target |-> " tgt ", evt |-> " (q event)
                      ", eff |-> NoId, auto |-> FALSE, source |-> node, sourceType |-> ns[node].type, payload |-> " (payload->apa payload env) "])]"))
      ;; a child takes the next fresh id, whatever the handler named it
      :child (str "[" acc " EXCEPT !.children = @ \\cup {[id |-> " acc ".next, type |-> " (q (second args)) "]}, !.next = @ + 1]")
      :each (let [[sym coll & body] args
                  ct (type-of coll env)
                  v (str "v_" (ident (name sym)))]
              (cond
                ;; the node's children, from the tree
                (and (set-type? ct) (= :str (second ct)))
                (let [env* (assoc-in env [:vars sym] :id)]
                  (str "(LET \\* @type: ($acc, Int) => $acc;\n        Step(acc, " v ") == " (ops->apa specs t (vec body) "acc" env*)
                       "\n    IN ApaFoldSet(Step, " acc ", ChildrenIn(ns, node)))"))
                ;; the records of a collection: some number of them, each arbitrary
                (and (set-type? ct) (map? (second ct)))
                (let [et (scalars (second ct))
                      env* (assoc-in env [:vars sym] et)]
                  (str "(LET \\* @type: ($acc, Int) => $acc;\n        Step(acc, i) == LET \\* @type: " (tla-type et) ";\n          " v " == Gen(1) IN "
                       (ops->apa specs t (vec body) "acc" env*)
                       "\n    IN ApaFoldSet(Step, " acc ", {i \\in 1..MaxChildren : i <= kids}))"))
                :else (fail ":each over something that is not a collection in the schema" op)))
      :let (let [[bindings & body] args
                 env* (reduce (fn [e [sym x]] (assoc-in e [:vars sym] (type-of x env))) env bindings)]
             (str "(LET " (str/join " " (map (fn [[sym x]] (str "v_" (ident (name sym)) " == " (expr->apa x env))) bindings))
                  " IN " (ops->apa specs t (vec body) acc env*) ")"))
      :when (let [[c & body] args]
              (str "(IF " (expr->apa c env) " THEN " (ops->apa specs t (vec body) acc env) " ELSE " acc ")"))
      :if (let [[c then else] args]
            (str "(IF " (expr->apa c env) " THEN " (ops->apa specs t then acc env) " ELSE " (ops->apa specs t else acc env) ")"))
      (fail "unknown op" op))))

(def ^:dynamic ^:private *fresh* nil)

(defn- ops->apa [specs t ops acc env]
  (if (empty? ops)
    acc
    (let [names (vec (repeatedly (count ops) #(str "a" (swap! *fresh* inc))))
          steps (map vector names ops (cons acc names))]
      (str "(" (str/join " " (map (fn [[nm op prev]] (str "LET " nm " == " (op->apa specs t op prev env) " IN")) steps))
           " " (last names) ")"))))

(defn- plan-operator
  "The plan of transition `k` as an operator over the node, its data, the
  payload, the tree, the next fresh id and the number of children to create."
  [specs schemas* k t]
  (let [env {:data-type (get-in schemas* [:by-type (:node-type t)])
             :payload-type (get-in schemas* [:by-event (:on t)] {})
             :data-union (:data schemas*)
             :payload-union (:payload schemas*)
             :node-type (:node-type t)
             :counters (get-in specs [:node-types (:node-type t) :counters])
             :vars {}}]
    (str "\\* @type: (Int, $data, $payload, Int -> $node, Int, Int) => $acc;\n"
         "Plan_" k "(node, d0, payload, ns, fresh, kids) == " (ops->apa specs t (:plan t) "Acc0(d0, fresh)" env) "\n")))

;; --- the definition, specialised: literal states, guards and routes, no table ----------

(defn- cmp-op [op]
  (case (name op) "=" "=" ("!=" "not=") "/=" (name op)))

(defn- counted-name [nt cname] (str "Counted_" (ident nt) "_" (ident (name cname))))
(defn- counter-value-name [nt cname] (str "CounterValue_" (ident nt) "_" (ident (name cname))))

(defn- guard->apa
  "A constraint of a transition of node type `nt`, over `ns`, `efs` and the node `n`."
  [specs nt c]
  (let [counters (get-in specs [:node-types nt :counters])
        counter! (fn [cname] (when-not (get counters cname) (fail (str "counter " cname " is not a counter of " nt) c)) cname)
        states (fn [other]
                 (str (when (seq (:allowed c)) (str " /\\ ns[" other "].state \\in " (tla-set (map q (:allowed c)))))
                      (when (seq (:forbidden c)) (str " /\\ ns[" other "].state \\notin " (tla-set (map q (:forbidden c)))))))]
    (case (:constraint c)
      :parent-state (str "(LET other == ns[n].parent IN other /= NoId" (states "other") ")")
      :ancestor-state (str "(LET other == AncestorIn(ns, n, " (q (:node-type c)) ") IN other /= NoId" (states "other") ")")
      :counter (let [op (cmp-op (:op c)) v (long (or (:value c) 0)) cname (counter! (:counter c))]
                 (cond
                   (and (#{"=" "<="} op) (zero? v)) (str "(\\A c \\in ChildrenIn(ns, n) : ~" (counted-name nt cname) "(ns, c))")
                   (or (and (= ">" op) (zero? v)) (and (= ">=" op) (= 1 v)) (and (= "/=" op) (zero? v)))
                   (str "(\\E c \\in ChildrenIn(ns, n) : " (counted-name nt cname) "(ns, c))")
                   :else (str "(" (counter-value-name nt cname) "(ns, n) " op " " v ")")))
      :counters-cover-children (if (empty? (:counters c))
                                 "(ChildrenIn(ns, n) = {})"
                                 (str "(\\A c \\in ChildrenIn(ns, n) : " (str/join " \\/ " (map #(str (counted-name nt (counter! %)) "(ns, c)") (:counters c))) ")"))
      :no-outstanding-effects (str "NoLiveEffectsIn(ns, efs, n, " (tla-bool (= :subtree (:scope c))) ")")
      (fail "constraint not modelled" c))))

(defn- transitions
  "The transitions with their index, the first per (type, state, event) for
  the ones an event fires, every auto one."
  [specs]
  (let [seen (atom #{})]
    (vec (keep-indexed (fn [i t]
                         (let [k (inc i) key [(:node-type t) (:from t) (:on t)]]
                           (cond (:auto t) (assoc t :k k)
                                 (@seen key) nil
                                 :else (do (swap! seen conj key) (assoc t :k k)))))
                       (:transitions specs)))))

(defn- reach
  "The states of node type `nt` reachable from `state`, itself included."
  [specs nt state]
  (let [edges (group-by :from (filter #(= nt (:node-type %)) (:transitions specs)))]
    (loop [seen #{state} todo [state]]
      (if (empty? todo)
        seen
        (let [next* (remove seen (map :to (edges (first todo))))]
          (recur (into seen next*) (into (subvec todo 1) next*)))))))

(defn- after
  "The states a node of type `nt` may be in after taking one of `ts`: their
  targets and what is reachable from them."
  [specs nt ts]
  (sort (reduce into #{} (map #(reach specs nt (:to %)) ts))))

(defn- reach-avoiding
  "The states of node type `nt` reachable from `state` by transitions not on
  any of `events`, itself included."
  [specs nt state events]
  (let [edges (group-by :from (filter #(and (= nt (:node-type %)) (not (contains? events (:on %)))) (:transitions specs)))]
    (loop [seen #{state} todo [state]]
      (if (empty? todo)
        seen
        (let [next* (remove seen (map :to (edges (first todo))))]
          (recur (into seen next*) (into (subvec todo 1) next*)))))))

(defn- handler-ops
  "Every op in a plan, branches and loops included."
  [ops]
  (mapcat (fn [[kind & args :as op]]
            (case kind
              :each (cons op (handler-ops (vec (drop 2 args))))
              :let (cons op (handler-ops (vec (rest args))))
              :when (cons op (handler-ops (vec (rest args))))
              :if (cons op (handler-ops (into (vec (second args)) (nth args 2))))
              [op]))
          ops))

(defn- routed?
  "Whether an event emitted by node type `nt` is routed to an ancestor."
  [specs nt ev]
  (some #(and (= ev (:event %)) (= nt (:from-node-type %))) (:routing specs)))

(defn- self-emits
  "The events a transition may queue for the node itself."
  [specs {:keys [node-type plan emit]}]
  (if plan
    (set (keep (fn [[kind & args]]
                 (case kind
                   :emit (when-not (routed? specs node-type (first args)) (first args))
                   :emit-to (when (= :self (first args)) (second args))
                   nil))
               (handler-ops plan)))
    (set (remove #(routed? specs node-type %) emit))))

(defn- always-self-emits?
  "Whether a transition's plan queues an event for the node itself on every
  path; for a code handler, whether it declares emits and none is routed."
  [specs {:keys [node-type plan emit]}]
  (letfn [(ops? [ops]
            (some (fn [[kind & args]]
                    (case kind
                      :emit (not (routed? specs node-type (first args)))
                      :emit-to (= :self (first args))
                      :if (and (ops? (second args)) (ops? (nth args 2)))
                      :let (ops? (rest args))
                      false))
                  ops))]
    (if plan
      (boolean (ops? plan))
      (and (seq emit) (not-any? #(routed? specs node-type %) emit)))))

(defn- reach-except
  "The states of node type `nt` reachable from the states `from` by transitions
  other than those in `ts`, `from` included."
  [specs nt from ts]
  (let [ts (set (if (map? ts) [ts] ts))
        edges (group-by :from (filter #(and (= nt (:node-type %)) (not (contains? ts %))) (:transitions specs)))]
    (loop [seen (set from) todo (vec from)]
      (if (empty? todo)
        seen
        (let [next* (remove seen (map :to (edges (first todo))))]
          (recur (into seen next*) (into (subvec todo 1) next*)))))))

(defn- cover-guards
  "The counters-cover guards of a transition, each as the child type it
  counts and the states it counts; a guard over counters of several child
  types is split per type."
  [specs t]
  (for [c (:requires t) :when (= :counters-cover-children (:constraint c))
        :let [counters (get-in specs [:node-types (:node-type t) :counters])]
        [ct cs] (group-by #(:child-type (get counters %)) (:counters c))
        :when ct]
    {:child-type ct :states (set (mapcat #(:when-child-enters (get counters %)) cs))}))

(defn- closure-rule
  "The rule a set of guarded transitions of `nt` over children of `child-type`
  gives: in every parent state that, from where the children were created, is
  only reached through one of them, the children are in the counted states of
  some of them or states reachable from those. Nil when no state is dominated
  or more children may be created afterwards."
  [specs nt child-type guarded]
  (let [mine (filter #(= nt (:node-type %)) (:transitions specs))
        created-at (set (map :to (filter #(some #{child-type} (:creates %)) mine)))
        after (reduce into #{} (map #(reach specs nt (:to %)) guarded))
        dominated (clojure.set/difference after (reach-except specs nt created-at guarded))
        creates-again? (some #(and (contains? dominated (:from %)) (some #{child-type} (:creates %))) mine)
        counted (reduce into #{} (for [t guarded, g (cover-guards specs t) :when (= child-type (:child-type g))] (:states g)))]
    (when (and (seq created-at) (seq dominated) (not creates-again?))
      [nt (sort dominated) child-type (sort (reduce into #{} (map #(reach specs child-type %) counted)))])))

(defn- guard-closures
  "What the counters-cover guards guarantee afterwards, as [parent-type
  parent-states child-type child-states]: for each guarded auto transition
  alone (the precise rule), and for all the guards over a child type together
  (a state every path to which passes one of them)."
  [specs]
  (let [autos (filter :auto (:transitions specs))
        single (for [t autos, g (cover-guards specs t)] (closure-rule specs (:node-type t) (:child-type g) [t]))
        grouped (for [[[nt ct] ts] (group-by (fn [[t g]] [(:node-type t) (:child-type g)])
                                             (for [t autos, g (cover-guards specs t)] [t g]))
                      :when (> (count ts) 1)]
                  (closure-rule specs nt ct (distinct (map first ts))))]
    (distinct (remove nil? (concat single grouped)))))

(defn- fallback-rules
  "What a fallback state means, by the state it was entered from: an unguarded
  auto transition from S was taken because every cover-guarded auto declared
  before it from S failed, so for each such guard a child is outside its
  counted states, or the event that moves the last one is queued for the
  parent. Rules as [parent-type state from [[child-type counted-states] ...]],
  for states entered only by such fallbacks."
  [specs]
  (let [ts (vec (:transitions specs))
        idx (into {} (map-indexed (fn [i t] [t i]) ts))
        guards-before (fn [t] (for [g ts :when (and (:auto g) (= (:node-type g) (:node-type t)) (= (:from g) (:from t)) (< (idx g) (idx t)))
                                    c (cover-guards specs g)]
                                [(:child-type c) (sort (:states c))]))
        fallback? (fn [t] (and (:auto t) (empty? (:requires t)) (seq (guards-before t))))]
    (for [[[nt st] into] (group-by (juxt :node-type :to) (remove #(= (:from %) (:to %)) ts))
          :when (every? fallback? into)
          t into]
      [nt st (:from t) (distinct (guards-before t))])))

(defn- tables [specs]
  (let [nts (sort-by key (:node-types specs))
        case-of (fn [arms other] (if (empty? arms) other (str "CASE " (str/join "\n    [] " arms) "\n    [] OTHER -> " other)))
        routes (vals (reduce (fn [m r] (let [key [(:event r) (:from-node-type r)]] (if (contains? m key) m (assoc m key r)))) {} (:routing specs)))
        routes (sort-by (juxt :event :from-node-type) routes)]
    (str
     "Types == " (tla-set (map (comp q key) nts)) "\n"
     "Events == " (tla-set (map q (sort (:events specs)))) "\n"
     "\\* @type: (Str) => Str;\n"
     "InitialOf(t) == " (case-of (for [[nt s] nts] (str "t = " (q nt) " -> " (q (:initial-state s)))) "\"\"") "\n"
     "\\* @type: (Str, Str) => Bool;\n"
     "IsTerminal(t, s) == " (case-of (for [[nt s] nts] (str "t = " (q nt) " -> s \\in " (tla-set (map q (:terminal-states s))))) "FALSE") "\n"
     "\\* @type: (Str, Str) => Bool;\n"
     "IsState(t, s) == " (case-of (for [[nt s] nts] (str "t = " (q nt) " -> s \\in " (tla-set (map q (:states s))))) "FALSE") "\n"
     (str/join (for [[nt {:keys [counters]}] nts, [cname {:keys [child-type when-child-enters]}] (sort-by key counters)]
                 (str "\\* @type: (Int -> $node, Int) => Bool;\n"
                      (counted-name nt cname) "(ns, c) == ns[c].type = " (q child-type) " /\\ ns[c].state \\in " (tla-set (map q when-child-enters)) "\n"
                      "\\* @type: (Int -> $node, Int) => Int;\n"
                      (counter-value-name nt cname) "(ns, n) == Cardinality({c \\in ChildrenIn(ns, n) : " (counted-name nt cname) "(ns, c)})\n")))
     "\\* @type: (Int -> $node, Int, Str, Str) => Bool;\n"
     "ParentAllows(ns, parent, childType, childState) ==\n  parent = NoId"
     (str/join (for [[nt {:keys [child-states-allowed]}] nts, [ps by-child] (sort-by key child-states-allowed), [ct states] (sort-by key by-child)]
                 (str "\n  \\/ (ns[parent].type = " (q nt) " /\\ ns[parent].state = " (q ps) " /\\ childType = " (q ct) " /\\ childState \\in " (tla-set (map q states)) ")")))
     "\n  \\/ ~(" (if (empty? (for [[_ s] nts, [_ by-child] (:child-states-allowed s), [_ _] by-child] 1)) "FALSE"
                 (str/join " \\/ " (for [[nt {:keys [child-states-allowed]}] nts, [ps by-child] (sort-by key child-states-allowed), [ct _] (sort-by key by-child)]
                                     (str "(ns[parent].type = " (q nt) " /\\ ns[parent].state = " (q ps) " /\\ childType = " (q ct) ")")))) ")\n"
     "\\* @type: (Str, Str, Str) => Bool;\n"
     "Ignores(nt, st, ev) == " (let [xs (for [[nt {:keys [ignores]}] nts, [s evs] (sort-by key ignores), e evs] (str "(nt = " (q nt) " /\\ st = " (q s) " /\\ ev = " (q e) ")"))]
                                 (if (empty? xs) "FALSE" (str/join " \\/ " xs))) "\n"
     "\\* @type: (Str, Str) => Bool;\n"
     "RouteIgnorable(ev, fromType) == " (let [xs (for [r routes :when (= :ignore (:on-unsatisfied r))] (str "(ev = " (q (:event r)) " /\\ fromType = " (q (:from-node-type r)) ")"))]
                                          (if (empty? xs) "FALSE" (str/join " \\/ " xs))) "\n"
     "\\* @type: (Int -> $node, Int, Str) => Int;\n"
     "EmitTarget(ns, node, ev) == "
     (case-of (for [r routes]
                (str "(ev = " (q (:event r)) " /\\ ns[node].type = " (q (:from-node-type r)) ") -> (LET a == AncestorIn(ns, node, " (q (:to-node-type r))
                     ") IN IF a /= NoId THEN a ELSE " (if (= :ignore (:on-unsatisfied r)) "Dropped" "Unrouted") ")"))
              "node") "\n"
     "EffectSpecs == " (tla-set (map (comp q key) (sort-by key (:effects specs)))) "\n"
     ;; what the runtime guarantees of a state, by node type: for the well-formedness the induction starts from
     (let [by-type (group-by :node-type (:transitions specs))
           set-of (fn [op f] (str "\\* @type: (Str) => Set(Str);\n" op "(t) == "
                                  (case-of (for [[nt _] nts] (str "t = " (q nt) " -> " (tla-set (map q (sort (set (f nt))))))) "{}") "\n"))]
       (str (set-of "EventsOf" (fn [nt] (concat (map :on (by-type nt)) (mapcat val (get-in specs [:node-types nt :ignores])))))
            (set-of "AutoEventsOf" (fn [nt] (map :on (filter :auto (by-type nt)))))
            (set-of "EffectsOf" (fn [nt] (mapcat :effects (by-type nt))))
            (set-of "EmitsOf" (fn [nt] (mapcat #(concat (:emit %) (:commands %)) (by-type nt))))
            "AutoEvents == " (tla-set (map q (sort (set (map :on (filter :auto (:transitions specs))))))) "\n"
            ;; where a node is after creating a child, emitting an event, or requesting an effect:
            ;; the transition's target state or one reachable from it
            "\\* @type: (Str, Str) => Set(Str);\n"
            "AfterCreating(pt, ct) == "
            (case-of (for [[nt ts] (sort-by key by-type), ct (sort (set (mapcat :creates ts)))]
                       (str "pt = " (q nt) " /\\ ct = " (q ct) " -> " (tla-set (map q (after specs nt (filter #(some #{ct} (:creates %)) ts))))))
                     "{}") "\n"
            "\\* @type: (Str, Str) => Set(Str);\n"
            "AfterEmitting(t, ev) == "
            (case-of (for [[nt ts] (sort-by key by-type), ev (sort (set (mapcat #(concat (:emit %) (:commands %)) ts)))]
                       (str "t = " (q nt) " /\\ ev = " (q ev) " -> " (tla-set (map q (after specs nt (filter #(some #{ev} (concat (:emit %) (:commands %))) ts))))))
                     "{}") "\n"
            "\\* @type: (Str, Str) => Set(Str);\n"
            "AfterEffect(t, spec) == "
            (case-of (for [[nt ts] (sort-by key by-type), spec (sort (set (mapcat :effects ts)))]
                       (str "t = " (q nt) " /\\ spec = " (q spec) " -> " (tla-set (map q (after specs nt (filter #(some #{spec} (:effects %)) ts))))))
                     "{}") "\n"
            ;; the destination of a node type on an event in a state, "" for none; the auto
            ;; destinations from a state; whether the transition has guards (it may refuse)
            "\\* @type: (Str, Str, Str) => Str;\n"
            "Dst(t, s, ev) == "
            (case-of (for [{:keys [node-type from on to]} (remove :auto (transitions specs))]
                       (str "t = " (q node-type) " /\\ s = " (q from) " /\\ ev = " (q on) " -> " (q to)))
                     "\"\"") "\n"
            "\\* @type: (Str, Str) => Set(Str);\n"
            "AutoEventsFrom(t, s) == "
            (case-of (for [[[nt from] ts] (sort-by key (group-by (juxt :node-type :from) (filter :auto (:transitions specs))))]
                       (str "t = " (q nt) " /\\ s = " (q from) " -> " (tla-set (map q (sort (set (map :on ts)))))))
                     "{}") "\n"
            "\\* @type: (Str, Str) => Set(Str);\n"
            "AutoDstsOf(t, ev) == "
            (case-of (for [[[nt on] ts] (sort-by key (group-by (juxt :node-type :on) (filter :auto (:transitions specs))))]
                       (str "t = " (q nt) " /\\ ev = " (q on) " -> " (tla-set (map q (sort (set (map :to ts)))))))
                     "{}") "\n"
            "\\* @type: (Str, Str, Str) => Bool;\n"
            "Guarded(t, s, ev) == "
            (let [xs (for [{:keys [node-type from on requires]} (remove :auto (transitions specs)) :when (seq requires)]
                       (str "(t = " (q node-type) " /\\ s = " (q from) " /\\ ev = " (q on) ")"))]
              (if (empty? xs) "FALSE" (str/join " \\/ " xs))) "\n"
            ;; where the owner of a live effect can be: the requesting transition's target state, or
            ;; one reached from it without the effect's completion, failure or timeout event
            "\\* @type: (Str, Str) => Set(Str);\n"
            "LiveOwnerStates(t, spec) == "
            (case-of (for [[nt ts] (sort-by key by-type), spec (sort (set (mapcat :effects ts)))
                           :let [final (set (keep (get-in specs [:effects spec]) [:completion-event :failure-event :timeout-event]))
                                 states (remove (set (get-in specs [:node-types nt :terminal-states]))
                                                (reduce into #{} (map #(reach-avoiding specs nt (:to %) final) (filter #(some #{spec} (:effects %)) ts))))]]
                       (str "t = " (q nt) " /\\ spec = " (q spec) " -> " (tla-set (map q (sort states)))))
                     "{}") "\n"
            ;; states every transition into which queues an event for the node itself: transient, never
            ;; idle; the events those transitions queue, one of which is pending while the node is there
            "\\* @type: (Str, Str) => Set(Str);\n"
            "SelfEmitEvents(t, s) == "
            (case-of (for [[nt spec] nts, st (sort (:states spec))
                           :when (not= st (:initial-state spec))
                           :let [incoming (filter #(and (= nt (:node-type %)) (= st (:to %))) (:transitions specs))]
                           :when (and (seq incoming) (every? #(always-self-emits? specs %) incoming))]
                       (str "t = " (q nt) " /\\ s = " (q st) " -> " (tla-set (map q (sort (reduce into #{} (map #(self-emits specs %) incoming)))))))
                     "{}") "\n"
            ;; where a node is after consuming an event: the targets of its transitions on it and
            ;; what is reachable from them, or the states that ignore it and what follows
            "\\* @type: (Str, Str) => Set(Str);\n"
            "AfterConsumed(t, ev) == "
            (case-of (for [[nt ts] (sort-by key by-type)
                           ev (sort (set (concat (map :on ts) (mapcat val (get-in specs [:node-types nt :ignores])))))
                           :let [taken (map :to (filter #(= ev (:on %)) ts))
                                 ignoring (for [[st evs] (get-in specs [:node-types nt :ignores]) :when (some #{ev} evs)] st)
                                 states (reduce into #{} (map #(reach specs nt %) (concat taken ignoring)))]]
                       (str "t = " (q nt) " /\\ ev = " (q ev) " -> " (tla-set (map q (sort states)))))
                     "{}") "\n"
            ;; a self-emitted event whose every cause is an effect's outcome: the outcome events that produce it
            "\\* @type: (Str, Str) => Set(Str);\n"
            "SelfEmitCauses(t, ev) == "
            (case-of (for [[nt ts] (sort-by key by-type)
                           :let [outcomes (set (for [[_ e] (:effects specs), k [:completion-event :failure-event :timeout-event] :when (k e)] (k e)))
                                 emitted (set (mapcat #(self-emits specs %) ts))]
                           ev (sort emitted)
                           :let [causes (set (map :on (filter #(contains? (self-emits specs %) ev) ts)))]
                           :when (and (seq causes) (every? outcomes causes))]
                       (str "t = " (q nt) " /\\ ev = " (q ev) " -> " (tla-set (map q (sort causes)))))
                     "{}") "\n"
            ;; the states a node can have come from: the sources of the transitions into its state
            "\\* @type: (Str, Str) => Set(Str);\n"
            "PrevOf(t, s) == "
            (case-of (for [[[nt st] ts] (sort-by key (group-by (juxt :node-type :to) (:transitions specs)))]
                       (str "t = " (q nt) " /\\ s = " (q st) " -> " (tla-set (map q (sort (set (map :from ts)))))))
                     "{}") "\n"
            ;; a command sent only when creating the child: the first event the child sees
            "\\* @type: (Str) => Bool;\n"
            "HasCreationCommand(pt) == "
            (let [xs (for [[nt ts] (sort-by key by-type)
                           :when (seq (remove (set (mapcat :commands (remove #(seq (:creates %)) ts)))
                                              (set (mapcat :commands (filter #(seq (:creates %)) ts)))))]
                       (str "pt = " (q nt)))]
              (if (empty? xs) "FALSE" (str/join " \\/ " xs))) "\n"
            "\\* @type: (Str, Str) => Bool;\n"
            "CreationCommand(pt, ev) == "
            (let [xs (for [[nt ts] (sort-by key by-type)
                           :let [creating (set (mapcat :commands (filter #(seq (:creates %)) ts)))
                                 other (set (mapcat :commands (remove #(seq (:creates %)) ts)))]
                           ev (sort (remove other creating))]
                       (str "(pt = " (q nt) " /\\ ev = " (q ev) ")"))]
              (if (empty? xs) "FALSE" (str/join " \\/ " xs))) "\n"
            "\\* @type: (Str, Str) => Bool;\n"
            "MayCreate(parentType, childType) == "
            (let [xs (for [[nt ts] (sort-by key by-type), ct (sort (set (mapcat :creates ts)))] (str "(parentType = " (q nt) " /\\ childType = " (q ct) ")"))]
              (if (empty? xs) "FALSE" (str/join " \\/ " xs))) "\n"))
     (str/join (for [[op field] [["Dispatch" :dispatch-event] ["Completion" :completion-event] ["Failure" :failure-event]]]
                 (str "\\* @type: (Str) => Str;\n"
                      op "(spec) == " (case-of (for [[n e] (sort-by key (:effects specs)) :when (field e)] (str "spec = " (q n) " -> " (q (field e)))) "\"\"") "\n"))))))

(declare runtime-steps)

(defn- transition-operators
  "Per transition: its guards, its match, its plan, and the action that fires
  it; the auto decision over the auto transitions in order."
  [specs schemas*]
  (binding [*fresh* (atom 0)]
    (let [ts (transitions specs)
          autos (filter :auto ts)
          fired (remove :auto ts)
          terminal? (fn [t] (contains? (set (get-in specs [:node-types (:node-type t) :terminal-states])) (:to t)))
          body (fn [{:keys [k to node-type plan emit effects creates commands timeout] :as t}]
                 (let [kids? (seq creates)
                       commit (fn [counted r] (str "Commit(i, " k ", " (q to) ", " (tla-bool (terminal? t)) ", " counted ", " r ", " (q node-type) ")"))
                       with-kids (fn [inner] (if kids? (str "\\E kids \\in 0..MaxChildren : " inner) inner))]
                   (if plan
                     (with-kids (commit "FALSE" (str "Plan_" k "(id, n.data, q.payload, nodes, nextId, " (if kids? "kids" "0") ")")))
                     (let [abstract (fn [ev] (str "Abstract(id, nodes, " ev ", nextId, " (if kids? "kids" "0") ", " (tla-seq (map q creates)) ", "
                                                  (tla-seq (map q commands)) ", " (tla-seq (map q effects)) ", " (q (or (:event timeout) "")) ")"))]
                       (if (empty? emit)
                         (with-kids (commit "TRUE" (abstract "\"\"")))
                         (str "\\E ev \\in (IF Fired(id, " k ") >= MaxRepeat THEN {" (q (last emit)) "} ELSE " (tla-set (map q emit)) ") : "
                              (with-kids (commit "TRUE" (abstract "ev")))))))))]
      (str
       (str/join "\n"
                 (for [{:keys [k node-type from on to auto plan requires] :as t} ts]
                   (str "\\* " k ": " node-type "." from " -on " on "-> " to (when auto " (auto)") "\n"
                        "\\* @type: (Int -> $node, Seq($effect), Int) => Bool;\n"
                        "Guards_" k "(ns, efs, n) == " (if (empty? requires) "TRUE" (str/join " /\\ " (map #(guard->apa specs node-type %) requires))) "\n"
                        (when-not auto
                          (str "\\* @type: (Int -> $node, Int, Str) => Bool;\n"
                               "Match_" k "(ns, id, ev) == ns[id].type = " (q node-type) " /\\ ns[id].state = " (q from) " /\\ ev = " (q on) "\n"))
                        (when plan (plan-operator specs schemas* k t)))))
       "\n\\* The auto transition a node takes now: the first whose guards hold, 0 for none.\n"
       "\\* @type: (Int -> $node, Seq($effect), Int) => Int;\n"
       "DecideAuto(ns, efs, n) ==\n"
       (str/join (for [{:keys [k node-type from]} autos]
                   (str "  IF ns[n].type = " (q node-type) " /\\ ns[n].state = " (q from) " /\\ Guards_" k "(ns, efs, n) THEN " k " ELSE\n")))
       "  0\n"
       "\\* @type: (Int) => Str;\n"
       "AutoEvent(k) == " (if (empty? autos) "\"\"" (str "CASE " (str/join " [] " (map #(str "k = " (:k %) " -> " (q (:on %))) autos)) " [] OTHER -> \"\"")) "\n"
       "\\* @type: (Int -> $node, Int, Str) => Bool;\n"
       "Matches(ns, id, ev) == " (if (empty? fired) "FALSE" (str/join " \\/ " (map #(str "Match_" (:k %) "(ns, id, ev)") fired))) "\n"
       "\\* @type: (Int -> $node, Seq($effect), Int, Str) => Bool;\n"
       "Refused(ns, efs, id, ev) == " (if (empty? fired) "FALSE" (str/join " \\/ " (map #(str "(Match_" (:k %) "(ns, id, ev) /\\ ~Guards_" (:k %) "(ns, efs, id))") fired))) "\n"
       runtime-steps
       "\n\\* --- the transitions, one action each ---\n"
       (str/join (for [{:keys [k auto] :as t} ts]
                   (str "\\* @type: (Int) => Bool;\n"
                        "Fire_" k "(i) == LET q == queue[i] id == q.target n == nodes[id] IN\n"
                        (if auto
                          (str "  /\\ q.auto /\\ DecideAuto(nodes, effects, id) = " k "\n")
                          (str "  /\\ ~q.auto /\\ Match_" k "(nodes, id, q.evt) /\\ Guards_" k "(nodes, effects, id)\n"))
                        "  /\\ " (body t) "\n")))
       "\nNext ==\n  /\\ ~bad\n  /\\ \\/ \\E i \\in DOMAIN queue : Eligible(i) /\\ Drop(i)\n     \\/ \\E i \\in DOMAIN queue : Eligible(i) /\\ Refuse(i)\n"
       (str/join (for [{:keys [k]} ts] (str "     \\/ \\E i \\in DOMAIN queue : Eligible(i) /\\ Fire_" k "(i)\n")))
       "     \\/ \\E i \\in DOMAIN effects : EffectMove(i)\n"
       ;; what the runtime guarantees about progress: a node in a state entered only by requesting
       ;; an effect still owns it, live or with its outcome queued; a node whose auto transition is
       ;; enabled has something queued, since only processing an event re-evaluates it
       "\\* A node whose auto transition is enabled has an event or an auto request queued.\n"
       "PokedWhenEnabled == \\A n \\in DOMAIN nodes : DecideAuto(nodes, effects, n) /= 0 => \\E j \\in DOMAIN queue : queue[j].target = n\n"
       "\\* What the cover guards of the auto transitions guarantee afterwards, where the guard dominates.\n"
       "GuardClosures ==\n"
       (let [rules (for [[nt pstates ct cstates] (guard-closures specs)]
                     (str "  /\\ \\A n \\in DOMAIN nodes : (nodes[n].type = " (q nt) " /\\ nodes[n].state \\in " (tla-set (map q pstates)) ") =>\n"
                          "       \\A c \\in ChildrenIn(nodes, n) : nodes[c].type = " (q ct) " => nodes[c].state \\in " (tla-set (map q cstates))))]
         (if (empty? rules) "  TRUE\n" (str (str/join "\n" rules) "\n")))
       "\\* What a fallback state means: a child the cover guard did not count, or the event that moves it queued.\n"
       "FallbackStates ==\n"
       (let [rules (for [[nt st from guards] (fallback-rules specs)]
                     (str "  /\\ \\A n \\in DOMAIN nodes : (nodes[n].type = " (q nt) " /\\ nodes[n].state = " (q st) " /\\ nodes[n].prev = " (q from) ") =>\n"
                          "       \\/ \\E j \\in DOMAIN queue : queue[j].target = n\n"
                          "       \\/ " (str/join "\n          /\\ " (for [[ct cstates] guards]
                                                                   (str "(\\E c \\in ChildrenIn(nodes, n) : nodes[c].type = " (q ct) " /\\ nodes[c].state \\notin " (tla-set (map q cstates)) ")")))))]
         (if (empty? rules) "  TRUE\n" (str (str/join "\n" rules) "\n")))
       "\\* Once a transition that commands the children of a type has fired (its target visited), each child of\n"
       "\\* that type has the command queued or has consumed it, unless more such children may be created later.\n"
       "CommandedChildren ==\n"
       (let [mine (fn [nt] (filter #(= nt (:node-type %)) (:transitions specs)))
             rules (for [t (:transitions specs)
                         :let [nt (:node-type t)]
                         ev (distinct (:commands t))
                         ct (sort (set (for [c (:transitions specs) :when (and (= ev (:on c)) (some #(some #{(:node-type c)} (:creates %)) (mine nt)))] (:node-type c))))
                         ;; a child of the type created later without this command would not have it
                         :let [creates-again? (some #(and (not= % t) (contains? (reach specs nt (:to t)) (:from %))
                                                          (some #{ct} (:creates %)) (not (some #{ev} (:commands %))))
                                                    (mine nt))]
                         :when (not creates-again?)]
                     (str "  /\\ \\A n \\in DOMAIN nodes : (nodes[n].type = " (q nt) " /\\ " (q (:to t)) " \\in nodes[n].visited) =>\n"
                          "       \\A c \\in ChildrenIn(nodes, n) : nodes[c].type = " (q ct) " =>\n"
                          "         (\\E j \\in DOMAIN queue : queue[j].target = c /\\ queue[j].evt = " (q ev) ") \\/ nodes[c].state \\in AfterConsumed(" (q ct) ", " (q ev) ")"))]
         (if (empty? rules) "  TRUE\n" (str (str/join "\n" (distinct rules)) "\n")))
       "\\* A state every entry into which creates a child of a type: such a child is there, not yet done,\n"
       "\\* or the event by which it finished is still queued for the parent.\n"
       "CreatedChildrenPresent ==\n"
       (let [rules (for [[[nt st] incoming] (sort-by key (group-by (juxt :node-type :to) (remove #(= (:from %) (:to %)) (:transitions specs))))
                         :let [common (reduce clojure.set/intersection (map (comp set :creates) incoming))]
                         :when (and (seq incoming) (seq common))
                         ct (sort common)]
                     (str "  /\\ \\A n \\in DOMAIN nodes : (nodes[n].type = " (q nt) " /\\ nodes[n].state = " (q st) ") =>\n"
                          "       \\E c \\in ChildrenIn(nodes, n) : nodes[c].type = " (q ct)
                          " /\\ (~IsTerminal(nodes[c].type, nodes[c].state) \\/ \\E j \\in DOMAIN queue : queue[j].target = n /\\ queue[j].source = c)"))]
         (if (empty? rules) "  TRUE\n" (str (str/join "\n" rules) "\n")))
       "\\* The inductive form of NotDead: a node that is not done has an event queued or a live effect,\n"
       "\\* or an ancestor or a descendant has, since a node only ever waits on its line.\n"
       "\\* @type: (Int) => Bool;\n"
       "Moving(a) == (\\E j \\in DOMAIN queue : queue[j].target = a) \\/ (\\E e \\in DOMAIN effects : effects[e].owner = a /\\ effects[e].status \\in Live)\n"
       "NoDeadNode == \\A n \\in DOMAIN nodes : ~IsTerminal(nodes[n].type, nodes[n].state) =>\n"
       "  \\E a \\in DOMAIN nodes : (a = n \\/ DescendsIn(nodes, n, a) \\/ DescendsIn(nodes, a, n)) /\\ Moving(a)\n"))))

;; --- the module -------------------------------------------------------------------------

(defn- aliases [{:keys [data payload]}]
  (str "\\* @typeAlias: data = " (tla-type data) ";\n"
       "\\* @typeAlias: payload = " (tla-type payload) ";\n"
       "\\* @typeAlias: node = { type: Str, state: Str, prev: Str, visited: Set(Str), parent: Int, data: $data };\n"
       "\\* @typeAlias: queued = { target: Int, evt: Str, eff: Int, auto: Bool, source: Int, sourceType: Str, payload: $payload };\n"
       "\\* @typeAlias: effect = { owner: Int, spec: Str, status: Str, timeout: Str };\n"
       "\\* @typeAlias: peffect = { spec: Str, timeout: Str };\n"
       "\\* @typeAlias: child = { id: Int, type: Str };\n"
       "\\* @typeAlias: acc = { data: $data, children: Set($child), events: Seq($queued), effects: Seq($peffect), next: Int };\n"
       "Aliases == TRUE\n"))

(defn- runtime-defaults [{:keys [data payload]}]
  (str "
NoId == -1
Dropped == -2
Unrouted == -3
Live == {\"CREATED\", \"DISPATCHED\"}
\\* @type: () => $data;
DefaultData == " (default-of data) "
\\* @type: () => $payload;
DefaultPayload == " (default-of payload) "
\\* @type: ($data, Int) => $acc;
Acc0(d0, fresh) == [data |-> d0, children |-> {}, events |-> <<>>, effects |-> <<>>, next |-> fresh]
\\* @type: (Seq(a), Int) => Seq(a);
Remove(s, i) == SubSeq(s, 1, i - 1) \\o SubSeq(s, i + 1, Len(s))
"))

(defn- unrolled [depth]
  (letfn [(anc [k] (if (zero? k)
                     "NoId"
                     (str "(LET p" k " == ns[" (if (= k depth) "n" (str "p" (inc k))) "].parent IN IF p" k " = NoId THEN NoId ELSE IF ns[p" k "].type = t THEN p" k " ELSE " (anc (dec k)) ")")))
          (desc [k] (if (zero? k)
                      "FALSE"
                      (str "(LET p" k " == ns[" (if (= k depth) "n" (str "p" (inc k))) "].parent IN IF p" k " = NoId THEN FALSE ELSE IF p" k " = a THEN TRUE ELSE " (desc (dec k)) ")")))]
    (str "\\* @type: (Int -> $node, Int, Str) => Int;\n"
         "AncestorIn(ns, n, t) == " (anc depth) "\n"
         "\\* @type: (Int -> $node, Int, Int) => Bool;\n"
         "DescendsIn(ns, n, a) == " (desc depth) "\n")))

(defn- tree [depth]
  (str "
\\* --- the tree ---
\\* @type: (Int -> $node, Int) => Set(Int);
ChildrenIn(ns, n) == {c \\in DOMAIN ns : ns[c].parent = n}
" (unrolled depth) "
\\* @type: (Int -> $node, Seq($effect), Int, Bool) => Bool;
NoLiveEffectsIn(ns, efs, n, subtree) ==
  \\A j \\in DOMAIN efs : efs[j].status \\in Live => ~(efs[j].owner = n \\/ (subtree /\\ DescendsIn(ns, efs[j].owner, n)))
"))

(def ^:private runtime-abstract "
\\* --- the handler is code: everything the definition says it may do (one emit per step) ---
\\* @type: ($acc, Seq(Str), Int) => $acc;
Spawn(acc0, spawns, kids) ==
  LET \\* @type: ($acc, Str) => $acc;
      One(a, tp) == LET \\* @type: ($acc, Int) => $acc;
                        Add(a2, k) == [a2 EXCEPT !.children = @ \\cup {[id |-> a2.next, type |-> tp]}, !.next = @ + 1]
                    IN ApaFoldSet(Add, a, {k \\in 1..MaxChildren : k <= kids})
  IN ApaFoldSeqLeft(One, acc0, spawns)
\\* @type: (Int, Int -> $node, Str, Int, Int, Seq(Str), Seq(Str), Seq(Str), Str) => $acc;
Abstract(node, ns, ev, fresh, kids, spawns, cmds, effs, tmo) ==
  LET a1 == Spawn(Acc0(ns[node].data, fresh), spawns, kids)
      targets == IF a1.children /= {} THEN { c.id : c \\in a1.children } ELSE ChildrenIn(ns, node)
      \\* @type: (Seq($queued), Str) => Seq($queued);
      Cmd(acc, c) == LET \\* @type: (Seq($queued), Int) => Seq($queued);
                         To(acc2, tg) == Append(acc2, [target |-> tg, evt |-> c, eff |-> NoId, auto |-> FALSE, source |-> node, sourceType |-> ns[node].type, payload |-> DefaultPayload])
                     IN ApaFoldSet(To, acc, targets)
      \\* @type: (Seq($peffect), Str) => Seq($peffect);
      Eff(acc, e) == Append(acc, [spec |-> e, timeout |-> tmo])
      a2 == [a1 EXCEPT !.events = ApaFoldSeqLeft(Cmd, <<>>, cmds), !.effects = ApaFoldSeqLeft(Eff, <<>>, effs)]
  IN IF ev = \"\" THEN a2
     ELSE [a2 EXCEPT !.events = Append(@, [target |-> EmitTarget(ns, node, ev), evt |-> ev, eff |-> NoId, auto |-> FALSE, source |-> node,
                                             sourceType |-> ns[node].type, payload |-> DefaultPayload])]
")

(def ^:private runtime-steps "
\\* --- the state ---
VARIABLES
  \\* @type: Int -> $node;
  nodes,
  \\* @type: Seq($queued);
  queue,
  \\* @type: Seq($effect);
  effects,
  \\* @type: Bool;
  bad,
  \\* @type: <<Int, Int>> -> Int;
  fired,
  \\* @type: Int;
  nextId
vars == <<nodes, queue, effects, bad, fired, nextId>>

Quiescent == queue = <<>> /\\ \\A j \\in DOMAIN effects : effects[j].status \\notin Live
\\* @type: (Int) => Bool;
Eligible(i) == \\A j \\in DOMAIN queue : j < i => queue[j].target /= queue[i].target

\\* Auto requests for the nodes in `ids`, one pending per node at most.
\\* @type: (Int -> $node, Seq($effect), Seq($queued), Set(Int)) => Seq($queued);
Pokes(ns, efs, q0, ids) ==
  LET \\* @type: (Seq($queued), Int) => Seq($queued);
      Poke(q, n) == LET k == DecideAuto(ns, efs, n)
                        pending == \\E j \\in DOMAIN q : q[j].target = n /\\ q[j].auto
                    IN IF k = 0 \\/ pending THEN q
                       ELSE Append(q, [target |-> n, evt |-> AutoEvent(k), eff |-> NoId, auto |-> TRUE, source |-> n, sourceType |-> ns[n].type, payload |-> DefaultPayload])
  IN ApaFoldSet(Poke, q0, ids)

\\* @type: (Int, Int) => Int;
Fired(id, k) == LET \\* @type: <<Int, Int>>;
                    key == <<id, k>>
                IN IF key \\in DOMAIN fired THEN fired[key] ELSE 0

\\* The step a transition makes: the node moves to `dst` with the plan's result `r`,
\\* its children are created, its effects cancelled when `dst` is terminal and the new
\\* ones created, the events queued, and the auto requests added.
\\* @type: (Int, Int, Str, Bool, Bool, $acc, Str) => Bool;
Commit(i, k, dst, terminal, counted, r, ntype) ==
  LET q == queue[i]
      id == q.target
      n == nodes[id]
      rest == Remove(queue, i)
      nodes1 == [nodes EXCEPT ![id] = [@ EXCEPT !.state = dst, !.prev = n.state, !.visited = @ \\cup {dst}, !.data = r.data]]
      \\* @type: (Int -> $node, $child) => Int -> $node;
      AddKid(ns, c) == ns @@ (c.id :> [type |-> c.type, state |-> InitialOf(c.type), prev |-> \"\", visited |-> {InitialOf(c.type)}, parent |-> id, data |-> DefaultData])
      nodes2 == ApaFoldSet(AddKid, nodes1, r.children)
      \\* @type: ($effect) => $effect;
      Cancel(e) == IF e.owner = id /\\ e.status \\in Live THEN [e EXCEPT !.status = \"CANCELLED\"] ELSE e
      \\* @type: (Seq($effect), $effect) => Seq($effect);
      Cancelled(acc, e) == Append(acc, Cancel(e))
      effects1 == IF terminal THEN ApaFoldSeqLeft(Cancelled, <<>>, effects) ELSE effects
      \\* @type: (Seq($effect), $peffect) => Seq($effect);
      Created(acc, e) == Append(acc, [owner |-> id, spec |-> e.spec, status |-> \"CREATED\", timeout |-> e.timeout])
      effects2 == ApaFoldSeqLeft(Created, effects1, r.effects)
      \\* @type: ($queued) => Bool;
      Kept(e) == e.target /= Dropped
      kept == SelectSeq(r.events, Kept)
      unknownTarget == \\E j \\in DOMAIN kept : kept[j].target = Unrouted \\/ kept[j].target \\notin DOMAIN nodes2
      \\* @type: <<Int, Int>>;
      key == <<id, k>>
  IN /\\ nodes' = nodes2
     /\\ queue' = Pokes(nodes2, effects2, rest \\o kept, {id} \\cup { c.id : c \\in r.children })
     /\\ effects' = effects2
     /\\ nextId' = r.next
     /\\ fired' = (IF counted THEN [x \\in (DOMAIN fired) \\cup {key} |-> IF x = key THEN Fired(id, k) + 1 ELSE fired[x]] ELSE fired)
     /\\ bad' = (bad \\/ ~ParentAllows(nodes, n.parent, ntype, dst) \\/ unknownTarget)

\\* An event no transition takes: dropped, and a rejection unless it may be ignored.
\\* @type: (Int) => Bool;
Drop(i) == LET q == queue[i] id == q.target n == nodes[id] IN
  /\\ (IF q.auto THEN DecideAuto(nodes, effects, id) = 0 ELSE ~Matches(nodes, id, q.evt))
  /\\ queue' = Remove(queue, i)
  /\\ bad' = (bad \\/ ~(q.auto \\/ Ignores(n.type, n.state, q.evt) \\/ RouteIgnorable(q.evt, q.sourceType)))
  /\\ UNCHANGED <<nodes, effects, fired, nextId>>
\\* An event whose transition's guards do not hold: refused.
\\* @type: (Int) => Bool;
Refuse(i) == LET q == queue[i] id == q.target IN
  /\\ ~q.auto /\\ Refused(nodes, effects, id, q.evt)
  /\\ queue' = Remove(queue, i)
  /\\ bad' = (bad \\/ ~RouteIgnorable(q.evt, q.sourceType))
  /\\ UNCHANGED <<nodes, effects, fired, nextId>>

\\* --- effects: what a dispatcher, a rail or a callback would do ---
\\* @type: (Int, Str, Str) => Bool;
Outcome(i, status, ev) ==
  /\\ effects' = [effects EXCEPT ![i].status = status]
  /\\ queue' = (IF ev = \"\" THEN queue
               ELSE Append(queue, [target |-> effects[i].owner, evt |-> ev, eff |-> i, auto |-> FALSE,
                                   source |-> NoId, sourceType |-> \"\", payload |-> DefaultPayload]))
  /\\ UNCHANGED <<nodes, bad, fired, nextId>>
\\* The event an effect's outcome queued, by its status now (a dispatch event may be queued
\\* while the effect has since completed).
\\* @type: ($effect, Str) => Bool;
EffectEvent(e, evt) ==
  \\/ e.status /= \"CREATED\" /\\ Dispatch(e.spec) /= \"\" /\\ Dispatch(e.spec) = evt
  \\/ e.status = \"COMPLETED\" /\\ Completion(e.spec) /= \"\" /\\ Completion(e.spec) = evt
  \\/ e.status = \"FAILED\" /\\ Failure(e.spec) /= \"\" /\\ Failure(e.spec) = evt
  \\/ e.status = \"LOST\" /\\ e.timeout /= \"\" /\\ e.timeout = evt
\\* An effect still to be heard from: live, or final with its outcome queued for its owner.
\\* @type: (Int) => Bool;
Pending(e) == effects[e].status \\in Live \\/ \\E j \\in DOMAIN queue : queue[j].eff = e
\\* @type: (Int) => Bool;
EffectMove(i) ==
  LET e == effects[i] d == Dispatch(e.spec) f == Failure(e.spec) IN
  \\/ e.status = \"CREATED\" /\\ d /= \"\" /\\ Outcome(i, \"DISPATCHED\", d)
  \\/ /\\ e.status \\in Live /\\ ~(e.status = \"CREATED\" /\\ d /= \"\")
     /\\ \\/ Outcome(i, \"COMPLETED\", Completion(e.spec))
        \\/ f /= \"\" /\\ Outcome(i, \"FAILED\", f)
        \\/ AllowDrop /\\ Outcome(i, \"LOST\", e.timeout)

Init ==
  /\\ nodes = [x \\in {0} |-> [type |-> RootType, state |-> InitialOf(RootType), prev |-> \"\", visited |-> {InitialOf(RootType)}, parent |-> NoId, data |-> DefaultData]]
  /\\ queue = << [target |-> 0, evt |-> FirstEvent, eff |-> NoId, auto |-> FALSE, source |-> NoId, sourceType |-> \"\", payload |-> FirstPayload] >>
  /\\ effects = <<>>
  /\\ bad = FALSE
  /\\ fired = [x \\in {} |-> 0]
  /\\ nextId = 1
")

(defn- prop->apa
  "A provable business invariant (docs/design/invariants.md), compiled and
  inlined, over the typed state: a node variable is a node id, an effect
  variable an index into `effects`. `env` binds variables to the node type
  their collection names, when known, which a data read needs."
  [schemas* x env]
  (let [v (fn [sym] (str "p_" (ident (name sym))))
        e (fn [y] (prop->apa schemas* y env))
        node-type-of (fn [y] (when (and (vector? y) (= :var (first y))) (get env (second y))))
        quals (fn [qs] (apply hash-map qs))
        coll-set (fn [coll]
                   (let [[kind x & qs] coll qs (quals qs)]
                     (case kind
                       :nodes (str "{n_ \\in DOMAIN nodes : nodes[n_].type = " (q x)
                                   (when (contains? qs :under) (str " /\\ DescendsIn(nodes, n_, " (e (:under qs)) ")"))
                                   (when (contains? qs :children-of) (str " /\\ nodes[n_].parent = " (e (:children-of qs))))
                                   (when (contains? qs :in) (str " /\\ nodes[n_].state \\in " (tla-set (map q (:in qs)))))
                                   "}")
                       :effects (str "{j_ \\in DOMAIN effects : effects[j_].spec = " (q x)
                                     (when (contains? qs :owner) (str " /\\ effects[j_].owner = " (e (:owner qs))))
                                     (when (contains? qs :owner-under) (let [a (e (:owner-under qs))] (str " /\\ (effects[j_].owner = " a " \\/ DescendsIn(nodes, effects[j_].owner, " a "))")))
                                     (when (contains? qs :status) (str " /\\ effects[j_].status \\in " (tla-set (map q (:status qs)))))
                                     "}"))))
        elem-type (fn [coll] (when (and (vector? coll) (= :nodes (first coll))) (second coll)))
        bind (fn [sym coll] (assoc env sym (elem-type coll)))
        body-of (fn [sym coll body alias] (prop->apa schemas* body (assoc (bind sym coll) ::alias (assoc (::alias env {}) sym alias))))
        field-type (fn [y k] (let [t (node-type-of y)] (or (get-in schemas* [:by-type t :data k]) (get-in schemas* [:data k]))))]
    (cond
      (vector? x)
      (let [[op & args] x]
        (case op
          (:nodes :effects) (coll-set x)
          :forall (let [[sym coll body] args] (str "(\\A " (v sym) " \\in " (coll-set coll) " : " (prop->apa schemas* body (bind sym coll)) ")"))
          :exists (let [[sym coll body] args]
                    (if (nil? body) (str "(" (coll-set coll) " /= {})")
                        (str "(\\E " (v sym) " \\in " (coll-set coll) " : " (prop->apa schemas* body (bind sym coll)) ")")))
          :none (let [[sym coll body] args]
                  (if (nil? body) (str "(" (coll-set coll) " = {})")
                      (str "(~\\E " (v sym) " \\in " (coll-set coll) " : " (prop->apa schemas* body (bind sym coll)) ")")))
          ;; cardinality as pair quantifiers: no two distinct elements satisfy the body
          :at-most-one (let [[sym coll body] args
                             S (coll-set coll)
                             a (v sym) b (str (v sym) "_2")
                             holds (fn [alias] (if (nil? body) "TRUE" (prop->apa schemas* body (assoc (bind sym coll) ::alias (assoc (::alias env {}) sym alias)))))]
                         (str "(\\A " a ", " b " \\in " S " : (" (holds a) " /\\ " (holds b) ") => " a " = " b ")"))
          :exactly-one (let [[sym coll body] args
                             S (coll-set coll)
                             a (v sym) b (str (v sym) "_2")
                             holds (fn [alias] (if (nil? body) "TRUE" (prop->apa schemas* body (assoc (bind sym coll) ::alias (assoc (::alias env {}) sym alias)))))]
                         (str "(\\E " a " \\in " S " : " (holds a) " /\\ (\\A " b " \\in " S " : " (holds b) " => " b " = " a "))"))
          :implies (str "(" (e (first args)) " => " (e (second args)) ")")
          :state-of (str "nodes[" (e (first args)) "].state")
          :type-of (str "nodes[" (e (first args)) "].type")
          :root? (str "(nodes[" (e (first args)) "].parent = NoId)")
          :parent-of (str "nodes[" (e (first args)) "].parent")
          :data-of (let [[n k] args t (field-type n k)]
                     (when-not (scalar? t) (fail "a property over a collection field is not provable; it is checkable" x))
                     (str "nodes[" (e n) "].data." (fname k) (when (maybe? t) ".v")))
          :counter-of (let [[n c] args t (node-type-of n)]
                        (when-not t (fail ":counter-of needs a node of a known type" x))
                        (str (counter-value-name t c) "(nodes, " (e n) ")"))
          :owner-of (str "effects[" (e (first args)) "].owner")
          :status-of (str "effects[" (e (first args)) "].status")
          :effect-of (str "effects[" (e (first args)) "].spec")
          :var (get (::alias env {}) (first args) (v (first args)))
          := (str "(" (e (first args)) " = " (e (second args)) ")")
          :< (str "(" (e (first args)) " < " (e (second args)) ")")
          :<= (str "(" (e (first args)) " <= " (e (second args)) ")")
          :> (str "(" (e (first args)) " > " (e (second args)) ")")
          :>= (str "(" (e (first args)) " >= " (e (second args)) ")")
          :+ (str "(" (e (first args)) " + " (e (second args)) ")")
          :- (str "(" (e (first args)) " - " (e (second args)) ")")
          :* (str "(" (e (first args)) " * " (e (second args)) ")")
          :and (str "(" (str/join " /\\ " (map e args)) ")")
          :or (str "(" (str/join " \\/ " (map e args)) ")")
          :not (str "(~" (e (first args)) ")")
          :nil? (let [[y] args]
                  (if (and (vector? y) (= :data-of (first y)))
                    (let [[_ n k] y t (field-type n k)]
                      (if (maybe? t) (str "(~nodes[" (e n) "].data." (fname k) ".some)") (str "(nodes[" (e n) "].data." (fname k) " = " (default-of t) ")")))
                    (fail ":nil? in a property is only over a data field" x)))
          :if (str "(IF " (e (first args)) " THEN " (e (second args)) " ELSE " (e (nth args 2)) ")")
          (fail "property form not provable in the typed specification" x)))
      (string? x) (q x)
      (boolean? x) (tla-bool x)
      (number? x) (str (long x))
      :else (fail "literal not modelled" x))))

(defn- invariant-name [n] (str "Inv_" (ident (name n))))

(defn- properties
  "The framework's properties and the definition's provable business
  invariants, the conjunction the induction proves; a checkable property is
  named and left out."
  [specs schemas*]
  (let [invs (sort-by key (:invariants specs))
        provable (filter #(= :provable (:class (val %))) invs)]
    (str "\\* --- what must hold ---\n"
         "NoRejection == ~bad\n"
         "ChildrenAllowed == \\A n \\in DOMAIN nodes : ParentAllows(nodes, nodes[n].parent, nodes[n].type, nodes[n].state)\n"
         "NotDead == Quiescent => IsTerminal(nodes[0].type, nodes[0].state)\n"
         (when (seq invs) "\\* the definition's business invariants (docs/design/invariants.md): the provable ones\n")
         (str/join (for [[n {:keys [property class]}] invs]
                     (if (= :provable class)
                       (str (invariant-name n) " == " (prop->apa schemas* property {}) "\n")
                       (str "\\* " (name n) ": checkable, not provable; the simulator, the models and the oracle check it\n"))))
         "Properties == NoRejection /\\ ChildrenAllowed /\\ NotDead"
         (str/join (map #(str " /\\ " (invariant-name (key %))) provable)) "\n")))

(def ^:private runtime-induction "
\\* --- the inductive invariant: a well-formed state, the properties, and the application's lemmas ---
WellFormed ==
  /\\ 0 \\in DOMAIN nodes
  /\\ nodes[0].parent = NoId /\\ nodes[0].type = RootType
  /\\ \\A n \\in DOMAIN nodes : n >= 0 /\\ n < nextId /\\ nodes[n].type \\in Types /\\ IsState(nodes[n].type, nodes[n].state)
  /\\ \\A n \\in DOMAIN nodes : n /= 0 => (nodes[n].parent \\in DOMAIN nodes /\\ nodes[n].parent < n)
  \\* the states a node has visited: its initial one, its current one, states of its type
  /\\ \\A n \\in DOMAIN nodes : nodes[n].state \\in nodes[n].visited /\\ InitialOf(nodes[n].type) \\in nodes[n].visited
                               /\\ (nodes[n].prev = \"\" \\/ nodes[n].prev \\in nodes[n].visited)
                               /\\ \\A s \\in nodes[n].visited : IsState(nodes[n].type, s)
                                    \\* every visited state but the first was entered from a visited state
                                    /\\ (s = InitialOf(nodes[n].type) \\/ \\E p \\in nodes[n].visited : p \\in PrevOf(nodes[n].type, s))
  \\* a node came from nothing (created, still in its initial state) or from a state with a transition into its own
  /\\ \\A n \\in DOMAIN nodes : (nodes[n].prev = \"\" /\\ nodes[n].state = InitialOf(nodes[n].type))
                               \\/ nodes[n].prev \\in PrevOf(nodes[n].type, nodes[n].state)
  /\\ \\A j \\in DOMAIN queue : queue[j].target \\in DOMAIN nodes /\\ queue[j].evt \\in Events
  /\\ \\A j \\in DOMAIN effects : effects[j].owner \\in DOMAIN nodes /\\ effects[j].spec \\in EffectSpecs
                               /\\ effects[j].status \\in {\"CREATED\", \"DISPATCHED\", \"COMPLETED\", \"FAILED\", \"CANCELLED\", \"LOST\"}
  \\* what the runtime guarantees: children of the types their parent creates, events the
  \\* target handles, auto requests for auto transitions and one per node, effects of the owner's type
  /\\ \\A n \\in DOMAIN nodes : n /= 0 => MayCreate(nodes[nodes[n].parent].type, nodes[n].type)
  /\\ \\A j \\in DOMAIN queue : queue[j].evt \\in EventsOf(nodes[queue[j].target].type)
                             /\\ (queue[j].auto => queue[j].evt \\in AutoEventsOf(nodes[queue[j].target].type))
                             /\\ (~queue[j].auto => queue[j].evt \\notin AutoEvents)
                             /\\ queue[j].sourceType \\in Types \\cup {\"\"}
  /\\ \\A j, l \\in DOMAIN queue : (j /= l /\\ queue[j].auto /\\ queue[l].auto) => queue[j].target /= queue[l].target
  \\* an event's source: nothing (the first event, or an effect's outcome), the target itself
  \\* (an emit, an auto request), its parent (a command), or a descendant (a routed emit)
  /\\ \\A j \\in DOMAIN queue :
       LET q == queue[j] IN
       \\/ /\\ q.source = NoId /\\ q.sourceType = \"\"
          /\\ \\/ (q.evt = FirstEvent /\\ q.target = 0 /\\ nodes[0].state = InitialOf(RootType) /\\ q.eff = NoId)
             \\/ (q.eff \\in DOMAIN effects /\\ effects[q.eff].owner = q.target /\\ EffectEvent(effects[q.eff], q.evt))
       \\/ /\\ q.source \\in DOMAIN nodes /\\ q.sourceType = nodes[q.source].type /\\ q.eff = NoId
          /\\ (q.auto \\/ q.evt \\in EmitsOf(q.sourceType))
          /\\ (q.auto \\/ nodes[q.source].state \\in AfterEmitting(q.sourceType, q.evt))
          /\\ ((q.source = nodes[q.target].parent /\\ CreationCommand(q.sourceType, q.evt))
                => nodes[q.target].state = InitialOf(nodes[q.target].type) /\\ \\A l \\in DOMAIN queue : l < j => queue[l].target /= q.target)
          /\\ (q.source = q.target \\/ q.source = nodes[q.target].parent \\/ DescendsIn(nodes, q.source, q.target))
  /\\ \\A n \\in DOMAIN nodes : n /= 0 => nodes[nodes[n].parent].state \\in AfterCreating(nodes[nodes[n].parent].type, nodes[n].type)
  /\\ \\A j \\in DOMAIN effects : nodes[effects[j].owner].state \\in AfterEffect(nodes[effects[j].owner].type, effects[j].spec)
  /\\ \\A j \\in DOMAIN effects : effects[j].spec \\in EffectsOf(nodes[effects[j].owner].type)
  /\\ \\A j \\in DOMAIN effects : effects[j].status = \"LOST\" => AllowDrop
  \\* a queued self-emitted event that only an effect's outcome produces: that outcome was consumed
  /\\ \\A j \\in DOMAIN queue :
       LET q == queue[j] causes == SelfEmitCauses(nodes[q.target].type, q.evt) IN
       (q.source = q.target /\\ ~q.auto /\\ causes /= {})
         => \\E e \\in DOMAIN effects : effects[e].owner = q.target /\\ ~(\\E l \\in DOMAIN queue : queue[l].eff = e)
              /\\ ((effects[e].status = \"COMPLETED\" /\\ Completion(effects[e].spec) \\in causes)
                  \\/ (effects[e].status = \"FAILED\" /\\ Failure(effects[e].spec) \\in causes)
                  \\/ (effects[e].status = \"LOST\" /\\ effects[e].timeout \\in causes))
  \\* and it is not queued behind a pending outcome of the same node: that outcome is consumed next
  /\\ \\A j, l \\in DOMAIN queue :
       (j < l /\\ queue[j].target = queue[l].target /\\ queue[j].eff /= NoId
        /\\ queue[l].source = queue[l].target /\\ ~queue[l].auto /\\ SelfEmitCauses(nodes[queue[l].target].type, queue[l].evt) /= {})
         => FALSE
  \\* a pending effect's owner, live or with the outcome still queued, is where it awaits it
  /\\ \\A j \\in DOMAIN effects : Pending(j) => nodes[effects[j].owner].state \\in LiveOwnerStates(nodes[effects[j].owner].type, effects[j].spec)
  \\* an effect's outcome is queued at most once; a consumed outcome put its owner after the transition
  \\* that took it; an effect is cancelled only when its owner reached a terminal state
  /\\ \\A j, l \\in DOMAIN queue : (j /= l /\\ queue[j].eff /= NoId) => queue[j].eff /= queue[l].eff
  /\\ \\A e \\in DOMAIN effects :
       LET ev == CASE effects[e].status = \"COMPLETED\" -> Completion(effects[e].spec)
                   [] effects[e].status = \"FAILED\" -> Failure(effects[e].spec)
                   [] effects[e].status = \"LOST\" -> effects[e].timeout
                   [] OTHER -> \"\"
       IN (ev /= \"\" /\\ ~(\\E j \\in DOMAIN queue : queue[j].eff = e))
            => nodes[effects[e].owner].state \\in AfterConsumed(nodes[effects[e].owner].type, ev)
  /\\ \\A e \\in DOMAIN effects : effects[e].status = \"CANCELLED\" => IsTerminal(nodes[effects[e].owner].type, nodes[effects[e].owner].state)
  \\* only the last effect of a specification a node requested can be pending: a retry follows the outcome
  /\\ \\A e1, e2 \\in DOMAIN effects : (e1 < e2 /\\ effects[e1].owner = effects[e2].owner /\\ effects[e1].spec = effects[e2].spec) => ~Pending(e1)
  \\* a node in a state where it awaits an effect has one pending
  /\\ \\A n \\in DOMAIN nodes : \\A spec \\in EffectSpecs :
       nodes[n].state \\in LiveOwnerStates(nodes[n].type, spec) => \\E e \\in DOMAIN effects : effects[e].owner = n /\\ effects[e].spec = spec /\\ Pending(e)
  /\\ \\A j \\in DOMAIN queue : queue[j].eff = NoId \\/ queue[j].eff \\in DOMAIN effects
  \\* an auto request is the node's own; a fresh child, still in its initial state, has its creation command queued
  /\\ \\A j \\in DOMAIN queue : queue[j].auto => queue[j].source = queue[j].target
  /\\ \\A n \\in DOMAIN nodes : (n /= 0 /\\ nodes[n].state = InitialOf(nodes[n].type) /\\ HasCreationCommand(nodes[nodes[n].parent].type))
       => \\E j \\in DOMAIN queue : queue[j].target = n /\\ CreationCommand(nodes[nodes[n].parent].type, queue[j].evt)
  /\\ \\A n \\in DOMAIN nodes : SelfEmitEvents(nodes[n].type, nodes[n].state) /= {} =>
       \\E j \\in DOMAIN queue : queue[j].target = n /\\ queue[j].evt \\in SelfEmitEvents(nodes[n].type, nodes[n].state)
  /\\ PokedWhenEnabled /\\ NoDeadNode /\\ GuardClosures /\\ FallbackStates /\\ CreatedChildrenPresent /\\ CommandedChildren
TypeOK ==
  /\\ nodes = Gen(NodeBound)
  /\\ queue = Gen(QueueBound)
  /\\ effects = Gen(EffectBound)
  /\\ bad \\in BOOLEAN
  /\\ fired = Gen(NodeBound)
  /\\ nextId \\in 1..(NodeBound + 1)
\\* The invariant to prove inductive, and the states the step check starts from: any
\\* state of the bounded shapes that satisfies it (Gen chooses an arbitrary value).
\\* Every node accepts its queued events in order: from its state now, each event has a
\\* transition or is ignored; an auto request names an auto transition of some state it may be
\\* in and moves it along one; a guarded transition may refuse and leave it. What a step appends
\\* must be accepted too.
Accepted ==
  \\A n \\in DOMAIN nodes :
    LET t == nodes[n].type
        \\* @type: ({ states: Set(Str), ok: Bool }, $queued) => { states: Set(Str), ok: Bool };
        Sim(acc, q) ==
          IF q.target /= n THEN acc
          ELSE IF q.auto THEN [states |-> acc.states \\cup AutoDstsOf(t, q.evt),
                               ok |-> acc.ok /\\ \\E s \\in acc.states : q.evt \\in AutoEventsFrom(t, s)]
          ELSE [states |-> { IF Dst(t, s, q.evt) /= \"\" THEN Dst(t, s, q.evt) ELSE s : s \\in acc.states }
                              \\cup { s \\in acc.states : Guarded(t, s, q.evt) },
                ok |-> acc.ok /\\ \\A s \\in acc.states : Dst(t, s, q.evt) /= \"\" \\/ Ignores(t, s, q.evt) \\/ RouteIgnorable(q.evt, q.sourceType)]
    IN ApaFoldSeqLeft(Sim, [states |-> {nodes[n].state}, ok |-> TRUE], queue).ok
IndInv == WellFormed /\\ Accepted /\\ Properties /\\ Lemmas
IndInit == TypeOK /\\ IndInv
")

(defn- value->apa [v t]
  (cond
    (maybe? t) (if (nil? v) (default-of t) (str "[some |-> TRUE, v |-> " (value->apa v (second t)) "]"))
    (= :int t) (str (long (or v 0)))
    (= :str t) (q (or v ""))
    (= :bool t) (tla-bool (boolean v))
    :else (fail "schema type not carried in the typed state" t)))

(defn- driver [schemas* {:keys [root-type first-event first-payload children max-repeat allow-drop bounds]}]
  (let [event-schema (scalars (get-in schemas* [:by-event first-event] {}))
        {:keys [nodes queue effects] :or {nodes 2 queue 2 effects 2}} bounds
        max-children (long (reduce max 2 (vals children)))]
    (str "RootType == " (q root-type) "\n"
         "FirstEvent == " (q first-event) "\n"
         "\\* @type: () => $payload;\n"
         "FirstPayload == "
         (if (empty? event-schema)
           "DefaultPayload"
           (str "[DefaultPayload EXCEPT "
                (str/join ", " (map (fn [[k t]] (str "!." (fname k) " = " (value->apa (get first-payload k) t))) (sort-by (comp name key) event-schema))) "]"))
         "\n"
         "MaxRepeat == " (long (or max-repeat 3)) "\n"
         "MaxChildren == " max-children "\n"
         "AllowDrop == " (tla-bool allow-drop) "\n"
         "NodeBound == " nodes "\nQueueBound == " queue "\nEffectBound == " effects "\n")))

(defn module-name [specs] (str (ident (or (get-in specs [:manifest :profile :name]) "voucha")) "_typed"))

(defn generate
  "The typed TLA+ module for an artifact and a run configuration
  `{:root-type :first-event :first-payload :children :max-repeat :allow-drop
  :bounds {:nodes :queue :effects} :lemmas \"TLA+\"}`. The root is node 0;
  the first event's scalar payload fields are the run's; the largest value
  in `:children` bounds how many children one step may create."
  [{:keys [artifact-id specs]} {:keys [root-type first-event lemmas] :as config}]
  (when-not (get-in specs [:node-types root-type])
    (throw (ex-info (str "unknown root node type " root-type) {:type ::bad-config})))
  (when-not (contains? (set (:events specs)) first-event)
    (throw (ex-info (str "unknown event " first-event) {:type ::bad-config})))
  (let [schemas* (schemas specs)
        name* (module-name specs)
        depth (count (:node-types specs))]
    {(str name* ".tla")
     (str "---- MODULE " name* " ----\n"
          "\\* Generated by voucha from artifact " artifact-id "\n"
          "\\* Profile: " (get-in specs [:manifest :profile :name]) " " (get-in specs [:manifest :profile :version])
          ". Regenerate on every change.\n"
          "EXTENDS Integers, Sequences, FiniteSets, TLC, Apalache\n\n"
          (aliases schemas*) "\n"
          (runtime-defaults schemas*)
          "\n\\* --- the run ---\n"
          (driver schemas* config)
          (tree depth)
          "\n\\* --- the definition ---\n"
          (tables specs)
          runtime-abstract
          "\n\\* --- the transitions: guards, matches and plans ---\n"
          (transition-operators specs schemas*)
          (properties specs schemas*)
          "\n\\* --- the application's lemmas, for the induction ---\n"
          "Lemmas == " (or lemmas "TRUE") "\n"
          runtime-induction
          "====\n")}))

(defn write! [dir files]
  (doall (for [[rel content] (sort-by key files)
               :let [f (java.io.File. ^String dir ^String rel)]]
           (do (.mkdirs (.getParentFile f)) (spit f content) (.getPath f)))))

;; --- running Apalache ------------------------------------------------------------------

(defn apalache
  "The Apalache launcher: `APALACHE_MC`, else the usual places."
  []
  (some #(when (and % (.canExecute (java.io.File. ^String %))) %)
        (concat [(System/getenv "APALACHE_MC") "/opt/apalache/bin/apalache-mc"]
                (map str (sort (filter #(.isFile ^java.io.File %)
                                       (map #(java.io.File. ^java.io.File % "bin/apalache-mc")
                                            (or (.listFiles (java.io.File. (str (System/getProperty "user.home") "/.local/opt/apalache")))
                                                []))))))))

(defn- run! [dir args]
  (let [launcher (apalache)]
    (if-not launcher
      {:exit 2 :out "apalache-mc not found: set APALACHE_MC or install it under ~/.local/opt/apalache"}
      (let [pb (doto (ProcessBuilder. ^java.util.List (into [launcher] args))
                 (.directory (java.io.File. ^String dir))
                 (.redirectErrorStream true))
            proc (.start pb)
            out (slurp (.getInputStream proc))]
        {:exit (.waitFor proc) :out out}))))

(defn- counterexample [dir out]
  (when-let [f (some->> (re-find #"(?m)^.*Check the counterexample in:\s*\n?\s*(\S+counterexample\S*\.tla)" out) second)]
    (let [file (java.io.File. ^String f)
          file (if (.isAbsolute file) file (java.io.File. ^String dir f))]
      (when (.isFile file) (slurp file)))))

(defn- outcome [dir r]
  (let [out (:out r)]
    (cond
      (not (zero? (:exit r)))
      (if (re-find #"EXITCODE: ERROR \(12\)" out)
        {:ok? false :violated? true :out out :counterexample (counterexample dir out)}
        {:ok? false :error (or (some->> (re-find #"(?m)^(.*(?:rror|Error).*)$" out) second) "apalache failed") :out out})
      :else {:ok? true :out out})))

(defn typecheck! [dir name*]
  (outcome dir (run! dir ["typecheck" (str name* ".tla")])))

(defn check!
  "The bounded symbolic check: every behaviour of up to `length` steps."
  [dir name* {:keys [length] :or {length 8}}]
  (outcome dir (run! dir ["check" "--init=Init" "--next=Next" "--inv=Properties" (str "--length=" length) (str name* ".tla")])))

(defn induction!
  "The three checks of the inductive invariant. Returns a map of :init :step
  :implies, each an outcome, and :proven? when all three hold."
  [dir name*]
  (let [init (outcome dir (run! dir ["check" "--init=Init" "--next=Next" "--inv=IndInv" "--length=0" (str name* ".tla")]))
        step (outcome dir (run! dir ["check" "--init=IndInit" "--next=Next" "--inv=IndInv" "--length=1" (str name* ".tla")]))
        implies (outcome dir (run! dir ["check" "--init=IndInit" "--next=Next" "--inv=Properties" "--length=0" (str name* ".tla")]))]
    {:init init :step step :implies implies
     :proven? (and (:ok? init) (:ok? step) (:ok? implies))}))
