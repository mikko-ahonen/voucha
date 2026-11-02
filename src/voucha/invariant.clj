(ns voucha.invariant
  "Declarative business invariants (docs/design/invariants.md): properties of
  a tree stated once in the definition, over what the kernel records, the
  nodes with their types, states, parents and data, and the effects with
  their names, owners and statuses. This namespace is the compiler's part:
  the forms, their validation against the definition, the class of each
  property, and the inlining of named predicates. Every checker evaluates the
  inlined property; none has a notion of a call.

  A property is one of two classes. Provable: over the tree, the effects and
  scalar data fields, with quantifiers and no cardinality, which the typed
  specification carries and so the induction proves. Checkable: anything with
  `:count`, `:sum`, a collection field or `:when-quiescent`, which every
  other checker runs and the proof leaves out."
  (:require [clojure.string :as str]))

(def statuses
  "The kernel's effect instance statuses (voucha.repo.effect), and LOST, which
  only the models have: an effect whose outcome never arrives."
  #{"CREATED" "DISPATCHING" "DISPATCHED" "ACKNOWLEDGED" "COMPLETED" "DISPATCH_FAILED" "FAILED" "TIMED_OUT" "CANCELLED" "LOST"})

;; --- the checker -----------------------------------------------------------------------

(defn- scalar-type? [t] (or (#{:int :str :bool} t) (and (vector? t) (= :maybe (first t)))))

(defn- kind-of-type
  "The kind a schema type reads as: :int :str :bool, :set for a collection, :record."
  [t]
  (cond
    (#{:int :str :bool} t) t
    (and (vector? t) (= :maybe (first t))) (kind-of-type (second t))
    (and (vector? t) (= :set (first t))) :set
    (map? t) :record
    :else :any))

(declare rebind-bool)

(defn- check-form
  "Walk a form, returning its kind (:bool :int :str :node :effect :coll :set
  :record :any); errors and the checkable mark go to the atoms in `ctx`.
  `env` binds variables to {:kind :node|:effect|:record :type T} and holds
  the variables guarded by `:root?`."
  [ctx env x]
  (let [{:keys [node-types effect-names predicates schemas? errors checkable]} ctx
        fail! (fn [msg] (swap! errors conj (str msg " in " (pr-str x))) :any)
        checkable! (fn [] (reset! checkable true))
        var-of (fn [e] (when (and (vector? e) (= :var (first e))) (second e)))
        node! (fn [e what]
                (let [k (check-form ctx env e)]
                  (when-not (#{:node :any} k) (fail! (str what " needs a node, not " (name k))))
                  k))
        bool! (fn [e what] (let [k (check-form ctx env e)] (when-not (#{:bool :any} k) (fail! (str what " needs a boolean, not " (name k)))) k))
        num! (fn [e what] (let [k (check-form ctx env e)] (when-not (#{:int :any} k) (fail! (str what " needs a number, not " (name k)))) k))
        bind (fn [env sym elem] (assoc-in env [:vars sym] elem))
        elem-of (fn [coll]
                  (cond
                    (and (vector? coll) (= :nodes (first coll))) {:kind :node :type (second coll)}
                    (and (vector? coll) (= :effects (first coll))) {:kind :effect :name (second coll)}
                    :else (let [t (:elem-type (meta (check-form ctx env coll)))] (or t {:kind :any}))))
        coll! (fn [coll what]
                (let [k (check-form ctx env coll)]
                  (when-not (#{:coll :set :any} k) (fail! (str what " needs a collection, not " (name k))))
                  k))
        quals! (fn [quals allowed what]
                 (when (odd? (count quals)) (fail! (str what " takes qualifier and value pairs")))
                 (doseq [[q v] (partition 2 quals)]
                   (when-not (allowed q) (fail! (str what " has no qualifier " (pr-str q))))))
        type-of-var (fn [e] (when-let [v (var-of e)] (get-in env [:vars v :type])))]
    (cond
      (vector? x)
      (let [[op & args] x]
        (case op
          ;; collections
          :nodes (let [[t & quals] args]
                   (when-not (contains? node-types t) (fail! (str "unknown node type " (pr-str t))))
                   (quals! quals #{:under :children-of :in} ":nodes")
                   (doseq [[q v] (partition 2 quals)]
                     (case q
                       (:under :children-of) (node! v (pr-str q))
                       :in (if-not (and (vector? v) (every? string? v))
                             (fail! ":in needs a vector of state names")
                             (doseq [s v :when (and (contains? node-types t) (not (contains? (get-in node-types [t :states]) s)))]
                               (fail! (str "state " (pr-str s) " is not a state of " (pr-str t)))))
                       nil))
                   :coll)
          :effects (let [[n & quals] args]
                     (when-not (contains? effect-names n) (fail! (str "unknown effect " (pr-str n))))
                     (quals! quals #{:owner :owner-under :status} ":effects")
                     (doseq [[q v] (partition 2 quals)]
                       (case q
                         (:owner :owner-under) (node! v (pr-str q))
                         :status (if-not (and (vector? v) (every? string? v))
                                   (fail! ":status needs a vector of statuses")
                                   (doseq [s v :when (not (contains? statuses s))]
                                     (fail! (str "unknown effect status " (pr-str s) "; the kernel's are " (str/join ", " (sort statuses))))))
                         nil))
                     :coll)
          ;; quantifiers
          (:forall :exists :none :at-most-one :exactly-one)
          (let [[sym coll body] args]
            (cond
              (not (keyword? sym)) (fail! (str (pr-str op) " needs a keyword to bind"))
              (and (nil? body) (= :forall op)) (fail! ":forall needs a body")
              :else (do (coll! coll (pr-str op))
                        (when (some? body) (rebind-bool ctx (assoc-in env [:vars sym] (elem-of coll)) body (pr-str op)))))
            :bool)
          :implies (let [[a b] args]
                     (bool! a ":implies")
                     ;; `[:implies [:not [:root? v]] ...]` guards a read of v's parent
                     (let [v (and (vector? a) (= :not (first a)) (vector? (second a)) (= :root? (first (second a))) (var-of (second (second a))))
                           env* (if v (update env :guarded (fnil conj #{}) v) env)]
                       (rebind-bool ctx env* b ":implies"))
                     :bool)
          :when-quiescent (do (checkable!) (bool! (first args) ":when-quiescent") :bool)
          ;; reads of a node
          (:state-of :type-of) (do (node! (first args) (pr-str op)) :str)
          :root? (do (node! (first args) ":root?") :bool)
          :parent-of (let [v (var-of (first args))]
                       (node! (first args) ":parent-of")
                       (when-not (and v (contains? (:guarded env) v))
                         (fail! ":parent-of must be guarded by :root? (as [:implies [:not [:root? v]] ...] or [:if [:root? v] ... ...])"))
                       :node)
          :data-of (let [[e k & ks] args
                         t (type-of-var e)]
                     (node! e ":data-of")
                     (when-not (keyword? k) (fail! ":data-of needs a keyword field"))
                     (let [schema (if t (get-in node-types [t :data]) (:data-union ctx))
                           ft (get schema k)]
                       (cond
                         (and schemas? (nil? ft)) (fail! (str "field " (pr-str k) " is not in the :data schema of " (pr-str (or t "the node types"))))
                         (nil? ft) :any
                         (seq ks) (do (checkable!) :any)
                         (scalar-type? ft) (kind-of-type ft)
                         :else (do (checkable!) (kind-of-type ft)))))
          :counter-of (let [[e c] args t (type-of-var e)]
                        (node! e ":counter-of")
                        (when-not (keyword? c) (fail! ":counter-of needs a keyword"))
                        (when (and t (not (contains? (get-in node-types [t :counters]) c)))
                          (fail! (str "counter " (pr-str c) " is not a counter of " (pr-str t))))
                        :int)
          ;; reads of an effect
          :owner-of (let [k (check-form ctx env (first args))]
                      (when-not (#{:effect :any} k) (fail! ":owner-of needs an effect"))
                      :node)
          (:status-of :effect-of) (let [k (check-form ctx env (first args))]
                                    (when-not (#{:effect :any} k) (fail! (str (pr-str op) " needs an effect")))
                                    :str)
          ;; cardinality: checkable
          :count (do (checkable!) (coll! (first args) ":count") :int)
          :sum (let [[sym coll term] args]
                 (checkable!)
                 (when-not (keyword? sym) (fail! ":sum needs a keyword to bind"))
                 (coll! coll ":sum")
                 (let [k (check-form ctx (bind env sym (elem-of coll)) term)]
                   (when-not (#{:int :any} k) (fail! (str ":sum needs a numeric term, not " (name k)))))
                 :int)
          ;; the bound variable
          :var (let [v (first args) b (get-in env [:vars v])]
                 (if-not b
                   (fail! (str "unbound variable " (pr-str v)))
                   (:kind b)))
          :get (let [[e k] args]
                 (check-form ctx env e)
                 (when-not (keyword? k) (fail! ":get needs a keyword field"))
                 (checkable!)
                 :any)
          ;; the plan expressions
          (:= :< :<= :> :>=) (do (doseq [a args] (check-form ctx env a))
                                 (when (not= 2 (count args)) (fail! (str (pr-str op) " takes 2 arguments")))
                                 :bool)
          (:+ :- :*) (do (doseq [a args] (num! a (pr-str op))) (when (not= 2 (count args)) (fail! (str (pr-str op) " takes 2 arguments"))) :int)
          (:and :or) (do (doseq [a args] (bool! a (pr-str op))) :bool)
          :not (do (bool! (first args) ":not") (when (not= 1 (count args)) (fail! ":not takes 1 argument")) :bool)
          :nil? (do (check-form ctx env (first args)) :bool)
          :if (let [[c then else] args]
                (bool! c ":if")
                (let [v (and (vector? c) (= :root? (first c)) (var-of (second c)))
                      env* (if v (update env :guarded (fnil conj #{}) v) env)
                      kt (check-form ctx env then)
                      ke (check-form ctx env* else)]
                  (if (or (= kt ke) (= :any kt)) ke kt)))
          ;; a named predicate, applied to one argument
          :pred (let [[pname e] args p (get predicates pname)]
                  (cond
                    (nil? p) (fail! (str "unknown predicate " (pr-str pname)))
                    (not= 2 (count args)) (fail! ":pred takes a predicate name and one argument")
                    :else (let [[_ sym body] p
                                k (check-form ctx env e)
                                elem (if-let [v (var-of e)] (get-in env [:vars v]) {:kind k})]
                            (rebind-bool ctx (bind env sym elem) body (str "predicate " (pr-str pname)))))
                  :bool)
          (:data :payload :node-id :counter :select :map :str :opaque)
          (fail! (str (pr-str op) " is a plan expression; a property has no current node or event"))
          (fail! (str "unknown form " (pr-str op)))))
      (string? x) :str
      (number? x) :int
      (boolean? x) :bool
      (nil? x) (fail! "nil is not a value here; test absence with :nil?")
      :else (fail! (str "a literal must be a string, number or boolean, not " (pr-str x))))))

(defn- rebind-bool
  "Walk `x` as a boolean under `env`: the consequent of an :implies whose
  antecedent guards a parent read."
  [ctx env x what]
  (let [k (check-form ctx env x)]
    (when-not (#{:bool :any} k) (swap! (:errors ctx) conj (str what " needs a boolean, not " (name k) " in " (pr-str x))))
    k))

(defn- predicate-cycle
  "A cycle among the predicates, as the names on it, or nil."
  [predicates]
  (let [uses (fn [body] (set (keep #(when (and (vector? %) (= :pred (first %))) (second %)) (tree-seq vector? rest body))))
        graph (into {} (map (fn [[n [_ _ body]]] [n (uses body)])) predicates)]
    (loop [todo (keys graph) done #{}]
      (when-let [start (first todo)]
        (or (loop [stack [[start [start]]] seen #{}]
              (when-let [[n path] (peek stack)]
                (let [next* (remove done (get graph n))]
                  (if-let [back (some #(when (some #{%} path) %) next*)]
                    (conj (vec (drop-while #(not= % back) path)) back)
                    (recur (into (pop stack) (map (fn [m] [m (conj path m)])) (remove seen next*))
                           (into seen next*))))))
            (recur (rest todo) (conj done start)))))))

(defn- predicate-errors [predicates]
  (concat
   (for [[n p] predicates
         :let [problem (cond (not (keyword? n)) "a predicate name must be a keyword"
                             (not (and (vector? p) (= :fn (first p)) (= 3 (count p)) (keyword? (second p)))) "a predicate is [:fn :x body]")]
         :when problem]
     (str "predicate " (pr-str n) ": " problem))
   (when-let [cycle (predicate-cycle predicates)]
     [(str "predicates use one another in a cycle: " (str/join " -> " (map pr-str cycle)))])))

(defn context
  "The checking context for a definition: node types with their states, data
  schemas and counters; effect names; predicates; whether schemas are
  declared at all."
  [d]
  (let [nts (:node-types d)
        all-names (fn [{:keys [states state-tree]}]
                    (letfn [(names [tree] (mapcat #(if (map? %) (cons (:name %) (names (:states %))) [%]) tree))]
                      (set (names (or state-tree states)))))]
    {:node-types (into {} (map (fn [[nt s]] [nt {:states (all-names s) :data (:data s) :counters (set (keys (:counters s)))}])) nts)
     :data-union (reduce merge {} (map :data (vals nts)))
     :schemas? (boolean (some (comp seq :data) (vals nts)))
     :effect-names (set (keys (:effects d)))
     :predicates (:predicates d)}))

(defn check
  "The problems of a definition's `:invariants` and `:predicates`, as
  messages, and the class of each property. Returns {:errors [..] :classes
  {name :provable|:checkable}}."
  [d]
  (let [ctx (context d)
        pred-errors (vec (predicate-errors (:predicates d)))
        results (for [[n prop] (:invariants d)]
                  (let [errors (atom []) checkable (atom false)
                        ctx* (assoc ctx :errors errors :checkable checkable)]
                    (cond
                      (not (keyword? n)) {:errors [(str "invariant " (pr-str n) ": a property name must be a keyword")]}
                      (empty? pred-errors)
                      (let [k (check-form ctx* {:vars {} :guarded #{}} prop)]
                        (when-not (#{:bool :any} k) (swap! errors conj (str "a property must be a boolean form, this is " (name k))))
                        {:name n :errors (mapv #(str "invariant " (pr-str n) ": " %) @errors)
                         :class (if @checkable :checkable :provable)})
                      :else {:name n :errors []})))]
    {:errors (into pred-errors (mapcat :errors results))
     :classes (into {} (keep (fn [{:keys [name class]}] (when (and name class) [name class])) results))}))

;; --- inlining ------------------------------------------------------------------------------

(defn- rename-bound
  "The body of a predicate with its bound variables renamed apart, so that
  inlining cannot capture a variable of the argument."
  [body suffix]
  (letfn [(ren [x bound]
            (cond
              (vector? x)
              (let [[op & args] x]
                (cond
                  (#{:forall :exists :none :at-most-one :exactly-one :sum} op)
                  (let [[sym coll & more] args
                        sym* (keyword (str (name sym) suffix))]
                    (into [op sym* (ren coll bound)] (map #(ren % (assoc bound sym sym*)) more)))
                  (= :var op) [:var (get bound (first args) (first args))]
                  :else (into [op] (map #(ren % bound) args))))
              :else x))]
    (ren body {})))

(defn- inline
  "A property with every `[:pred name e]` replaced by the predicate's body,
  the argument substituted for its variable."
  [predicates x]
  (let [counter (atom 0)]
    (letfn [(subst [body sym arg] (cond (and (vector? body) (= :var (first body)) (= sym (second body))) arg
                                        (vector? body) (into [] (map #(subst % sym arg) body))
                                        :else body))
            (walk [x]
              (if (and (vector? x) (= :pred (first x)))
                (let [[_ pname e] x
                      [_ sym body] (get predicates pname)
                      body* (rename-bound body (str "_" (swap! counter inc)))]
                  (walk (subst body* sym (walk e))))
                (if (vector? x) (into [] (map walk x)) x)))]
      (walk x))))

(defn- expand-states
  "`:in` lists with composite states expanded to their leaves."
  [leaves-of x]
  (if (vector? x)
    (if (and (= :nodes (first x)) (> (count x) 2))
      (let [[_ t & quals] x]
        (into [:nodes t] (mapcat (fn [[q v]] [q (if (= :in q) (vec (distinct (mapcat #(leaves-of t %) v))) (expand-states leaves-of v))]) (partition 2 quals))))
      (into [] (map #(expand-states leaves-of %) x)))
    x))

(defn compiled
  "The definition's properties as the artifact carries them: inlined, their
  `:in` lists over leaves, each with its class. `leaves-of` maps a node type
  and a state name to the leaves it stands for."
  [d leaves-of]
  (let [{:keys [classes]} (check d)]
    (into {} (map (fn [[n prop]]
                    [n {:property (expand-states leaves-of (inline (:predicates d) prop))
                        :class (get classes n)}]))
          (:invariants d))))

;; --- the evaluator -------------------------------------------------------------------------
;;
;; A world is what the simulator holds and the oracle loads: `{:nodes {id node} :effects
;; [effect] :quiescent? bool}` with a node `{:node-id :parent-node-id :node-type :local-state
;; :local-data}` (counters at [:local-data :counters name]) and an effect `{:id :owner-node-id
;; :effect-name :status}`. The same evaluator serves both; the models translate instead.

(defn- truthy? [v] (and (some? v) (not (false? v))))

(defn- descends?
  "Whether node `n` is in the subtree of `a` (not `a` itself)."
  [nodes n a]
  (loop [p (:parent-node-id n)]
    (cond (nil? p) false
          (= p (:node-id a)) true
          :else (recur (:parent-node-id (get nodes p))))))

(declare eval-form)

(defn- collection
  "The nodes or effects a collection form yields in the world."
  [world env [kind x & quals]]
  (let [quals (apply hash-map quals)
        nodes (:nodes world)]
    (case kind
      :nodes (cond->> (filter #(= x (:node-type %)) (vals nodes))
               (contains? quals :under) (filter (let [a (eval-form world env (:under quals))] #(descends? nodes % a)))
               (contains? quals :children-of) (filter (let [a (eval-form world env (:children-of quals))] #(= (:node-id a) (:parent-node-id %))))
               (contains? quals :in) (filter (let [states (set (:in quals))] #(contains? states (:local-state %)))))
      :effects (cond->> (filter #(= x (:effect-name %)) (:effects world))
                 (contains? quals :owner) (filter (let [a (eval-form world env (:owner quals))] #(= (:node-id a) (:owner-node-id %))))
                 (contains? quals :owner-under) (filter (let [a (eval-form world env (:owner-under quals))]
                                                          #(let [o (get nodes (:owner-node-id %))] (or (= (:node-id a) (:node-id o)) (descends? nodes o a)))))
                 (contains? quals :status) (filter (let [ss (set (:status quals))] #(contains? ss (:status %))))))))

(defn- eval-form
  [world env x]
  (cond
    (vector? x)
    (let [[op & args] x
          ev (fn [e] (eval-form world env e))
          bind (fn [sym v] (assoc-in env [:vars sym] v))
          over (fn [coll] (if (and (vector? coll) (#{:nodes :effects} (first coll))) (collection world env coll) (ev coll)))]
      (case op
        (:nodes :effects) (collection world env x)
        :forall (let [[sym coll body] args] (every? #(truthy? (eval-form world (bind sym %) body)) (over coll)))
        :exists (let [[sym coll body] args] (boolean (some #(or (nil? body) (truthy? (eval-form world (bind sym %) body))) (over coll))))
        :none (let [[sym coll body] args] (not-any? #(or (nil? body) (truthy? (eval-form world (bind sym %) body))) (over coll)))
        :at-most-one (let [[sym coll body] args] (<= (count (filter #(or (nil? body) (truthy? (eval-form world (bind sym %) body))) (over coll))) 1))
        :exactly-one (let [[sym coll body] args] (= 1 (count (filter #(or (nil? body) (truthy? (eval-form world (bind sym %) body))) (over coll)))))
        :implies (or (not (truthy? (ev (first args)))) (truthy? (ev (second args))))
        :when-quiescent (or (not (:quiescent? world)) (truthy? (ev (first args))))
        :state-of (:local-state (ev (first args)))
        :type-of (:node-type (ev (first args)))
        :root? (nil? (:parent-node-id (ev (first args))))
        :parent-of (get (:nodes world) (:parent-node-id (ev (first args))))
        :data-of (let [[e & path] args] (get-in (:local-data (ev e)) path))
        :counter-of (let [[e c] args] (get-in (:local-data (ev e)) [:counters c] 0))
        :owner-of (get (:nodes world) (:owner-node-id (ev (first args))))
        :status-of (:status (ev (first args)))
        :effect-of (:effect-name (ev (first args)))
        :count (count (over (first args)))
        :sum (let [[sym coll term] args] (reduce + 0 (map #(or (eval-form world (bind sym %) term) 0) (over coll))))
        :var (get-in env [:vars (first args)])
        :get (get (ev (first args)) (second args))
        := (= (ev (first args)) (ev (second args)))
        :< (< (ev (first args)) (ev (second args)))
        :<= (<= (ev (first args)) (ev (second args)))
        :> (> (ev (first args)) (ev (second args)))
        :>= (>= (ev (first args)) (ev (second args)))
        :+ (+ (ev (first args)) (ev (second args)))
        :- (- (ev (first args)) (ev (second args)))
        :* (* (ev (first args)) (ev (second args)))
        :and (every? #(truthy? (ev %)) args)
        :or (boolean (some #(truthy? (ev %)) args))
        :not (not (truthy? (ev (first args))))
        :nil? (nil? (ev (first args)))
        :if (if (truthy? (ev (first args))) (ev (second args)) (ev (nth args 2)))
        (throw (ex-info (str "cannot evaluate " (pr-str op) "; was the property compiled?") {:type ::invalid :form x}))))
    :else x))

(defn- witness
  "For a failing property, the elements its outermost universal quantifiers
  chose: the first failing one at each level, as [sym value] pairs."
  [world env x]
  (if (and (vector? x) (= :forall (first x)))
    (let [[_ sym coll body] x
          elems (if (and (vector? coll) (#{:nodes :effects} (first coll))) (collection world env coll) (eval-form world env coll))
          bad (first (remove #(truthy? (eval-form world (assoc-in env [:vars sym] %) body)) elems))]
      (when bad (cons [sym bad] (witness world (assoc-in env [:vars sym] bad) body))))
    nil))

(defn- describe-value [v]
  (cond (:node-id v) (str (:node-id v) " (" (:node-type v) " " (:local-state v) ")")
        (:effect-name v) (str (:effect-name v) " " (:status v) " of " (:owner-node-id v))
        :else (pr-str v)))

(defn holds?
  "Whether a compiled property holds of the world."
  [world property]
  (truthy? (eval-form world {:vars {}} property)))

(defn violations
  "The compiled properties of `specs` that fail in the world, each as
  {:property name :class class :detail text :node-id id}, the id that of the
  outermost failing node when there is one."
  [specs world]
  (vec (for [[n {:keys [property class]}] (:invariants specs)
             :when (not (holds? world property))
             :let [w (witness world {:vars {}} property)
                   node (some (fn [[_ v]] (when (:node-id v) v)) w)]]
         {:property n :class class :node-id (:node-id node)
          :detail (if (seq w)
                    (str (name n) " fails for " (str/join ", " (map (fn [[sym v]] (str (name sym) " = " (describe-value v))) w)))
                    (str (name n) " fails"))})))
