(ns voucha.handler
  "Handlers as data: the plan IR (docs/design/handler-ir.md).

  A transition may carry `:plan`, a vector of ops, instead of a code handler.
  The ops are the plan API as data, with expressions over the node's data and
  the event's payload, and plan-level branching:

      [:set path expr]  [:inc path expr]
      [:call effect {k expr}]  [:store effect {k expr}]  [:await \"Event\"]
      [:emit \"Event\" {k expr}]  [:emit-to :self|expr \"Event\" {k expr}]
      [:child expr \"Type\"]  [:timeout ms \"Event\"]
      [:each :x expr op...]  [:let {:x expr} op...]
      [:when expr op...]  [:if expr [op...] [op...]]

  Expressions: a literal; [:data k & ks] [:payload k & ks] [:node-id]
  [:counter :name] [:var :x] [:get e :k] [:select e [k...]] [:count e]
  [:sum e :k] [:map e :k] [:+ a b] [:- a b] [:str a...] [:= a b] [:< a b]
  [:<= a b] [:> a b] [:>= a b] [:and ...] [:or ...] [:not a] [:nil? a]
  [:opaque 'ns/sym e...]; a map is a map of expressions.

  Names of events, effects, states and child types are literals, so `derive`
  sees everything a plan can do without evaluating it, and `check` validates a
  plan the same way. `plan-fn` interprets one: the result is an ordinary plan,
  which the kernel validates and persists exactly as a code handler's. The
  target state is the transition's `:to`.

  `resolve` is the one place the kernel, the reference model and replay choose
  what runs for a transition: its plan, else its registered function, else the
  kernel's own mover. A function registered under a planned transition's
  symbol is not called; `conflicts` lists such registrations, except where the
  plan was extracted from that very function (`:plan-source`)."
  (:require [clojure.string :as str]
            [voucha.plan :as plan]))

;; --- evaluation -----------------------------------------------------------------

(defn- nums? [a b] (and (number? a) (number? b)))

(defn- truthy? [v] (and (some? v) (not (false? v))))

(declare eval-expr)

(defn- eval-or
  "First truthy value, else the last value: Clojure's `or`, which makes
  `[:or [:data :x] 0]` a default."
  [env args]
  (loop [[a & more] args last* nil]
    (if (nil? a)
      last*
      (let [v (eval-expr env a)]
        (if (truthy? v) v (if (seq more) (recur more v) v))))))

(defn eval-expr
  [{:keys [data payload node-id vars opaques] :as env} x]
  (cond
    (vector? x)
    (let [[op & args] x
          arg (fn [i] (eval-expr env (nth args i)))]
      (case op
        :data (get-in data (vec args))
        :payload (get-in payload (vec args))
        :node-id node-id
        :counter (get-in data [:counters (first args)] 0)
        :var (get vars (first args))
        :get (get (arg 0) (second args))
        :select (select-keys (arg 0) (second args))
        :count (count (arg 0))
        :sum (if (= 2 (count args))
               (reduce + 0 (map #(get % (second args)) (arg 0)))
               ;; [:sum :x coll term]: the term over each element bound as :x
               (let [[sym coll term] args]
                 (reduce + 0 (map #(eval-expr (assoc-in env [:vars sym] %) term) (eval-expr env coll)))))
        :map (mapv #(get % (second args)) (arg 0))
        :+ (+ (arg 0) (arg 1))
        :- (- (arg 0) (arg 1))
        :* (* (arg 0) (arg 1))
        :if (if (truthy? (arg 0)) (arg 1) (arg 2))
        :str (apply str (map #(eval-expr env %) args))
        := (= (arg 0) (arg 1))
        :< (let [a (arg 0) b (arg 1)] (and (nums? a b) (< a b)))
        :<= (let [a (arg 0) b (arg 1)] (and (nums? a b) (<= a b)))
        :> (let [a (arg 0) b (arg 1)] (and (nums? a b) (> a b)))
        :>= (let [a (arg 0) b (arg 1)] (and (nums? a b) (>= a b)))
        :and (every? #(truthy? (eval-expr env %)) args)
        :or (eval-or env args)
        :not (not (truthy? (arg 0)))
        :nil? (nil? (arg 0))
        :opaque (let [[sym & more] args
                      f (or (get opaques sym)
                            (throw (ex-info (str "no opaque function registered for " sym)
                                            {:type ::missing-opaque :symbol sym})))]
                  (apply f (map #(eval-expr env %) more)))
        (throw (ex-info (str "unknown expression " op) {:type ::invalid-plan :expr x}))))
    (map? x) (into {} (map (fn [[k v]] [k (eval-expr env v)])) x)
    :else x))

(declare exec-ops)

(defn- exec-op [env p op]
  (let [[kind & args] op
        ev (fn [x] (eval-expr env x))]
    (case kind
      :set (plan/patch p (list 'assoc-in (first args) (ev (second args))))
      :inc (plan/patch p (list 'inc-in (first args) (ev (second args))))
      :call (plan/call p (first args) (ev (or (second args) {})))
      :store (plan/store p (first args) (ev (or (second args) {})))
      :await (plan/await* p (first args))
      :emit (plan/emit p (first args) (ev (or (second args) {})))
      :emit-to (let [[target event payload] args]
                 (plan/emit-to p (if (= :self target) (:node-id env) (ev target)) event (ev (or payload {}))))
      :child (plan/child p (ev (first args)) (second args))
      :timeout (plan/timeout-after p (first args) (second args))
      :each (let [[sym coll & ops] args]
              (reduce (fn [p item] (exec-ops (assoc-in env [:vars sym] item) p ops)) p (ev coll)))
      :let (let [[bindings & ops] args
                 env* (reduce (fn [env [sym x]] (assoc-in env [:vars sym] (eval-expr env x))) env bindings)]
             (exec-ops env* p ops))
      :when (let [[cond* & ops] args]
              (if (truthy? (ev cond*)) (exec-ops env p ops) p))
      :if (let [[cond* then else] args]
            (exec-ops env p (if (truthy? (ev cond*)) then else)))
      (throw (ex-info (str "unknown op " kind) {:type ::invalid-plan :op op})))))

(defn- exec-ops [env p ops] (reduce #(exec-op env %1 %2) p ops))

(defn plan-fn
  "A handler function for a transition with a `:plan`: context and event in,
  plan out, the target state the transition's `:to`. Opaque functions come
  from `handlers`, registered by symbol like code handlers."
  [t handlers]
  (fn [ctx ev]
    (let [env {:data (get-in ctx [:node :local-data])
               :payload (:payload ev)
               :node-id (get-in ctx [:node :node-id])
               :vars {}
               :opaques handlers}]
      (-> (exec-ops env (plan/plan) (:plan t))
          (plan/state (:to t))))))

;; --- what runs -------------------------------------------------------------------

(defn name-of
  "The handler name a transition's checkpoints record: its symbol, or for a
  plan without one `plan/<Type>.<from>.<on>`, or the kernel's own mover."
  [{:keys [handler plan node-type from on]}]
  (cond
    handler handler
    plan (symbol "plan" (str node-type "." from "." on))
    :else 'kernel/move))

(defn resolve
  "The function that runs for a transition: its plan, else the function
  registered under its symbol, else nil for a transition that only moves."
  [handlers t]
  (cond
    (:plan t) (plan-fn t handlers)
    (:handler t) (get handlers (:handler t))
    :else nil))

(defn conflicts
  "Symbols registered in `handlers` for transitions that carry a plan: the
  plan runs, the function would not."
  [definition handlers]
  (vec (distinct (for [t (:transitions definition)
                       :when (and (:plan t) (:handler t) (not (:plan-source t)) (contains? handlers (:handler t)))]
                   (:handler t)))))

;; --- static reading: validation and derivation --------------------------------

(def ^:private exprs
  {:data :fields :payload :fields :node-id 0 :counter 1 :var 1 :get 2 :select 2 :count 1 :sum :any :map 2
   :+ 2 :- 2 :* 2 :str :any := 2 :< 2 :<= 2 :> 2 :>= 2 :and :any :or :any :not 1 :nil? 1 :if 3 :opaque :any})

(defn- check-expr
  "Errors in an expression; `scope` is the set of bound variable names."
  [scope x]
  (cond
    (vector? x)
    (let [[op & args] x
          arity (get exprs op)]
      (cond
        (nil? arity) [(str "unknown expression " (pr-str op))]
        (and (= :fields arity) (or (empty? args) (not (every? keyword? args))))
        [(str (pr-str op) " needs keyword fields")]
        (and (integer? arity) (not= arity (count args)))
        [(str (pr-str op) " takes " arity " argument(s)")]
        (= :var op) (when-not (contains? scope (first args)) [(str "unbound variable " (pr-str (first args)))])
        (= :counter op) (when-not (keyword? (first args)) [":counter needs a keyword"])
        (#{:get :map} op) (concat (check-expr scope (first args))
                                  (when-not (keyword? (second args)) [(str (pr-str op) " needs a keyword field")]))
        (= :sum op) (case (count args)
                      2 (concat (check-expr scope (first args))
                                (when-not (keyword? (second args)) [":sum needs a keyword field"]))
                      3 (let [[sym coll term] args]
                          (concat (when-not (keyword? sym) [":sum needs a keyword to bind"])
                                  (check-expr scope coll)
                                  (check-expr (conj scope sym) term)))
                      [":sum takes a collection and a field, or a binding, a collection and a term"])
        (= :select op) (concat (check-expr scope (first args))
                               (when-not (and (vector? (second args)) (every? keyword? (second args)))
                                 [":select needs a vector of keyword fields"]))
        (= :opaque op) (concat (when-not (symbol? (first args)) [":opaque needs a symbol"])
                               (mapcat #(check-expr scope %) (rest args)))
        (= :fields arity) nil
        :else (mapcat #(check-expr scope %) args)))
    (map? x) (mapcat #(check-expr scope %) (vals x))
    (or (string? x) (number? x) (boolean? x) (nil? x) (keyword? x)) nil
    :else [(str "a literal must be a string, number, boolean or nil, not " (pr-str x))]))

(defn- check-ops
  [scope ops]
  (if-not (vector? ops)
    ["ops must be a vector"]
    (mapcat
     (fn [op]
       (if-not (and (vector? op) (keyword? (first op)))
         [(str "an op must be a vector headed by a keyword, not " (pr-str op))]
         (let [[kind & args] op
               path? (fn [p] (and (vector? p) (seq p) (every? keyword? p)))
               payload-errs (fn [m] (when (some? m) (check-expr scope m)))]
           (case kind
             (:set :inc) (concat (when-not (path? (first args)) [(str (pr-str kind) " needs a path of keywords")])
                                 (check-expr scope (second args)))
             (:call :store) (concat (when-not (string? (first args)) [(str (pr-str kind) " needs an effect name")])
                                    (payload-errs (second args)))
             :await (when-not (string? (first args)) [":await needs an event name"])
             :emit (concat (when-not (string? (first args)) [":emit needs an event name"])
                           (payload-errs (second args)))
             :emit-to (let [[target event payload] args]
                        (concat (when-not (= :self target) (check-expr scope target))
                                (when-not (string? event) [":emit-to needs an event name"])
                                (payload-errs payload)))
             :child (concat (check-expr scope (first args))
                            (when-not (string? (second args)) [":child needs a node type"]))
             :timeout (concat (when-not (pos-int? (first args)) [":timeout needs a positive number of milliseconds"])
                              (when-not (string? (second args)) [":timeout needs an event name"]))
             :each (let [[sym coll & body] args]
                     (concat (when-not (keyword? sym) [":each needs a keyword to bind"])
                             (check-expr scope coll)
                             (check-ops (conj scope sym) (vec body))))
             :let (let [[bindings & body] args]
                    (if-not (and (map? bindings) (every? keyword? (keys bindings)))
                      [":let needs a map of keyword to expression"]
                      (concat (mapcat #(check-expr scope %) (vals bindings))
                              (check-ops (into scope (keys bindings)) (vec body)))))
             :when (let [[cond* & body] args]
                     (concat (check-expr scope cond*) (check-ops scope (vec body))))
             :if (let [[cond* then else] args]
                   (concat (check-expr scope cond*)
                           (when-not (vector? then) [":if needs a vector of ops for then"])
                           (when-not (vector? else) [":if needs a vector of ops for else"])
                           (when (vector? then) (check-ops scope then))
                           (when (vector? else) (check-ops scope else))))
             [(str "unknown op " (pr-str kind))]))))
     ops)))

(defn check
  "Structural errors of a transition's plan, as messages."
  [t]
  (vec (check-ops #{} (:plan t))))

(defn- walk-ops
  "Every op in a plan, branches and loops included."
  [ops]
  (mapcat (fn [[kind & args :as op]]
            (case kind
              :each (cons op (walk-ops (vec (drop 2 args))))
              :let (cons op (walk-ops (vec (rest args))))
              :when (cons op (walk-ops (vec (rest args))))
              :if (cons op (walk-ops (into (vec (second args)) (nth args 2))))
              [op]))
          ops))

(defn derive
  "What a plan can do, as the transition keys the compiler, the diagrams and
  the model read: `:emit` (events emitted upward or to the node itself),
  `:effects`, `:await`, `:creates` (child types) and `:commands` (events sent
  to children), each in order of first appearance."
  [{:keys [plan]}]
  (let [ops (walk-ops plan)
        pick (fn [pred f] (vec (distinct (keep #(when (pred %) (f %)) ops))))]
    (cond-> {}
      :always (assoc :emit (pick #(or (= :emit (first %)) (and (= :emit-to (first %)) (= :self (second %))))
                                 #(if (= :emit (first %)) (second %) (nth % 2))))
      :always (assoc :effects (pick #(#{:call :store} (first %)) second))
      :always (assoc :await (pick #(= :await (first %)) second))
      :always (assoc :creates (pick #(= :child (first %)) #(nth % 2)))
      :always (assoc :commands (pick #(and (= :emit-to (first %)) (not= :self (second %))) #(nth % 2)))
      true (->> (remove (fn [[_ v]] (empty? v))) (into {}))
      :always (merge (when-let [[_ ms e] (first (filter #(= :timeout (first %)) ops))]
                       {:timeout {:after-ms ms :event e}})))))

(defn describe
  "A transition's plan as one line, for diagrams and reports."
  [t]
  (str/join " " (map #(pr-str (first %)) (:plan t))))
