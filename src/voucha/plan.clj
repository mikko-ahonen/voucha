(ns voucha.plan
  "The developer command model (design 42).

  A transition handler returns a plan. A plan is data: it *decides* that things
  should happen, it does not make them happen (design 23). The kernel turns the
  plan into runtime-owned writes -- a state patch, internal events, durable
  effect intents, timeouts -- all inside one transaction, and dispatchers act on
  the effects only after that transaction commits.

      (-> (plan)
          (state \"ATTEMPT_DEBITING\")
          (call \"account.debit\" {:amount amount})
          (await \"AccountDebited\"))"
  (:require [voucha.patch :as patch]))

(defn plan [] {:patch [] :emit [] :call [] :store [] :await [] :child [] :state nil :timeout nil})

(defn state
  "Target local state for the node."
  [p s] (assoc p :state s))

(defn patch
  "Append state-patch operations against runtime-owned node data."
  [p & ops] (update p :patch into ops))

(defn emit
  "Create an internal event."
  ([p event-type] (emit p event-type {}))
  ([p event-type payload] (update p :emit conj {:event-type event-type :payload payload})))

(defn emit-to
  "Create an internal event addressed at a specific node -- typically a child this
  transition just created (design 31, downward routing). An untargeted `emit`
  bubbles upward instead."
  ([p node-id event-type] (emit-to p node-id event-type {}))
  ([p node-id event-type payload]
   (update p :emit conj {:event-type event-type :payload payload :target-node-id node-id})))

(defn call
  "Create an external effect intent. Performs no I/O."
  [p effect-name request] (update p :call conj {:effect effect-name :request request}))

(defn store
  "Create a store-effect intent. Performs no database write here."
  [p effect-name request] (update p :store conj {:effect effect-name :request request}))

(defn await*
  "Declare the event this node expects next."
  [p event-type] (update p :await conj event-type))

(defn child
  "Create a child node under this node. The kernel checks the artifact's
  containment rules and keeps the parent's :child-count consistent, so an
  aggregate rule can never be satisfied by a child the log never mentions."
  [p node-id node-type]
  (update p :child conj {:node-id node-id :node-type node-type}))

(defn timeout-after
  [p ms event-type] (assoc p :timeout {:after-ms ms :event event-type}))

(defn validate!
  "Structural validation. Artifact-level validation (is this state legal? is this
  effect permitted?) happens in the kernel against the pinned artifact."
  [p]
  (when-not (map? p)
    (throw (ex-info "Transition handler must return a plan" {:type ::invalid-plan :plan p})))
  (when-not (string? (:state p))
    (throw (ex-info "Plan must declare a target :state"
                    {:type ::invalid-plan :plan p})))
  (patch/validate! (vec (:patch p)))
  p)
