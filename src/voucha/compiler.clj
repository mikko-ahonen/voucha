(ns voucha.compiler
  "Compiles profile + domain definitions into an immutable, content-addressed
  execution artifact (design 34, 35).

  The compiler is where hierarchical mistakes are caught. It can reason about
  `Batch` and `Transaction` precisely because they are profile node types
  compiled into the artifact -- not because the kernel knows what they are
  (design 35, invariant 'no kernel logic that special-cases Batch').

  Errors accumulate: one compile reports every problem, each with the specific
  fix. `compile-definition` throws ex-info carrying :errors; `explain` renders
  them for a human."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [voucha.codec :as codec]
            [voucha.constraint :as constraint]
            [voucha.handler :as handler]
            [voucha.invariant :as invariant]
            [voucha.substates :as substates]))

;; --- error accumulation ------------------------------------------------------

(defn- err
  ([errors code message] (err errors code message nil))
  ([errors code message fixes]
   (conj errors (cond-> {:code code :message message}
                  fixes (assoc :fixes fixes)))))

(defn explain
  "Render compiler errors as actionable text (design 35)."
  [errors]
  (str/join "\n\n"
            (map (fn [{:keys [code message fixes]}]
                   (str "[" (name code) "] " message
                        (when (seq fixes)
                          (str "\n\nEither:\n"
                               (str/join "\n" (map #(str "  - " %) fixes))))))
                 errors)))

;; --- accessors ---------------------------------------------------------------

(defn- node-type-names [d] (set (keys (:node-types d))))
(defn- states-of [d nt] (set (get-in d [:node-types nt :states])))
(defn- terminal-states-of [d nt] (set (get-in d [:node-types nt :terminal-states])))
(defn- counters-of [d nt] (set (keys (get-in d [:node-types nt :counters]))))

;; --- structural validation ---------------------------------------------------

(defn- all-state-names
  "Leaves and composites alike: a host may be a composite, which flattening
  keeps only in :state-tree."
  [{:keys [states state-tree]}]
  (letfn [(names [tree] (mapcat #(if (map? %) (cons (:name %) (names (:states %))) [%]) tree))]
    (set (names (or state-tree states)))))

(defn- validate-node-types
  [d errors]
  (let [known (node-type-names d)]
    (reduce
     (fn [errors [nt {:keys [may-contain states initial-state terminal-states failure-state counters] :as spec}]]
       (let [states (set states)
             errors (reduce (fn [errors child]
                              (if (contains? known child)
                                errors
                                (err errors :unknown-node-type
                                     (format "Node type %s may contain %s, which is not defined." nt child)
                                     [(format "define node type %s" child)
                                      (format "remove %s from %s's :may-contain" child nt)])))
                            errors may-contain)
             errors (if (seq states)
                      errors
                      (err errors :unknown-state (format "Node type %s declares no :states." nt)))
             errors (if (contains? states initial-state)
                      errors
                      (err errors :missing-initial-state
                           (format "Node type %s has initial state %s, which is not in :states."
                                   nt (pr-str initial-state))))
             errors (reduce (fn [errors s]
                              (if (contains? states s)
                                errors
                                (err errors :unknown-state
                                     (format "Node type %s lists terminal state %s, which is not in :states." nt s))))
                            errors terminal-states)
             errors (reduce (fn [errors [child host]]
                              (cond-> errors
                                (not (some #{child} may-contain))
                                (err :unknown-node-type
                                     (format "Node type %s hosts %s, which it may not contain." nt child))
                                (not (contains? (all-state-names spec) host))
                                (err :unknown-state
                                     (format "Node type %s hosts %s in state %s, which it does not define." nt child host))))
                            errors (:hosts spec))
             ;; a state may declare events it ignores; it must not also handle them
             errors (reduce (fn [errors [state evs]]
                              (reduce (fn [errors ev]
                                        (cond
                                          (not (contains? (all-state-names spec) state))
                                          (err errors :unknown-state
                                               (format "Node type %s ignores %s in state %s, which it does not define." nt ev state))
                                          (not (contains? (set (:events d)) ev))
                                          (err errors :unknown-event
                                               (format "Node type %s ignores event %s in %s, which is not declared in :events." nt ev state))
                                          (some #(and (= nt (:node-type %)) (= state (:from %)) (= ev (:on %))) (:transitions d))
                                          (err errors :ambiguous-ignore
                                               (format "Node type %s both ignores and handles %s in state %s." nt ev state)
                                               ["remove the event from :ignores" "or remove the transition"])
                                          :else errors))
                                      errors evs))
                            errors (:ignores spec))
             errors (if (contains? (set terminal-states) failure-state)
                      errors
                      (err errors :missing-failure-state
                           (format "Node type %s must declare a :failure-state among its :terminal-states." nt)
                           ["add a terminal failure state (e.g. FAILED) and name it in :failure-state"]))]
         ;; counters must reference a child type this node may contain,
         ;; and states that exist on that child type
         (reduce (fn [errors [cname {:keys [child-type when-child-enters]}]]
                   (cond
                     (not (contains? known child-type))
                     (err errors :unknown-node-type
                          (format "Counter %s on %s watches unknown node type %s." cname nt child-type))

                     (not (contains? (set may-contain) child-type))
                     (err errors :invalid-parent-child-state
                          (format "Counter %s on %s watches %s, which %s may not contain."
                                  cname nt child-type nt))

                     :else
                     (reduce (fn [errors s]
                               (if (contains? (states-of d child-type) s)
                                 errors
                                 (err errors :unknown-state
                                      (format "Counter %s on %s watches %s state %s, which %s does not define."
                                              cname nt child-type s child-type))))
                             errors when-child-enters)))
                 errors counters)))
     errors (:node-types d))))

(defn- validate-child-state-allowances
  "Design 16 and the worked example in design 35: a terminal parent state must
  not permit non-terminal children."
  [d errors]
  (reduce
   (fn [errors [nt {:keys [child-states-allowed terminal-states]}]]
     (let [terminal (set terminal-states)]
       (reduce
        (fn [errors [parent-state by-child]]
          (let [errors (if (contains? (states-of d nt) parent-state)
                         errors
                         (err errors :unknown-state
                              (format "%s :child-states-allowed refers to unknown parent state %s."
                                      nt parent-state)))]
            (reduce
             (fn [errors [child-type allowed]]
               (let [child-states (states-of d child-type)
                     child-terminal (terminal-states-of d child-type)]
                 (cond
                   (empty? child-states)
                   (err errors :unknown-node-type
                        (format "%s.%s allows child states of unknown node type %s."
                                nt parent-state child-type))

                   (seq (set/difference (set allowed) child-states))
                   (err errors :unknown-state
                        (format "%s.%s allows %s state(s) %s, which %s does not define."
                                nt parent-state child-type
                                (str/join ", " (sort (set/difference (set allowed) child-states)))
                                child-type))

                   (and (contains? terminal parent-state)
                        (seq (set/difference (set allowed) child-terminal)))
                   (let [bad (sort (set/difference (set allowed) child-terminal))]
                     (err errors :terminal-parent-allows-nonterminal-child
                          (format "%s.%s is terminal, but allows child %s state(s) %s, which are not terminal."
                                  nt parent-state child-type (str/join ", " bad))
                          [(format "disallow %s.%s while children are %s"
                                   nt parent-state (str/join "/" bad))
                           (format "mark %s as terminal under this parent state"
                                   (str/join "/" bad))
                           "define a cancellation/completion aggregation rule"]))

                   :else errors)))
             errors by-child)))
        errors child-states-allowed)))
   errors (:node-types d)))

;; --- constraints -------------------------------------------------------------

(defn- validate-constraint
  [d nt where c errors]
  (let [t (:constraint c)
        at (format "%s (%s)" where (pr-str c))]
    (cond
      (not (contains? constraint/all-types t))
      (err errors :invalid-constraint
           (format "Unknown constraint type %s in %s. Known types: %s"
                   (pr-str t) at (str/join ", " (sort (map name constraint/all-types)))))

      (= t :counter)
      (cond
        (not (contains? (counters-of d nt) (:counter c)))
        (err errors :missing-counter
             (format "Constraint in %s reads counter %s, which %s does not declare."
                     at (:counter c) nt)
             [(format "declare counter %s in %s's :counters" (:counter c) nt)])
        (not (contains? constraint/comparison-ops (:op c)))
        (err errors :invalid-constraint
             (format "Constraint in %s uses unknown operator %s." at (pr-str (:op c))))
        :else errors)

      (= t :counters-cover-children)
      (reduce (fn [errors cname]
                (if (contains? (counters-of d nt) cname)
                  errors
                  (err errors :missing-counter
                       (format "Constraint in %s reads counter %s, which %s does not declare." at cname nt))))
              errors (:counters c))

      (= t :all-children-in)
      (let [{:keys [child-type states]} c
            matching (keep (fn [[cname spec]]
                             (when (and (= child-type (:child-type spec))
                                        (= (set states) (set (:when-child-enters spec))))
                               cname))
                           (get-in d [:node-types nt :counters]))]
        (cond
          (not (contains? (set (get-in d [:node-types nt :may-contain])) child-type))
          (err errors :invalid-parent-child-state
               (format "Constraint in %s aggregates over %s, which %s may not contain." at child-type nt))
          (empty? matching)
          (err errors :missing-aggregation-rule
               (format "Constraint in %s requires all %s children in %s, but %s declares no counter over exactly those states."
                       at child-type (str/join "/" (sort states)) nt)
               [(format "add a counter to %s: {:child-type %s :when-child-enters %s}"
                        nt (pr-str child-type) (pr-str (vec (sort states))))
                "or use an explicit :counters-cover-children constraint"])
          :else errors))

      (= t :ancestor-state)
      (if (contains? (node-type-names d) (:node-type c))
        errors
        (err errors :unknown-node-type
             (format "Constraint in %s refers to unknown ancestor node type %s." at (:node-type c))))

      :else errors)))

(defn- expand-constraint
  "Rewrite authoring sugar into runtime constraint forms."
  [d nt c]
  (if (= :all-children-in (:constraint c))
    (let [{:keys [child-type states]} c
          cname (some (fn [[cname spec]]
                        (when (and (= child-type (:child-type spec))
                                   (= (set states) (set (:when-child-enters spec))))
                          cname))
                      (get-in d [:node-types nt :counters]))]
      {:constraint :counters-cover-children :counters [cname]})
    c))

;; --- transitions -------------------------------------------------------------

(defn- validate-transitions
  [d errors]
  (let [known-nt (node-type-names d)
        events (set (:events d))
        effects (:effects d)
        declared-policies (set (keys (:policies d)))]
    (reduce
     (fn [errors {:keys [node-type from on to handler requires] :as t}]
       (let [where (format "transition %s.%s -on %s->" node-type from on)]
         (cond
           (not (contains? known-nt node-type))
           (err errors :unknown-node-type
                (format "Transition refers to unknown node type %s." node-type))

           :else
           (let [states (states-of d node-type)
                 errors (if (contains? states from) errors
                            (err errors :unknown-state
                                 (format "%s: from-state %s is not defined on %s." where from node-type)))
                 errors (if (contains? states to) errors
                            (err errors :unknown-state
                                 (format "%s: to-state %s is not defined on %s." where to node-type)))
                 errors (if (contains? events on) errors
                            (err errors :unknown-event
                                 (format "%s: event %s is not declared in :events." where on)))
                 ;; A transition that only moves the node needs no handler; the
                 ;; kernel supplies one. Effects and awaits must come from a plan.
                 errors (if (or handler (:plan t) (not (or (seq (:effects t)) (seq (:await t))))) errors
                          (err errors :missing-handler
                               (format "%s: no :handler declared, but it creates effects or awaits an event." where)))
                 errors (reduce (fn [errors e]
                                  (if (contains? events e)
                                    errors
                                    (err errors :unknown-event
                                         (format "%s: emits event %s, which is not declared in :events." where e))))
                                errors (:emit t))
                 errors (if (contains? (terminal-states-of d node-type) from)
                          (err errors :invalid-transition
                               (format "%s: %s is terminal and may not transition." where from)
                               [(format "remove %s from %s's :terminal-states" from node-type)
                                "or remove this transition"])
                          errors)
                 ;; what the handler may create and command, for the model
                 errors (reduce (fn [errors ct]
                                  (if (some #{ct} (get-in d [:node-types node-type :may-contain]))
                                    errors
                                    (err errors :illegal-child-type
                                         (format "%s: creates %s, which %s may not contain." where ct node-type))))
                                errors (:creates t))
                 errors (reduce (fn [errors e]
                                  (if (contains? events e)
                                    errors
                                    (err errors :unknown-event
                                         (format "%s: commands %s, which is not declared in :events." where e))))
                                errors (:commands t))
                 ;; effect permissions (design 16, 35)
                 permitted (set (get-in d [:node-types node-type :may-emit-effects]))
                 errors (reduce (fn [errors e]
                                  (cond
                                    (not (contains? effects e))
                                    (err errors :unknown-effect
                                         (format "%s: emits undeclared effect %s." where e))
                                    (not (contains? permitted e))
                                    (err errors :effect-not-permitted
                                         (format "%s: emits effect %s, which %s is not permitted to emit."
                                                 where e node-type)
                                         [(format "add %s to %s's :may-emit-effects" e node-type)])
                                    :else errors))
                                errors (:effects t))
                 ;; Awaited events must be producible by the effects this transition creates.
                 ;; Unknown effect names are reported above; skip them here rather than
                 ;; dereferencing a nil spec.
                 declared-completions (into #{}
                                            (comp (keep effects)
                                                  (mapcat (juxt :dispatch-event :ack-event
                                                                :completion-event :failure-event))
                                                  (remove nil?))
                                            (:effects t))
                 timeout-event (get-in t [:timeout :event])
                 errors (reduce (fn [errors a]
                                  (cond
                                    (not (contains? events a))
                                    (err errors :unknown-event
                                         (format "%s: awaits event %s, which is not declared in :events." where a))
                                    (and (seq (:effects t))
                                         (not (contains? declared-completions a)))
                                    (err errors :invalid-effect-completion-mapping
                                         (format "%s: awaits %s, but none of its effects (%s) declare it as an ack, completion or failure event."
                                                 where a (str/join ", " (:effects t)))
                                         [(format "declare %s as :ack-event or :completion-event on the effect" a)
                                          "or await an event the effect actually produces"])
                                    :else errors))
                                errors (:await t))
                 errors (if-let [tm (:timeout t)]
                          (cond
                            (not (pos-int? (:after-ms tm)))
                            (err errors :invalid-timeout-policy
                                 (format "%s: timeout :after-ms must be a positive integer." where))
                            (not (contains? events timeout-event))
                            (err errors :invalid-timeout-policy
                                 (format "%s: timeout event %s is not declared in :events." where timeout-event))
                            :else errors)
                          errors)
             ;; Level-2 policies a transition names must be declared top-level, so
             ;; their versioned identity is pinned in the artifact hash.
             errors (reduce (fn [errors pname]
                              (if (contains? declared-policies pname)
                                errors
                                (err errors :unknown-policy
                                     (format "%s: guards on policy %s, which is not declared in top-level :policies."
                                             where (pr-str pname))
                                     [(format "declare policy %s in the profile's :policies" (pr-str pname))])))
                            errors (:policies t))]
             (reduce (fn [errors c] (validate-constraint d node-type where c errors))
                     errors requires)))))
     errors (:transitions d))))

(defn- validate-transition-determinism
  "Duplicate transitions and ambiguous event routes are the same defect seen from
  two sides: two handlers claiming (node-type, from, event)."
  [d errors]
  (->> (:transitions d)
       (group-by (juxt :node-type :from :on))
       (reduce (fn [errors [[nt from on] ts]]
                 (if (> (count ts) 1)
                   (err errors :duplicate-transition
                        (format "%s.%s has %d transitions on event %s (handlers: %s). The route is ambiguous."
                                nt from (count ts) on
                                (str/join ", " (map #(pr-str (:handler %)) ts)))
                        ["give the transitions distinct :from states"
                         "or distinct :on events"
                         "or merge them into one handler that decides internally"])
                   errors))
               errors)))

(defn- validate-reachability
  [d errors]
  (reduce
   (fn [errors [nt {:keys [states initial-state]}]]
     (let [edges (->> (:transitions d)
                      (filter #(= nt (:node-type %)))
                      (group-by :from))
           reachable (loop [seen #{initial-state} queue [initial-state]]
                       (if-let [s (first queue)]
                         (let [nxt (remove seen (map :to (get edges s)))]
                           (recur (into seen nxt) (into (rest queue) nxt)))
                         seen))
           orphans (set/difference (set states) reachable)]
       (reduce (fn [errors s]
                 (err errors :unreachable-state
                      (format "State %s.%s is unreachable from initial state %s." nt s initial-state)
                      [(format "add a transition into %s" s)
                       (format "or remove %s from %s's :states" s nt)]))
               errors (sort orphans))))
   errors (:node-types d)))

;; --- liveness (decision D14) -------------------------------------------------

(defn- reachable-from
  "Forward closure of states from `from` over one node type's transitions,
  including `from` itself."
  [transitions nt from]
  (let [edges (group-by :from (filter #(= nt (:node-type %)) transitions))]
    (loop [seen #{from} queue [from]]
      (if-let [s (first queue)]
        (let [nxt (remove seen (map :to (get edges s)))]
          (recur (into seen nxt) (into (rest queue) nxt)))
        seen))))

(def ^:private effect-outcome-keys
  [:dispatch-event :ack-event :completion-event :failure-event :timeout-event])

(defn- validate-liveness
  "Stuck-by-construction is a compile error (decision D14).

  A non-terminal state from which no terminal state is reachable strands every
  node that enters it. And every outcome an effect can report -- dispatched,
  acknowledged, completed, failed, timed out -- must be consumable by a
  transition from the state the creating transition lands in, or from a state
  reachable from it; otherwise that outcome arrives, finds no transition, is
  rejected, and the node waits forever for an answer that was thrown away."
  [d errors]
  (let [ts (:transitions d)
        errors (reduce
                (fn [errors [nt {:keys [states terminal-states]}]]
                  (let [terminal (set terminal-states)]
                    (reduce (fn [errors s]
                              (if (or (contains? terminal s)
                                      (some terminal (reachable-from ts nt s)))
                                errors
                                (err errors :dead-end-state
                                     (format "State %s.%s can reach no terminal state; a node entering it is stuck by construction."
                                             nt s)
                                     [(format "add a path from %s to one of %s" s (str/join "/" terminal-states))
                                      (format "or make %s terminal" s)])))
                            errors states)))
                errors (:node-types d))]
    (reduce
     (fn [errors {:keys [node-type from on to effects]}]
       (let [reach (reachable-from ts node-type to)
             handled (into #{} (comp (filter #(and (= node-type (:node-type %)) (contains? reach (:from %))))
                                     (map :on))
                           ts)
             where (format "transition %s.%s -on %s->" node-type from on)]
         (reduce (fn [errors e]
                   (reduce (fn [errors [k ev]]
                             (if (or (nil? ev) (contains? handled ev))
                               errors
                               (err errors :unrouted-effect-outcome
                                    (format "%s creates effect %s, whose %s %s no transition from %s (or a state reachable from it) consumes; that outcome would strand the node."
                                            where e (name k) ev to)
                                    [(format "add a transition on %s from %s or a state reachable from it" ev to)])))
                           errors (select-keys (get-in d [:effects e]) effect-outcome-keys)))
                 errors effects)))
     errors ts)))

;; --- effects -----------------------------------------------------------------

(defn- validate-effects
  [d errors]
  (let [events (set (:events d))]
    (reduce
     (fn [errors [name* {:keys [version kind idempotency-key correlation-key uncertainty-policy
                                adapter completion-event dispatch-event status-query
                                completion-timeout-ms timeout-event participant]}]]
       (let [errors (if version errors
                        (err errors :missing-effect-version
                             (format "Effect %s declares no :version. Execution is version pinned." name*)))
             errors (if (#{:external :store} kind) errors
                        (err errors :invalid-effect
                             (format "Effect %s must declare :kind :external or :store." name*)))
             errors (if (seq idempotency-key) errors
                        (err errors :missing-idempotency-key
                             (format "Effect %s declares no :idempotency-key." name*)
                             ["declare :idempotency-key as a vector of context keys, e.g. [:tree-id :node-id]"]))
             errors (if correlation-key errors
                        (err errors :missing-correlation-key
                             (format "Effect %s declares no :correlation-key; its completion could not be routed." name*)))
             errors (if (#{:retry-safe :query :manual} uncertainty-policy) errors
                        (err errors :missing-uncertainty-policy
                             (format "Effect %s must declare :uncertainty-policy as one of :retry-safe, :query, :manual." name*)
                             ["use :retry-safe if the endpoint is idempotent under the idempotency key"
                              "use :query if the adapter can look up outcome by external request id"
                              "use :manual to park uncertain outcomes for an operator"]))
             errors (if (and (= :query uncertainty-policy) (not status-query))
                      (err errors :missing-status-query
                           (format "Effect %s uses :query uncertainty policy but declares no :status-query operation." name*))
                      errors)
             errors (if (and (= :store kind) (not adapter))
                      (err errors :store-effect-missing-adapter
                           (format "Store effect %s declares no :adapter." name*))
                      errors)
             ;; A participant effect (docs/design/sql-interface.md §3) is carried
             ;; out by whoever claims it through the SQL interface, not by an
             ;; adapter: it is external, and nothing in the worker could query
             ;; its status, so the policy is retry-safe or manual.
             errors (cond
                      (nil? participant) errors

                      (not= :sql participant)
                      (err errors :invalid-participant
                           (format "Effect %s declares :participant %s; the only participant kind is :sql." name* (pr-str participant)))

                      (not= :external kind)
                      (err errors :invalid-participant
                           (format "Effect %s declares a :participant but is not :kind :external." name*))

                      (= :query uncertainty-policy)
                      (err errors :invalid-participant
                           (format "Effect %s declares a :participant with the :query uncertainty policy; no adapter could query it. Use :retry-safe or :manual." name*))

                      :else errors)
             errors (if (and completion-event (not (contains? events completion-event)))
                      (err errors :unknown-event
                           (format "Effect %s declares completion event %s, which is not in :events." name* completion-event))
                      errors)
             errors (if (and dispatch-event (not (contains? events dispatch-event)))
                      (err errors :unknown-event
                           (format "Effect %s declares dispatch event %s, which is not in :events." name* dispatch-event))
                      errors)
             ;; An effect-level completion timeout (design 27) needs both halves: a
             ;; deadline, and an event the artifact can route when it elapses.
             errors (cond
                      (and completion-timeout-ms (not (pos-int? completion-timeout-ms)))
                      (err errors :invalid-timeout-policy
                           (format "Effect %s has :completion-timeout-ms %s; it must be a positive integer."
                                   name* (pr-str completion-timeout-ms)))

                      (and completion-timeout-ms (not timeout-event))
                      (err errors :invalid-timeout-policy
                           (format "Effect %s declares :completion-timeout-ms but no :timeout-event, so the timeout could never fire."
                                   name*)
                           [(format "declare :timeout-event on %s" name*)
                            "or drop :completion-timeout-ms"])

                      (and completion-timeout-ms (not (contains? events timeout-event)))
                      (err errors :unknown-event
                           (format "Effect %s declares timeout event %s, which is not in :events."
                                   name* timeout-event))

                      (and timeout-event (not completion-timeout-ms))
                      (err errors :invalid-timeout-policy
                           (format "Effect %s declares :timeout-event but no :completion-timeout-ms, so the timeout could never fire."
                                   name*)
                           [(format "declare :completion-timeout-ms on %s" name*)
                            "or drop :timeout-event"])

                      :else errors)]
         errors))
     errors (:effects d))))

;; --- policies (Level-2 Decide tier) ------------------------------------------

(defn- validate-policies
  "A Level-2 policy declaration binds a name to a versioned guard (design:
  testing-api.md). Core validates only that the binding is *declared and
  versioned* — the guard function itself is registered at runtime, so its
  existence cannot be checked here; a missing registration fails closed at
  evaluation instead."
  [d errors]
  (reduce
   (fn [errors [pname {:keys [version guard]}]]
     (cond-> errors
       (not (string? pname))
       (err :invalid-policy (format "Policy name %s must be a string." (pr-str pname)))
       (nil? version)
       (err :missing-policy-version
            (format "Policy %s declares no :version. A Decide-tier policy is version pinned into the artifact." pname)
            [(format "declare :version on policy %s" pname)])
       (not (string? guard))
       (err :missing-policy-guard
            (format "Policy %s declares no :guard naming the registered guard function." pname)
            [(format "declare :guard \"<guard-name>\" on policy %s" pname)])))
   errors (:policies d)))

;; --- routing and chain views -------------------------------------------------

(defn- validate-routing
  [d errors]
  (let [known (node-type-names d)
        events (set (:events d))
        errors (reduce (fn [errors {:keys [event from-node-type to-node-type scope on-unsatisfied]}]
                         (cond-> errors
                           (not (contains? events event))
                           (err :unknown-event (format "Routing rule refers to undeclared event %s." event))
                           (and from-node-type (not (contains? known from-node-type)))
                           (err :unknown-node-type (format "Routing rule refers to unknown node type %s." from-node-type))
                           (and to-node-type (not (contains? known to-node-type)))
                           (err :unknown-node-type (format "Routing rule refers to unknown node type %s." to-node-type))
                           (not (#{:local :upward :downward} scope))
                           (err :invalid-routing (format "Routing rule for %s has invalid :scope %s." event (pr-str scope)))
                           (not (#{:reject :ignore nil} on-unsatisfied))
                           (err :invalid-routing
                                (format "Routing rule for %s has invalid :on-unsatisfied %s."
                                        event (pr-str on-unsatisfied)))))
                       errors (:routing d))]
    (->> (:routing d)
         (group-by (juxt :event :from-node-type))
         (reduce (fn [errors [[event nt] rs]]
                   (if (> (count rs) 1)
                     (err errors :ambiguous-event-route
                          (format "Event %s from %s has %d routing rules." event nt (count rs))
                          ["keep one routing rule per (event, source node type)"])
                     errors))
                 errors))))

(defn- validate-invariants
  "The declared business invariants and predicates must be well formed over
  the definition (docs/design/invariants.md)."
  [d errors]
  (reduce (fn [errors m] (err errors :invalid-invariant (str m ".")))
          errors (:errors (invariant/check d))))

(defn- validate-chain-views
  [d errors]
  (reduce
   (fn [errors [view {:keys [node-type steps]}]]
     (if-not (contains? (node-type-names d) node-type)
       (err errors :invalid-chain-step
            (format "Chain view %s refers to unknown node type %s." view node-type))
       (let [states (states-of d node-type)]
         (reduce (fn [errors {:keys [name active-states complete-states]}]
                   (let [bad (set/difference (into (set active-states) complete-states) states)]
                     (cond
                       (str/blank? (str name))
                       (err errors :invalid-chain-step (format "Chain view %s has an unnamed step." view))
                       (empty? complete-states)
                       (err errors :invalid-chain-step
                            (format "Chain view %s step %s declares no :complete-states." view name))
                       (seq bad)
                       (err errors :invalid-chain-step
                            (format "Chain view %s step %s refers to %s state(s) %s, which are not defined."
                                    view name node-type (str/join ", " (sort bad))))
                       :else errors)))
                 errors steps))))
   errors (:chain-views d)))

;; --- compile -----------------------------------------------------------------

(declare validate-flat)

(def ^:private derived-keys [:emit :effects :await :creates :commands])

(defn- validate-plans
  "A transition's plan must be well formed, and what it declares alongside the
  plan must agree with what the plan does (docs/design/handler-ir.md)."
  [d]
  (reduce
   (fn [errors {:keys [node-type from on] :as t}]
     (if-not (:plan t)
       errors
       (let [where (format "transition %s.%s -on %s->" node-type from on)
             problems (handler/check t)]
         (if (seq problems)
           (reduce (fn [errors m] (err errors :invalid-plan (format "%s: %s." where m))) errors problems)
           (let [derived (handler/derive t)]
             ;; a declaration beside a plan must name the same things; the plan's
             ;; order is the one that counts, so the comparison is as sets
             (reduce (fn [errors k]
                       (if (and (contains? t k) (not= (set (get t k)) (set (get derived k []))))
                         (err errors :plan-mismatch
                              (format "%s: declares %s %s, but its plan does %s." where k (pr-str (get t k)) (pr-str (get derived k [])))
                              [(format "drop %s from the transition; it is derived from the plan" k)])
                         errors))
                     errors derived-keys))))))
   [] (:transitions d)))

(defn- derive-plans
  "Transitions with a plan carry what it does as the keys the rest of the
  compiler, the diagrams and the model read."
  [d]
  (update d :transitions (fn [ts] (mapv #(if (:plan %) (merge % (handler/derive %)) %) ts))))

(defn validate
  "Return a vector of compiler errors, empty when the definition is sound.

  Nested states are flattened first (see `voucha.substates`); every check below
  sees leaves only. Plans are checked and their derived keys filled in first,
  so a plan's effects and events are validated like a declaration's."
  [d]
  (let [plan-errors (validate-plans d)
        nesting-errors (substates/validate d)]
    (cond
      (seq plan-errors) plan-errors
      (seq nesting-errors) nesting-errors
      :else (validate-flat (substates/flatten-definition (derive-plans d))))))

(defn- validate-flat
  [d]
  (->> []
       (validate-node-types d)
       (validate-child-state-allowances d)
       (validate-effects d)
       (validate-policies d)
       (validate-transitions d)
       (validate-transition-determinism d)
       (validate-reachability d)
       (validate-liveness d)
       (validate-routing d)
       (validate-chain-views d)
       (validate-invariants d)))

(defn warnings
  "Observations that are usually a typo but are legitimate often enough not to be
  errors.

  An emitted event that no transition consumes and no routing rule mentions can
  reach nothing. Almost always the name is misspelled -- but a fire-and-forget
  audit event that only a downstream reader consumes is a real pattern, so this
  does not fail the compile."
  [d]
  (let [consumed (into #{} (map :on) (:transitions d))
        routed (into #{} (map :event) (:routing d))]
    (into []
          (comp (mapcat (fn [t] (map (fn [e] [t e]) (:emit t))))
                (remove (fn [[_ e]] (or (contains? consumed e) (contains? routed e))))
                (map (fn [[t e]]
                       {:code :unconsumed-event
                        :message (format "transition %s.%s -on %s-> emits %s, which no transition consumes and no routing rule mentions."
                                         (:node-type t) (:from t) (:on t) e)})))
          (:transitions d))))

(defn- expand
  [d]
  (let [leaves-of (fn [nt s]
                    (let [spec (get-in d [:node-types nt])]
                      (substates/leaves-of (substates/index (or (:state-tree spec) (:states spec))) s)))]
    (-> d
        (update :transitions
                (fn [ts]
                  (mapv (fn [t]
                          (update t :requires
                                  #(mapv (partial expand-constraint d (:node-type t)) %)))
                        ts)))
        ;; the business invariants, inlined and over leaves, each with its class
        (assoc :invariants (invariant/compiled d leaves-of))
        (dissoc :predicates))))

(defn compile-definition
  "Compile a definition into {:artifact-id .. :specs .. :warnings ..}.

  The artifact is content-addressed: artifact_id = sha256(canonical specs)
  (design 34). Recompiling identical definitions yields an identical id. Warnings
  ride alongside the artifact rather than inside it: they describe the definition,
  they are not rules the kernel executes, and hashing them would make advice
  change identity."
  [d]
  (let [errors (validate d)]
    (when (seq errors)
      (throw (ex-info (str "Artifact does not compile:\n\n" (explain errors))
                      {:type ::compile-failed :errors errors}))))
  (let [d (substates/flatten-definition (derive-plans d))
        specs (expand d)
        artifact-id (codec/content-hash specs)]
    {:artifact-id artifact-id
     :profile-name (get-in d [:profile :name])
     :profile-version (get-in d [:profile :version])
     :warnings (warnings d)
     :specs (assoc specs :manifest {:artifact-id artifact-id
                                    :profile (:profile d)})}))

;; --- artifact lookup helpers -------------------------------------------------

(defn external-events
  "The events the definition handles but nothing in it produces: not emitted,
  not a command, not an effect's outcome, not an auto request. They come from
  outside, an operator or a caller, and the checkers may inject them."
  [specs]
  (let [ts (:transitions specs)
        produced (into #{} (concat (mapcat :emit ts) (mapcat :commands ts)
                                   (filter :auto (map :on ts))
                                   (for [[_ e] (:effects specs), k [:dispatch-event :ack-event :completion-event :failure-event :timeout-event] :when (k e)] (k e))))]
    (sort (remove produced (set (map :on (remove :auto ts)))))))

(defn transition-for
  "The single transition claiming (node-type, from-state, event), or nil.
  Uniqueness is guaranteed by :duplicate-transition validation."
  [specs node-type from-state event-type]
  (first (filter #(and (= node-type (:node-type %))
                       (= from-state (:from %))
                       (= event-type (:on %)))
                 (:transitions specs))))

(defn node-type-spec [specs nt] (get-in specs [:node-types nt]))

(defn ignores?
  "Whether the node type declares, in `:ignores`, that `state` ignores
  `event-type`: the event is dropped as ignored rather than rejected."
  [specs nt state event-type]
  (contains? (set (get-in specs [:node-types nt :ignores state])) event-type))

(defn terminal?
  [specs nt state]
  (contains? (set (get-in specs [:node-types nt :terminal-states])) state))

(defn effect-spec [specs effect-name] (get-in specs [:effects effect-name]))

(defn auto-transitions
  "Transitions that fire on entering `state`, in declared order. The first
  whose rules hold is taken."
  [specs node-type state]
  (filter #(and (:auto %) (= node-type (:node-type %)) (= state (:from %)))
          (:transitions specs)))

(defn routing-rule
  [specs event-type from-node-type]
  (first (filter #(and (= event-type (:event %))
                       (= from-node-type (:from-node-type %)))
                 (:routing specs))))

(defn counters-watching
  "Counters on `parent-type` that watch `child-type` entering `state`."
  [specs parent-type child-type state]
  (keep (fn [[cname spec]]
          (when (and (= child-type (:child-type spec))
                     (contains? (set (:when-child-enters spec)) state))
            cname))
        (get-in specs [:node-types parent-type :counters])))

(defn child-states-allowed
  [specs parent-type parent-state child-type]
  (get-in specs [:node-types parent-type :child-states-allowed parent-state child-type]))
