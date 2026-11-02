# SDK guide

How to write a process, task by task. Clojure first; the Java section at the end shows the same
things in Java. Every example here is drawn from the bundled SEPA example in
`voucha.example.batch` and `voucha.profiles.file-batch-transaction`, which is the best place to
look for a complete, working process.

## The shape of a handler

A handler receives a context and an event and returns a plan.

```clojure
(ns my.handlers
  (:require [voucha.plan :as plan]))

(defn- node-id [ctx] (get-in ctx [:node :node-id]))
(defn- data    [ctx] (get-in ctx [:node :local-data]))
(defn- payload [ev k] (get-in ev [:payload k]))

(defn start-screening [ctx _ev]
  (-> (plan/plan)
      (plan/state "TX_SCREENING")
      (plan/call "screening.check" {:tx-id (node-id ctx) :amount (:amount (data ctx))})
      (plan/await* "TransactionScreeningCleared")))
```

The context holds:

| Key | Contents |
|---|---|
| `[:node :node-id]`, `[:node :node-type]`, `[:node :local-state]` | the node this event is addressed to |
| `[:node :local-data]` | its data, including `:counters` and `:child-count` maintained by the kernel |
| `[:parent ...]` | the parent node, same shape, when the transition's rules read it |
| `[:ancestors "File"]` | ancestors by node type, when the rules read them |
| `[:facts]` | the transaction time and an id generator, both deterministic |

The event holds `:event-type`, `:payload`, `:event-id`, and `:operator-id` and
`:operator-reason` when a person submitted it.

Handlers are registered under the symbol the definition names:

```clojure
(def handlers
  {'tx/start-screening start-screening
   ...})
```

Every transition in the definition must have a handler, or the kernel refuses the event.

## Handlers as data

The handler above can be written on the transition itself, as a plan:

```clojure
{:node-type "Transaction" :from "TX_VALIDATED" :on "TransactionScreeningStart" :to "TX_SCREENING"
 :handler 'tx/start-screening
 :plan [[:call "screening.check" {:tx-id [:node-id] :amount [:data :amount]}]
        [:await "TransactionScreeningCleared"]]}
```

A plan is the plan API as data: the same ops, with expressions over the node's data and the
event's payload in place of code, and `:when`, `:if`, `:each` and `:let` for branching, loops
over a payload collection and bindings. The target state is the transition's `:to`. The symbol
beside it is only the name its checkpoints record; nothing needs to be registered. The full
vocabulary is in the [definition reference](reference/definition.md#transitions).

Three things follow from a handler being data. The compiler checks it without running it:
every event it can emit, every effect it can create, every child type it can create, and
reports a plan that could do something the definition forbids. The plan is in the artifact, so
a change to handler logic changes the artifact id, is pinned per tree, and replays under its
own rules. And the model checker sees exactly what the handler does, rather than every way a
code handler might behave.

Write plans by default. Where logic does not fit, call a registered pure function with
`[:opaque 'my/predicate ...]`, which the model treats as any outcome, or keep a code handler
for that transition. The SEPA example is written entirely as plans; the order example, in
Java, is written as code.

## What a plan can say

| Call | Effect |
|---|---|
| `(plan/state s)` | the node's new state; required |
| `(plan/patch op ...)` | change the node data |
| `(plan/emit event)` | send an event upward to the parent that handles it |
| `(plan/emit-to node-id event payload)` | send an event to a specific node, usually a child or the node itself |
| `(plan/call effect request)` | request an external effect |
| `(plan/store effect request)` | request a store effect |
| `(plan/child node-id node-type)` | create a child |
| `(plan/await* event)` | declare which event this node now expects |
| `(plan/timeout-after ms event)` | deliver `event` to this node if it is still in the new state after `ms` |

Patches are a closed vocabulary with literal arguments, so they can be stored and replayed:

```clojure
(plan/patch p
  (list 'assoc-in  [:amount] 100)
  (list 'inc-in    [:attempts] 1)
  (list 'conj-in   [:errors] {:code "AC04"})
  (list 'dissoc-in [:scratch]))
```

## Rules for handlers

A handler must be deterministic. It may not read a clock, generate a random number, read a
database, call a service, read the environment or start a thread. The reason is replay: the
platform proves a handler is still correct by running it again on its recorded input, and that
only works if the output depends on nothing else.

| Instead of | Use |
|---|---|
| `(Instant/now)` | `(facts/transaction-time (:facts ctx))` |
| `(random-uuid)` | `(facts/next-id (:facts ctx))` |
| reading the database | put it in the event payload, or model a store effect |
| calling a service | `plan/call` |

`voucha.lint` scans a namespace for the forbidden constructs. Deterministic replay, run by
`voucha verify` or in a test, is the authoritative check.

## Starting a child and telling it what to do

A parent creates children in its plan and commands them in the same plan. The child does not
exist when the plan is written, but the kernel creates it before delivering the event.

```clojure
(defn batch-prepare [ctx _ev]
  (let [txns (:transactions (data ctx))]
    (reduce (fn [p {:keys [tx-id] :as t}]
              (-> p
                  (plan/child tx-id "Transaction")
                  (plan/emit-to tx-id "TransactionStartCommanded" t)))
            (-> (plan/plan)
                (plan/state "BATCH_SCREEN_TRANSACTIONS")
                (plan/patch (list 'assoc-in [:children] (mapv :tx-id txns))))
            txns)))
```

Recording the children's ids in the parent's data is a convention, not a requirement. It lets
a later transition fan a command out to every child, as the example does when it tells every
screened transaction to pay.

## Waiting for children

Declare counters on the parent's node type:

```clojure
"Batch" {:counters {:tx-ready  {:child-type "Transaction" :when-child-enters ["TX_SCREENED"]}
                    :tx-paid   {:child-type "Transaction" :when-child-enters ["TX_COMPLETED"]}
                    :tx-failed {:child-type "Transaction" :when-child-enters ["TX_FAILED"]}}}
```

Have the child bubble an event when it reaches the state the parent cares about:

```clojure
(defn screening-cleared [_ctx _ev]
  (-> (plan/plan) (plan/state "TX_SCREENED") (plan/emit "TransactionReady")))
```

Route the event upward, and make the question "are they all done?" a **decision state**: a
state whose outgoing transitions are marked `:auto` and fire on entry, the first whose rules
hold. The children run while the parent waits in a second state, and each child's event brings
the parent back to the decision:

```clojure
:routing [{:event "TransactionReady" :from-node-type "Transaction" :to-node-type "Batch"
           :scope :upward :on-unsatisfied :ignore}]

:transitions
[;; the decision, taken on entry from the kernel's counters
 {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "AllTransactionsScreened" :to "BATCH_PAY_TRANSACTIONS"
  :auto true
  :requires [{:constraint :counters-cover-children :counters [:tx-ready :tx-failed]}]
  :handler 'batch/start-paying}
 {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "ScreeningPending" :to "BATCH_PROCESSING"
  :auto true}
 ;; a child settling brings the batch back to decide; one settling while it is
 ;; already deciding makes it decide again
 {:node-type "Batch" :from "BATCH_PROCESSING" :on "TransactionReady" :to "BATCH_SCREEN_TRANSACTIONS"}
 {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "TransactionReady" :to "BATCH_SCREEN_TRANSACTIONS"}]
```

A transition that only moves the node needs no handler; the kernel supplies one. The decision's
rules are evaluated when the state is entered and again on every re-entry, so a batch with no
transactions, or one whose every transaction has already failed, passes straight through: there
is no "nothing to wait for" case to write in code. The handler that does run, `batch/start-paying`,
reads the counters it needs from `[:node :local-data :counters]`. Never increment a counter
yourself.

Declare where the children live with `:hosts {"Transaction" "BATCH_PROCESSING"}` on the node
type, so diagrams draw the child machine inside that state.

## Guarding a transition with the hierarchy

A transition may require things of its ancestors:

```clojure
{:constraint :parent-state   :allowed ["BATCH_PROCESSING" "BATCH_PAY_TRANSACTIONS"]}   ; or :forbidden [...]
{:constraint :ancestor-state :node-type "File" :forbidden ["FILE_PERMANENT_FAILURE"]}
{:constraint :counter        :counter :tx-failed :op :<= :value 5}
{:constraint :counters-cover-children :counters [:tx-paid :tx-failed]}
{:constraint :no-outstanding-effects  :scope :node}                     ; or :subtree
```

The kernel loads and locks exactly the ancestors a transition's rules mention, so a parent cannot
move between the check and the commit. When a rule fails, the event is rejected with every
failing rule named, and `voucha why` shows the same text.

## Calling an external system

Declare the effect:

```clojure
"rail.sct.submit"
{:version "v1" :kind :external
 :idempotency-key [:tree-id :node-id] :correlation-key :node-id
 :uncertainty-policy :query :status-query "rail.sct.status"
 :completion-event "SctPaymentAccepted"
 :failure-event    "SctPaymentRejected"}
```

Permit the node type to emit it, request it from a transition that declares it, and consume
every outcome event from the state the transition lands in or a state reachable from it. The
compiler checks all three.

```clojure
"Attempt" {:may-emit-effects ["rail.sct.submit" ...]}

{:node-type "Attempt" :from "ATTEMPT_DEBITED" :on "AttemptSubmitSct" :to "ATTEMPT_SCT_SUBMITTING"
 :handler 'attempt/submit-sct
 :effects ["rail.sct.submit"] :await ["SctPaymentAccepted"]}
{:node-type "Attempt" :from "ATTEMPT_SCT_SUBMITTING" :on "SctPaymentAccepted" :to "ATTEMPT_SUCCEEDED" ...}
{:node-type "Attempt" :from "ATTEMPT_SCT_SUBMITTING" :on "SctPaymentRejected" :to "ATTEMPT_REVERSING" ...}
```

Put everything the adapter will need into the request. The adapter is handed the intent as it
was written, not the node's data at dispatch time:

```clojure
(defn submit-sct [ctx _ev]
  (-> (plan/plan)
      (plan/state "ATTEMPT_SCT_SUBMITTING")
      (plan/call "rail.sct.submit" {:amount (:amount (data ctx)) :attempt (node-id ctx)})
      (plan/await* "SctPaymentAccepted")))
```

The outcome arrives as the completion or failure event, with the adapter's payload as the
event payload. Record what you need from it in the node:

```clojure
(defn succeeded [_ctx ev]
  (-> (plan/plan) (plan/state "ATTEMPT_SUCCEEDED")
      (plan/patch (list 'assoc-in [:rail-ref] (payload ev :rail-ref)))
      (plan/emit "AttemptSucceeded")))
```

## Choosing the uncertainty policy

The policy answers one question: if the call throws after the request has left, what is safe?

- **`:retry-safe`** when the other side deduplicates on your idempotency key, or the call is
  naturally idempotent. The runtime resends under the same key after a backoff.
- **`:query`** when a resend could do the work twice, as with a payment. The runtime calls the
  adapter's `query-status` instead. You must name a `:status-query` operation. If the other side
  cannot say yet, the effect waits in `DISPATCHED` and is asked again later.
- **`:manual`** when neither is safe. The effect is parked with `requires-operator` set, and an
  operator delivers the outcome once they have found out.

The idempotency key names fields of the effect's context and request. `[:tree-id :node-id]` means
one intent per node. Add a request field when a node legitimately re-issues the same effect in a
retry cycle, so each cycle is a distinct intent:

```clojure
:idempotency-key [:tree-id :node-id :attempt]
;; and in the handler:
(plan/call "screening.check" {... :attempt (get (data ctx) :screening-retries 0)})
```

## Retrying, and composite states

A retry is a loop in the state machine, not a loop in code. The example's screening:

```
TX_VALIDATED --TransactionScreeningStart--> TX_SCREENING          (screening.check)
TX_SCREENING --TransactionScreeningFailed--> TX_SCREEN_RETRY_WAIT
TX_SCREEN_RETRY_WAIT --TransactionScreeningRetry--> TX_SCREENING   (new screening.check)
TX_SCREEN_RETRY_WAIT --TransactionScreeningGaveUp--> TX_FAILED
TX_SCREENING --TransactionCancelled--> TX_FAILED
TX_SCREEN_RETRY_WAIT --TransactionCancelled--> TX_FAILED
```

When several states share transitions, a composite state declares them once. The `:states` of a
node type may nest: `{:name "ORDER_CHARGING" :initial "ORDER_CHARGE_CALLING" :states
["ORDER_CHARGE_CALLING" "ORDER_CHARGE_RETRY_WAIT"]}` is one entry among the plain state names. A
transition into the composite enters its initial sub-state, which is what the handler's plan
names; a transition declared from the composite applies in every sub-state unless a more specific
one is declared; a rule elsewhere that names the composite admits every state under it. The
runtime only ever sees the leaves.

Start flat and add a composite when a pattern repeats: several states that share a transition,
or a group of states a rule elsewhere should name as one. Keep the nesting shallow and give each
composite one purpose. If the composite needs to announce that it is finished, its last sub-state
emits an event to the node; there is no implicit completion of a composite.

The deciding handler counts in the node data and emits one of the two events to its own node:

```clojure
(defn screening-decide [ctx _ev]
  (let [n (get (data ctx) :screening-retries 0)]
    (if (< n max-retries)
      (-> (plan/plan) (plan/state "TX_SCREEN_RETRY_WAIT")
          (plan/patch (list 'inc-in [:screening-retries] 1))
          (plan/emit-to (node-id ctx) "TransactionScreeningRetry"))
      (-> (plan/plan) (plan/state "TX_SCREEN_RETRY_WAIT")
          (plan/emit-to (node-id ctx) "TransactionScreeningGaveUp")))))
```

Because the wait state and both ways out are declared, `voucha why` can explain a transaction
parked there, and the compiler guarantees the loop can end.

## Compensating

There is no compensation mechanism, because a compensating action is just another effect. The
example debits the debtor account before sending, and reverses the debit if the rail rejects:

```
ATTEMPT_SCT_SUBMITTING --SctPaymentRejected--> ATTEMPT_REVERSING   (account.reverse)
ATTEMPT_REVERSING --DebitReversed--> ATTEMPT_FAILED
ATTEMPT_REVERSING --DebitReversalFailed--> ATTEMPT_FAILED          (flagged for reconciliation)
```

Note the second failure transition. A reversal that fails must still end the attempt, or the
tree is dead; the handler records that the debit needs reconciling and moves on.

## Timeouts

A transition can arm a timeout that fires if the node is still in the new state after a delay:

```clojure
(plan/timeout-after (* 15 60 1000) "ScreeningTimedOut")
```

Leaving the state disarms it. For an effect that was acknowledged and then never completed,
declare a completion timeout on the effect itself, with `:completion-timeout-ms` and a
`:timeout-event`; that one is not disarmed by the node moving on.

## Writing an adapter

An adapter implements `voucha.adapter/EffectAdapter`, and reports an outcome. It never touches
node state.

```clojure
(reify adapter/EffectAdapter
  (dispatch [_ effect]
    (let [resp (http/post url {:body (:request-payload effect)
                               :headers {"Idempotency-Key" (:idempotency-key effect)}})]
      (case (:status resp)
        200 {:outcome :completed :payload (:body resp)}
        422 {:outcome :failed :payload {:error (:body resp)}}
        (throw (ex-info "unknown outcome" {:status (:status resp)})))))
  (query-status [_ effect]
    ...))
```

| Return | Meaning | Effect status |
|---|---|---|
| `{:outcome :completed}` | the work is done | `COMPLETED` |
| `{:outcome :acknowledged :external-request-id id}` | accepted, not finished | `ACKNOWLEDGED` |
| `{:outcome :dispatched}` | sent; nothing came back | `DISPATCHED` |
| `{:outcome :failed}` | a definite refusal | `FAILED` |
| throws | unknown; it may or may not have landed | per uncertainty policy |

The last two rows are the ones to get right. Return `:failed` only when the other side gave a
definite answer, such as a 4xx. Throw when the request left and you cannot say what happened,
such as a timeout or a 5xx. They are not interchangeable: `:failed` drives the failure event,
while an unknown outcome is resolved by the policy.

For a system that answers later, on another channel, deliver the answer through the dispatcher:

```clojure
(dispatcher/deliver-callback! ds {:tree-id tree-id
                                  :correlation-key attempt-id        ; or :external-request-id
                                  :effect-name "rail.inst.send"
                                  :outcome :completed
                                  :payload {:rail-ref "INST-0001"}})
```

A store adapter implements `StoreAdapter` and returns `{:target .. :record-key .. :payload ..}`;
the kernel records the write under a unique key so a repeat is a no-op.

`voucha.example.integrations` has a REST service and a message broker with a queue consumer,
both behaving the way real ones do, including the parts that make integration hard.

## Assembling a runtime

```clojure
(def rt (runtime/runtime {:handlers        my.handlers/handlers
                          :effect-adapters {"rail.sct.submit" (sct-adapter ...)
                                            "screening.check" (screening-adapter ...)}
                          :store-adapters  {"file.writeReceive" (file-store ...)}
                          :config          (config/load-config)}))

(def stop (runtime/start-workers! ds rt))   ; a kernel loop and a dispatcher loop
```

Workers claim work through the database, so you can run as many as you like against one
PostgreSQL. Compile and store the definition once with `voucha compile`, create a tree with
`kernel/create-tree!`, and submit events with `kernel/submit-event!` or the admin API.

## Authorization

Two levels, because of the determinism boundary. At the edge, before an event is persisted,
`voucha.auth` resolves who the caller is and freezes their claims onto the event. Inside the
transition, `voucha.policy` evaluates a deterministic guard over those frozen claims. A
transition names its policies:

```clojure
:policies {"approver-only" {:version "v1" :guard "actor-in-role" :params {:role "approver"}}}
{:node-type "File" :from "FILE_AWAITING_APPROVAL" :on "FileApproved" ... :policies ["approver-only"]}
```

and the application registers the guard with `policy/register!` and installs the enforcer with
`policy/install-enforcer!`. A denial is recorded on the event; a grant leaves its evidence in
the checkpoint, so it replays like any other rule.

## Java

The Java SDK is an authoring surface over the same core. A `ProcessProfile` declares the node
types, events, effects and routes; `@Transition`-annotated methods declare the transitions and
carry their handlers; `Plan.build()` returns the same plan data the Clojure API produces. The
kernel cannot tell which language wrote a process. The order example,
`java-src/example/OrderProfile.java` and `OrderHandlers.java`, is a complete process in Java.

The definition:

```java
public final class OrderProfile implements ProcessProfile {
    public void define(ProfileBuilder p) {
        p.profile("order", "v1")
         .events("OrderPlaced", "OrderValid", "ItemSettled", "PaymentCharged", ...)
         .effect(EffectSpec.external("payment.charge")
                     .query("payment.status")          // never charge twice: ask instead
                     .onCompletion("PaymentCharged")
                     .onFailure("PaymentDeclined"))
         .nodeType("Order")
             .mayContain("Item")
             .states("ORDER_NEW", "ORDER_RESERVING", ..., "ORDER_COMPLETED", "ORDER_CANCELLED")
             .initialState("ORDER_NEW")
             .terminalStates("ORDER_COMPLETED", "ORDER_CANCELLED", ...)
             .failureState("ORDER_CANCELLED")
             .mayEmitEffects("payment.charge", ...)
             .counter("items-reserved", "Item", "ITEM_RESERVED")
         .nodeType("Item")
             ...
         .routeUpward("ItemSettled", "Item", "Order");
    }
}
```

`composite(name, initial, subStates...)` declares a composite state in the builder's state list,
among the plain names given to `states(...)`; `ignores(state, events...)` declares events a state
drops as ignored rather than rejects. On `@Transition`, `creates` and `commands` tell the model
checker which children a handler creates and what it sends them.

Schemas, for the compiler's checks on the plans and for the typed specification `voucha prove
--inductive` proves over: `data(field, type)` on a node type and `payload(event, field, type,
...)` on the profile, with the types from `voucha.sdk.Schema`: `INT`, `STR`, `BOOL`,
`maybe(type)` for a field a handler tests for absence, `setOf(type)` for a collection, and
`record(field, type, ...)` for its elements. Each is the EDN text the Clojure profile API
writes (`[:set {:sku :str :qty :int}]`), so the compiler sees one form:

```java
static final String LINE = record("item-id", STR, "sku", STR, "qty", INT, "price", INT);
...
 .payload("OrderPlaced", "customer", STR, "items", setOf(LINE))
 .nodeType("Order")
     .data("customer", STR)
     .data("items", setOf(LINE))
     .data("charge-attempts", INT)
```

`EffectSpec.external(name)` and `EffectSpec.store(name, adapter)` default to version `v1`, an
idempotency key of tree and node, and the retry-safe policy; `query(statusQuery)` and
`manual()` change the policy, `idempotencyKey(...)` the key, and `onDispatch`, `onAck`,
`onCompletion`, `onFailure` and `completionTimeout` the outcome events. `participant("sql")`
hands the effect to whoever claims it through the [SQL interface](reference/sql-interface.md)
instead of an adapter.

A transition:

```java
// The decision, taken by the kernel on entering ORDER_RESERVE_ITEMS from its counters:
// every line settled and none unavailable means charge.
@Transition(nodeType = "Order", from = "ORDER_RESERVE_ITEMS", on = "AllItemsReserved", to = "ORDER_CHARGING",
            effects = {"payment.charge"}, await = {"PaymentCharged"},
            auto = true, countersCoverChildren = {"items-reserved", "items-unavailable"},
            counter = "items-unavailable", counterOp = "=", counterValue = 0)
public IPersistentMap charge(NodeContext ctx, Event event) { ... }

// Otherwise lines are still pending: wait where the items run.
@Transition(nodeType = "Order", from = "ORDER_RESERVE_ITEMS", on = "ItemsPending", to = "ORDER_RESERVING", auto = true)
public IPersistentMap waitForItems(NodeContext ctx, Event event) {
    return ctx.plan().state("ORDER_RESERVING").build();
}
```

**Java handlers are read as plans.** Give `voucha.sdk.java/definition` the handlers' source
files as `:sources`, and every annotated method written in the extractor's subset becomes a
`:plan` on its transition, which is what the kernel runs; the method is its syntax, and the
model checker reads it exactly. The subset is the plan builder and what feeds it: the reads on
the context and the event, `Values.field`, `Values.number`, `Values.count` and
`Values.isEmpty`, `equals` and `toString`, literals and locals, arithmetic and comparisons,
`c ? a : b`, a request map built with `put`, an `if` whose branches return, a `for` over a
collection that extends the plan or sums a term, and calls to the class's own private helpers,
which are inlined. A method outside it fails assembly with the construct named; mark it
`@Transition(code = true)` to keep it as code, which the model then abstracts. The order
example's handlers are all read as plans.

The annotation carries the handler, `effects`, `await`, `emit`, `auto`, and the common hierarchy
rules: `parentState` (and `parentStateForbidden`), `countersCoverChildren`, and a `counter`
comparison. Other rules can be added to the assembled definition in Clojure. `hosts(child,
state)` on the builder says where a child type's instances live.

`NodeContext` gives `nodeId()`, `nodeType()`, `localState()`, `data(key)`, `counter(name)`,
`childCount()` and `parentState()`. `Plan` has `state`, `set`, `setIn`, `remove`, `increment`,
`append`, `emit`, `emitTo`, `call`, `store`, `child`, `await` and `timeoutAfter`.

Compile with `clojure -T:build javac`, then assemble the definition and handlers:

```clojure
(require '[voucha.sdk.java :as jsdk])

(def assembled (jsdk/definition {:profile (example.OrderProfile.)
                                 :handler-classes [example.OrderHandlers]
                                 :chain-views {...}}))   ; optional, in Clojure

(def app {:definition (:definition assembled) :handlers (:handlers assembled)})
```

`src/voucha/example/order.clj` is exactly that glue, and `tests/voucha/order_example_test.clj`
drives the result through the kernel. Chain views are declared in Clojure data; everything else
about the process can be in Java.
