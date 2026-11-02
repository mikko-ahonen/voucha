(ns voucha.policy
  "The Level-2 Decide tier: deterministic authorization policy in the kernel
  (design: testing-api.md).

  Level 1 (`voucha.auth`, at `:event/before-submit`) does the I/O — authenticates
  the caller and *freezes* the resolved claims onto the event. Level 2 is what
  runs here: a pure decision over those frozen claims plus the tree context, at
  `:transition/constraints-checked`, in the deterministic zone. Because the inputs
  were frozen at the edge and the decision is pure, an authorization *verdict*
  becomes as auditable and replayable as any hierarchical constraint — 'why was
  this denied in March?' reconstructs from the recorded event plus the pinned
  artifact.

  Core owns the mechanism, never a policy. A profile *declares* its policies by
  name and version (data, hashed into the artifact); an application *registers*
  the guard functions (code) under those names and versions. The two must agree:
  a transition pinned to a guard version that is not registered fails closed and
  says so, which is what makes a version-pinned replay honest rather than silently
  running whatever guard happens to be loaded.

  A guard is `(fn [decision] -> {:ok? bool :reason str :read-set {..}})`, where
  `decision` is {:node :transition :event :ctx :params}. `:event` carries the
  frozen `:actor` and `:claims`. The returned `:read-set` is evidence — what the
  policy looked at — and lands in the transition's checkpoint alongside the
  hierarchical read-set."
  (:require [voucha.intercept :as intercept]))

;; guard-name -> {version -> guard-fn}. An application registers its guards here;
;; core ships none.
(defonce ^:private registry (atom {}))

(defn register!
  "Register a deterministic guard `f` under `guard-name` at `version`.
  Re-registering the same name+version replaces it (safe across REPL reloads)."
  [guard-name version f]
  (assert (string? guard-name) "guard-name must be a string")
  (assert (some? version) "a guard must be versioned")
  (assert (fn? f) "a guard is a function of a decision map")
  (swap! registry assoc-in [guard-name version] f)
  guard-name)

(defn registered
  "The guard registered under `guard-name` at `version`, or nil."
  [guard-name version]
  (get-in @registry [guard-name version]))

(defn unregister!
  "Remove a guard registration (test hygiene)."
  [guard-name version]
  (swap! registry update guard-name dissoc version)
  nil)

(defn- evaluate-one
  [specs pname decision]
  (let [{:keys [version guard params]} (get-in specs [:policies pname])
        f (registered guard version)]
    (if (nil? f)
      ;; Fail closed. The artifact pins a guard version; if that exact version is
      ;; not loaded, the honest answer is 'cannot authorize', never 'authorize
      ;; with whatever guard is around'. This is the pin a replay depends on.
      {:ok? false
       :reason (format "policy %s requires guard %s@%s, which is not registered"
                       pname guard version)
       :evidence {:guard guard :version version :decision "unavailable"}}
      (let [{:keys [ok? reason read-set]} (f (assoc decision :params params))]
        {:ok? (boolean ok?)
         :reason (when-not ok? (or reason (format "policy %s denied" pname)))
         :evidence (assoc (or read-set {}) :decision (if ok? "allow" "deny"))}))))

(defn evaluate-policies
  "Evaluate every policy `transition` declares, over `decision` (a map carrying
  at least {:node :ctx :event}). Pure. Returns the same shape
  `constraint/evaluate-all` returns — {:ok? :failures :read-set} — so a policy
  denial merges with the hierarchical verdict and its evidence with the read-set.

  The evidence for all policies is grouped under a single `:policies` key so it
  cannot collide with a hierarchical read-set entry."
  [specs transition decision]
  (let [pnames (:policies transition)]
    (if (empty? pnames)
      {:ok? true :failures [] :read-set {}}
      (let [results (mapv (fn [pname] (assoc (evaluate-one specs pname decision) :policy pname))
                          pnames)]
        {:ok? (every? :ok? results)
         :failures (into [] (comp (remove :ok?)
                                  (map (fn [r] {:constraint {:policy (:policy r)}
                                                :reason (:reason r)})))
                         results)
         :read-set {:policies (into {} (map (juxt :policy :evidence)) results)}}))))

(defn install-enforcer!
  "Install the single Decide interceptor at `:transition/constraints-checked`.

  It reads the transition's declared policies from the pinned artifact carried on
  the seam, evaluates them over the frozen claims and tree context, and folds any
  denial into the transition's verdict and read-set. Returns an interceptor id.

  Inert for a transition that declares no policies, and a no-op cost when none is
  installed — so a deployment that uses no Level-2 policy pays nothing."
  []
  (intercept/install!
   {:point :transition/constraints-checked
    :tier :decide
    :action (fn [{:keys [specs transition node event ctx failures read-set ok?] :as data}]
              (let [pol (evaluate-policies specs transition
                                           {:node node :event event :ctx ctx})]
                (assoc data
                       :ok? (and ok? (:ok? pol))
                       :failures (into (vec failures) (:failures pol))
                       :read-set (merge read-set (:read-set pol)))))}))
