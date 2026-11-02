# Definition format

A definition is a Clojure map. `voucha.compiler/compile-definition` turns it into an artifact;
`voucha.compiler/validate` returns the errors without compiling, and `voucha.compiler/explain`
formats them. The bundled example in `voucha.profiles.file-batch-transaction` is a complete
definition.

```clojure
{:profile     {:name "file-batch-transaction" :version "v3"}
 :events      #{"FileReceived" ...}
 :effects     {"screening.check" {...} ...}
 :node-types  {"File" {...} "Batch" {...} ...}
 :transitions [{...} ...]
 :routing     [{...} ...]
 :chain-views {"BatchChain" {...}}
 :policies    {"approver-only" {...}}}        ; optional
```

## `:profile`

`:name` and `:version` identify the definition in the artifact's manifest and on every tree
created from it. The artifact id is a hash of the whole definition, so two definitions that
differ in anything have different ids whatever their version says.

## `:events`

A set of event type names. Every event a transition consumes, emits or awaits, and every effect
outcome event, must be here. Nothing else about an event is declared; its payload is whatever
the sender puts there.

## `:effects`

A map from effect name to its specification.

| Key | Required | Meaning |
|---|---|---|
| `:version` | yes | a string; the effect's version travels with every intent |
| `:kind` | yes | `:external` (an adapter call) or `:store` (a store adapter write) |
| `:adapter` | store only | the store adapter's name |
| `:idempotency-key` | yes | a vector of fields making an intent unique; see below |
| `:correlation-key` | yes | the field a callback can name to find the effect, usually `:node-id` |
| `:uncertainty-policy` | yes | `:retry-safe`, `:query` or `:manual` |
| `:status-query` | query only | the operation the adapter's `query-status` performs |
| `:dispatch-event` | no | delivered when the adapter reports `:dispatched` |
| `:ack-event` | no | delivered when the adapter reports `:acknowledged` |
| `:completion-event` | no | delivered when the adapter reports `:completed` |
| `:failure-event` | no | delivered when the adapter reports `:failed` |
| `:completion-timeout-ms` | with `:timeout-event` | how long an acknowledged or dispatched effect may stay unfinished |
| `:timeout-event` | with `:completion-timeout-ms` | delivered when that elapses |
| `:participant` | no | `:sql`: carried out by whoever claims it through the [SQL interface](sql-interface.md), not by an adapter; external, and not `:query` |

An idempotency key names fields of the effect's context, `:tree-id` and `:node-id`, and fields
of the request the handler passed to `plan/call`. `[:tree-id :node-id]` is one intent per node;
`[:tree-id :node-id :attempt]` lets a node re-issue the effect once per retry cycle while a
replayed transition, which carries the same request, still finds the intent it already created.

Every outcome event an effect declares must be consumed by a transition from the state the
creating transition lands in, or a state reachable from it. Otherwise that outcome would strand
the node, and the compiler says so.

## `:node-types`

A map from node type name to its specification.

| Key | Required | Meaning |
|---|---|---|
| `:states` | yes | every state, as strings, or composite states (below) |
| `:initial-state` | yes | the state a new node starts in |
| `:terminal-states` | yes | states a node never leaves |
| `:failure-state` | yes | one of the terminal states, named as the failure outcome |
| `:may-contain` | yes | child node types; `[]` for a leaf |
| `:may-emit-effects` | yes | effects a transition of this type may create; `[]` for none |
| `:counters` | no | see below |
| `:child-states-allowed` | no | see below |
| `:hosts` | no | `{"Batch" "FILE_PROCESS_BATCH"}`: the state in which this type's children of that type live; diagrams draw the child machine there |
| `:data` | no | the node's data fields and their types, `{:amount :int :error :str :transactions [:set {:tx-id :str :amount :int}]}`: `:int`, `:str`, `:bool`, `[:maybe T]` for a field a plan tests with `:nil?`, `[:set record]` for a collection. With schemas declared the compiler checks the plans against them, and `voucha prove --symbolic` and `--inductive` can generate the typed specification. From Java: `.data(field, type)` with `voucha.sdk.Schema` |
| `:ignores` | no | `{"TX_FAILED" ["TransactionPayCommanded"]}`: events a state ignores. An event with no transition from the node's state is otherwise rejected and recorded as an error; a declared ignore records it as ignored. A composite state stands for its leaves. A state may not both ignore and handle an event |

**Composite states.** An entry in `:states` may be a map `{:name "ORDER_CHARGING" :initial
"ORDER_CHARGE_CALLING" :states [...]}` whose `:states` nest the same way. A node is always in
a leaf state; the composite is a name for all the leaves under it:

- A transition into a composite enters its `:initial`, recursively. The handler's plan names
  that leaf.
- A transition declared from a composite applies in every leaf under it, unless a transition on
  the same event is declared on a more specific state, which wins.
- Anywhere else a state is named, including terminal states, counters, child-state allowances,
  hierarchy rules and chain views, a composite stands for all of its leaves.

This is the hierarchical state machine of statecharts: a sub-state inherits its parent's
transitions and may override one, and the node is always in a leaf. What voucha does not have is
entry and exit actions on states, or a handler that runs and then forwards the event to the
parent; a transition has one handler, and the most specific declaration is the one that runs.

The compiler flattens the definition before checking it, and keeps the tree on the node type as
`:state-tree` for diagrams. A composite without an `:initial` among its states, or a name used
twice, is a compile error.

**Counters.** `{:tx-paid {:child-type "Transaction" :when-child-enters ["TX_COMPLETED"]}}`
declares a counter the kernel keeps on every node of this type: the number of `Transaction`
children currently in one of the listed states. A child leaving a counted state decrements. A
handler reads them at `[:node :local-data :counters :tx-paid]`; it never writes them.

**Child states allowed.** `{"BATCH_COMPLETED" {"Transaction" ["TX_COMPLETED" "TX_FAILED"]}}`
declares which states a child may be in while the parent is in the given state. A terminal
parent state may only allow terminal child states. The invariant oracle checks this rule against
the store; the compiler checks it against the definition.

## `:transitions`

A vector of transition maps. Exactly one transition may claim each (node type, from-state,
event) triple.

| Key | Required | Meaning |
|---|---|---|
| `:node-type`, `:from`, `:on`, `:to` | yes | which node type, in which state, on which event, moves to which state |
| `:plan` | no | the handler as data, below; when present it runs, and `:effects`, `:await`, `:emit`, `:creates`, `:commands` and `:timeout` are derived from it |
| `:handler` | if the transition creates effects or awaits an event and has no `:plan` | a symbol naming the handler in the handlers map, or, beside a `:plan`, the name its checkpoints record; omitted, the kernel supplies one that only moves the node |
| `:auto` | no | `true` for a transition that fires on entering its from-state when its rules hold; see below |
| `:effects` | no | effects the handler may create; each must be in the node type's `:may-emit-effects` |
| `:await` | no | events the node expects next; each must be an outcome event of one of the transition's effects, or any declared event |
| `:emit` | no | events the handler may emit |
| `:creates` | no | child node types the handler creates, e.g. `["Transaction"]`; for the model checker |
| `:commands` | no | events the handler sends to the children it creates, or to every child when it creates none; for the model checker |
| `:requires` | no | hierarchy rules, below |
| `:policies` | no | names from the top-level `:policies` map |

A from-state may not be terminal. The to-state must be one of the node type's states.

**Plans.** A transition's `:plan` is its handler as data: a vector of ops interpreted by the
kernel, with expressions over the node's data and the event's payload. The target state is the
transition's `:to`. The compiler validates a plan without running it and derives from it what
a code handler would have had to declare.

| Op | Meaning |
|---|---|
| `[:set path expr]`, `[:inc path expr]` | `assoc-in` and `inc-in` on node data |
| `[:call effect {k expr}]`, `[:store effect {k expr}]` | create an effect with a request |
| `[:await "Event"]` | await an effect outcome event |
| `[:emit "Event" {k expr}]` | emit, routed upward by the routing rules |
| `[:emit-to :self "Event" {k expr}]`, `[:emit-to expr "Event" {k expr}]` | emit to the node itself, or to a child by id |
| `[:child expr "Type"]` | create a child by id |
| `[:timeout ms "Event"]` | arm a timeout |
| `[:each :x expr op...]` | ops for each element of a collection, bound as `[:var :x]` |
| `[:let {:x expr} op...]` | bind values once |
| `[:when expr op...]`, `[:if expr [op...] [op...]]` | plan-level branching |

| Expression | Meaning |
|---|---|
| a string, number, boolean or nil | itself; a map is a map of expressions |
| `[:data k & ks]`, `[:payload k & ks]` | a field of node data or of the event payload, nil when absent |
| `[:node-id]`, `[:counter :name]` | the node's id; a kernel-maintained counter, 0 when absent |
| `[:var :x]`, `[:get expr :k]`, `[:select expr [k...]]` | a binding; a field of a record; a sub-record |
| `[:count expr]`, `[:sum expr :k]`, `[:sum :x expr term]`, `[:map expr :k]` | over a collection: its size, the sum of a field, the sum of a term over each element bound as `[:var :x]`, the field of each |
| `[:+ a b]`, `[:- a b]`, `[:* a b]`, `[:str a ...]` | arithmetic; string concatenation |
| `[:if c a b]` | a value chosen by a condition; names of events, effects and types may not be chosen this way |
| `[:= a b]`, `[:< a b]`, `[:<= a b]`, `[:> a b]`, `[:>= a b]` | comparisons; an ordering on a non-number is false |
| `[:and ...]`, `[:or ...]`, `[:not a]`, `[:nil? a]` | logic; `:or` yields the first truthy value, so it also serves as a default |
| `[:opaque 'ns/sym expr...]` | a pure function registered in the handlers map under that symbol |

Names of events, effects and child types are literals, never computed. A plan that only moves
the node is `[]`. The SEPA example is written entirely as plans; see `voucha.profiles.file-batch-transaction`.
A plan read from a Java method by the SDK carries `:plan-source "java"`.

### Auto transitions

An auto transition is taken by the kernel, not by an event from outside. On entering a state,
its auto transitions are evaluated in declared order, and the first whose rules hold is taken;
the kernel raises `:on` as the event, so the log reads like any other transition. If none holds,
nothing happens. A decision state is therefore a set of auto transitions with exclusive rules,
usually ending with one that has no rules at all. The rules are evaluated again every time the
state is entered, including a transition from the state to itself, which is how a parent
re-decides when another child settles. Only one such decision request is kept pending per node,
and it decides from the counters as they are when it is processed, so a decision cannot go
stale while it waits.

### Rules (`:requires`)

| Rule | Meaning |
|---|---|
| `{:constraint :parent-state :allowed [...]}` | the parent must be in one of these states |
| `{:constraint :parent-state :forbidden [...]}` | and must not be in one of these |
| `{:constraint :ancestor-state :node-type "File" :allowed [...]}` or `:forbidden` | the same for the nearest ancestor of that type |
| `{:constraint :counter :counter :tx-failed :op :<= :value 5}` | a counter on this node compared with a value; `:op` is one of `:=`, `:<`, `:<=`, `:>`, `:>=` |
| `{:constraint :counters-cover-children :counters [:tx-paid :tx-failed]}` | the named counters on this node sum to its child count |
| `{:constraint :no-outstanding-effects :scope :node}` or `:subtree` | no live effect owned by this node, or by any node under it |

A rule that fails rejects the event, unless the event reached this node by upward routing with
`:on-unsatisfied :ignore`, in which case the event is ignored. Every failing rule is reported,
and `voucha why` shows the same text.

## `:payloads`

Optional: the payload fields of each event and their types, in the same form as `:data`,
`{"FileReceived" {:name :str :batches [:set batch-record]} ...}`. Needed, with `:data`, for the
typed specification. From Java: `.payload(event, field, type, ...)`.

## `:invariants` and `:predicates`

Optional: business invariants, properties of every tree beyond what the framework checks,
stated once in the plan IR's expression language over what the kernel records, the nodes and
the effects (design: [invariants.md](../design/invariants.md)). A property is a boolean form
over collections `[:nodes "Type" :under e | :children-of e | :in ["S" ...]]` and
`[:effects "name" :owner e | :owner-under e | :status ["COMPLETED" ...]]`, with the quantifiers
`:forall`, `:exists`, `:none`, `:at-most-one`, `:exactly-one` (`[:forall :x coll body]`; the
body is optional except for `:forall`), `:implies`, the reads `:state-of`, `:type-of`,
`:root?`, `:parent-of` (guarded by `:root?`), `:data-of`, `:counter-of`, `:owner-of`,
`:status-of`, `:effect-of`, the cardinalities `:count` and `:sum`, `:when-quiescent`, and the
plan expressions `:=`, `:<`, `:<=`, `:>`, `:>=`, `:+`, `:-`, `:*`, `:and`, `:or`, `:not`,
`:nil?`, `:if`. `:predicates` names recurring questions, `{:debited? [:fn :a body]}`, used as
`[:pred :debited? [:var :a]]` and inlined by the compiler.

```clojure
:invariants
{:paid-means-one-success
 [:forall :tx [:nodes "Transaction" :in ["TX_COMPLETED"]]
  [:exactly-one :a [:nodes "Attempt" :children-of [:var :tx] :in ["ATTEMPT_SUCCEEDED"]]]]}
```

The compiler checks every type, state, effect, status (the kernel's effect instance statuses,
and `LOST` for the models), field (against the `:data` schemas when declared), counter,
variable and predicate, and derives each property's class: *provable*, over the tree, the
effects and scalar fields with quantifiers, which the typed specification carries and the
induction proves; or *checkable*, with a count, a sum, a collection field or
`:when-quiescent`, which the simulator, the models and the runtime oracle check. The artifact
carries the properties inlined, over leaf states, with their class. From Java:
`.invariant(name, edn)` and `.predicate(name, edn)` on the profile builder. Where each is
checked, and the status of the implementation: the design.

## `:routing`

How an event a child emits without a target reaches its parent.

```clojure
{:event "TransactionPaid" :from-node-type "Transaction" :to-node-type "Batch"
 :scope :upward :on-unsatisfied :ignore}
```

`:on-unsatisfied :ignore` means a rule failure on the parent's transition is not an error: the
parent is not ready yet, and a later child's event will satisfy it. Without it the event would be
rejected. The compiler rejects an event with more than one route from the same node type.

## `:chain-views`

A named progress view over one node type, shown by `voucha chain` and the admin API.

```clojure
{"BatchChain"
 {:node-type "Batch"
  :steps [{:name "Validate" :active-states ["BATCH_NEW" "BATCH_VALIDATING"]
           :complete-states ["BATCH_PROCESSING" "BATCH_COMPLETED"]
           :failed-states ["BATCH_REJECTED"]}
          {:name "Process" :active-states ["BATCH_SCREEN_TRANSACTIONS" "BATCH_PROCESSING" "BATCH_PAY_TRANSACTIONS"]
           :complete-states ["BATCH_COMPLETED"] :progress-counters [:tx-ready :tx-paid :tx-failed]}]}}
```

A step is complete, active, failed or pending according to the node's state; `:progress-counters`
adds the named counters and the child count to an active step.

## `:policies`

Authorization guards a transition may name. `{"approver-only" {:version "v1" :guard "actor-in-role"
:params {:role "approver"}}}` declares a guard the application registers with
`voucha.policy/register!`. The guard's name and version are pinned into the artifact; an
unregistered pinned version fails closed.

## Layering a domain on a profile

`voucha.profile/derive-definition` merges a domain map over a profile definition: events are
unioned; a node type's `:states`, `:terminal-states`, `:may-contain` and `:may-emit-effects`
are extended; its `:counters` and `:child-states-allowed` are merged; transitions, effects,
routing and chain views are added. A domain can add a state and the transitions into and out of
it without restating the profile's lifecycle. `voucha init` generates a project in this shape.

## Compiler errors

Each error has a code, a message naming the node type, state, transition or effect, and in
most cases the fixes to choose from.

| Code | Means |
|---|---|
| `:unknown-node-type` | a `:may-contain`, counter, transition, rule or routing names a node type that is not defined |
| `:unknown-state` | a state is used that the node type does not declare, or a node type declares no states |
| `:unknown-event` | an event is consumed, emitted or awaited that `:events` does not declare |
| `:unknown-effect`, `:invalid-effect` | an effect is named that `:effects` does not declare, or its spec is malformed |
| `:missing-initial-state`, `:missing-failure-state` | the node type lacks one, or names one outside its states |
| `:missing-counter` | a rule reads a counter the node type does not declare |
| `:missing-aggregation-rule` | a `:counters-cover-children` rule names states no counter covers |
| `:invalid-constraint` | a rule of unknown type or with an unknown operator |
| `:invalid-parent-child-state` | `:child-states-allowed` names an unknown state or node type |
| `:terminal-parent-allows-nonterminal-child` | a terminal parent state permits a live child |
| `:invalid-transition` | a transition from a terminal state |
| `:ambiguous-ignore` | a state both ignores an event in `:ignores` and has a transition on it |
| `:invalid-plan` | a plan with an unknown op or expression, a bad path, an unbound variable, or a branch that is not a vector of ops |
| `:plan-mismatch` | a transition declares `:emit`, `:effects`, `:await`, `:creates` or `:commands` beside a plan that does something else |
| `:illegal-child-type` | a transition's `:creates` names a type the node may not contain |
| `:duplicate-transition`, `:ambiguous-event-route` | two transitions or two routes claim the same event |
| `:missing-handler` | a transition names no handler |
| `:effect-not-permitted` | a transition creates an effect its node type may not emit |
| `:invalid-effect-completion-mapping` | a transition awaits an event none of its effects can produce |
| `:missing-effect-version`, `:missing-idempotency-key`, `:missing-correlation-key`, `:missing-uncertainty-policy`, `:missing-status-query`, `:store-effect-missing-adapter` | an effect spec lacks a required key |
| `:invalid-timeout-policy` | a timeout with no event, no duration, or an undeclared event |
| `:invalid-participant` | a `:participant` other than `:sql`, on a store effect, or with the `:query` policy |
| `:unreachable-state` | no transition leads to the state |
| `:dead-end-state` | no terminal state can be reached from the state |
| `:unrouted-effect-outcome` | an effect outcome no reachable transition consumes |
| `:invalid-chain-step` | a chain view step is unnamed or names an unknown state |
| `:unknown-policy` | a transition names a policy not declared in `:policies` |
| `:invalid-composite-state`, `:duplicate-state` | a composite without states or an initial among them, or a state name declared twice |

Warnings, which compile but are printed by `voucha compile`, cover things such as a declared
event nothing consumes.
