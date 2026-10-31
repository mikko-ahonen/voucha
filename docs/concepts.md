# Concepts

What the pieces are and how they fit. Read this once; the SDK guide and the reference assume
it.

## A process is a tree of state machines

Every running process is a **tree**. Each node in the tree is an instance of a **node type**
you declared, with its own state machine, its own current **state**, and a small document of
**node data**. A payment file is a root node whose children are batches, whose children are
transactions, whose children are payment attempts.

The tree, not the node, is the unit the platform reasons about. A parent cannot complete while
a child is still working; a child cannot act under a parent that has finished; and a parent
learns what its children are doing through counters the platform maintains, never by scanning
them.

```
File        FILE_NEW → WRITING_RECEIVE_FILE → FILE_PROCESS_BATCHES ⇄ FILE_PROCESS_BATCH → FILE_BATCHES_PROCESSED → FILE_COMPLETED
 └ Batch    BATCH_NEW → BATCH_VALIDATING → BATCH_SCREEN_TRANSACTIONS ⇄ BATCH_PROCESSING → … → BATCH_COMPLETED
    └ Transaction  TX_NEW → … → TX_SCREENED → TX_PAYING → TX_COMPLETED
       └ Attempt   ATTEMPT_NEW → ATTEMPT_DEBITING → … → ATTEMPT_SUCCEEDED
```

## The definition is data, and it is compiled

You describe the process as a map: node types with their states, terminal states and counters;
the **events** that exist; the **effects** that may be requested; the **transitions**, each
naming a node type, a from-state, an event, a to-state, a handler, and optionally the effects it
creates, the event it awaits, the events it emits and the hierarchy rules it requires.

States may nest. A composite state holds sub-states of one node, so that a rule declared on it,
an operator's cancel for instance, applies in every sub-state. The runtime only ever sees the
leaves; the compiler expands the rest. This is hierarchy inside one node's machine, distinct from
the hierarchy between nodes above.

The compiler turns that map into an **artifact**: immutable, named by the hash of its content,
and checked. It rejects a state nothing can reach, a state from which no terminal state can be
reached, an event or effect nobody declared, an effect outcome no transition consumes, a
terminal parent state that permits a live child, an aggregation rule over a counter that does
not exist, and more. Each error names the fix.

A tree is pinned to the artifact it started under for its whole life. A new version of the
definition is a new artifact; new trees start under it, and old trees finish under the old one.
There is no migration of in-flight work.

## Events drive everything

Nothing happens to a node except through an **event** addressed to it. Input from outside is an
event. A timer firing is an event. A child telling its parent it has finished is an event. The
outcome of a call to an external system is an event. There is one entry point, and it is a row in
the event log.

The kernel takes one event at a time and processes it in **one database transaction**: lock the
node and its ancestors, find the transition, check its rules, run the handler, validate the plan,
write everything, append a checkpoint, commit. If the worker dies half-way, the transaction rolls
back and the event is still pending. There is no recovery procedure, because there is nothing to
recover.

An event that fits no transition is **rejected**, with the reason recorded. An event whose
transition rules are not met is rejected too, unless the transition is an aggregation, in which
case it is **ignored**: the parent is simply not ready yet, and the last child to arrive will be
the one that passes. An event whose handler keeps failing is **parked** for an operator; the node
is untouched. Events addressed to one node are processed in the order they arrived.

## A handler returns a plan

A transition's handler is a pure function of the node, its ancestors, the event and the facts
the kernel provides. It returns a **plan**: the new state, patches to the node data, children to
create, events to send, effects to request, the event it now awaits, and optionally a timeout.

```clojure
(defn start-paying [ctx _event]
  (let [attempt-id (str (node-id ctx) "-A" (get (data ctx) :attempts 0))]
    (-> (plan/plan)
        (plan/state "TX_PAYING")
        (plan/child attempt-id "Attempt")
        (plan/emit-to attempt-id "AttemptCommanded" (select-keys (data ctx) [:amount :payment-type])))))
```

A handler performs no I/O. Not a database read, not a clock, not a random number. If it needs
the time or a fresh identifier, it asks the facts the kernel passes in, which are fixed at commit
time. The reward is **replay**: because the handler is deterministic, the platform can later run
it again on the recorded input and compare the result with what was committed. A handler that
has quietly started reading the clock is caught.

The kernel validates the plan against the artifact before applying it. A handler cannot move to
an undeclared state, request an effect its node type may not emit, or create a child of a type
its node type may not contain. One mistake and the whole transition is rejected.

## Effects are intents, executed after commit

A call to the outside world, such as a payment sent over a rail, a sanctions check, a file
written, is an **effect**. The handler does not perform it. The plan records an intent, and the
kernel writes it as a row in the same transaction as the state change. Either both land or
neither does.

After commit, a **dispatcher** claims the intent and calls the **adapter**, the code that talks
to the external system. The adapter is handed the request the handler put in the intent, never
the node's current data, which may have moved on. Whatever the adapter reports becomes an event
to the node that owns the effect: dispatched, acknowledged, completed, failed, timed out. The
node's state machine decides what each means.

The case that breaks most systems is the call whose outcome is unknown: the adapter threw after
the request left. That is not a failure, and the platform does not treat it as one. Each effect
declares an **uncertainty policy**:

| Policy | Meaning | Use when |
|---|---|---|
| `retry-safe` | send again under the same idempotency key | the other side deduplicates, or the call is idempotent |
| `query` | ask the other side what happened; never resend | a resend could pay twice |
| `manual` | park it for an operator | nobody can safely decide automatically |

A `query` effect that cannot be resolved rests in `DISPATCHED`, the honest state for "we may
have sent it", until the answer arrives or an operator delivers it. A dispatcher that dies
mid-call leaves a lease behind; when it expires, the same policy applies.

Effects come in two kinds. An **external** effect is a call to another system. A **store**
effect is a database write outside the kernel's own tables, performed by a store adapter and
recorded under a unique key so a repeat is a no-op.

## Parents see children through counters

A node type declares **counters** over its children: "the number of transactions in
`TX_SCREENED`", "the number in `TX_FAILED`". When a child transitions, the kernel adjusts the
parent's counters inside the child's transaction. Counters track current membership, so a child
that leaves a counted state decrements it.

The question "are all the children done?" is then a **decision state**. Its outgoing
transitions are marked `:auto`: they fire on entering the state, the first whose rules hold,
and the rules are aggregation rules over the counters, for instance that the counters for
"paid" and "failed" together cover every child. If they do not hold yet, a last auto transition
with no rules takes the parent to the state in which the children run. A child that reaches a
state its parent cares about bubbles an event upward, and that event brings the parent back to
the decision state to decide again. Each decision is one row read, however many children there
are, and it is taken by the kernel from the declared rules, never by a check in handler code. A
parent with no children, or whose children have all already finished, passes through the
decision at once.

Hierarchy rules work downward too. A transition can require its parent to be in one of some
states, or an ancestor not to be in a failed state. That is what stops a late callback from
moving an attempt under a transaction that has already given up on it.

## Everything is on the log

Every event, every rejection, every transition with its patch and the effects it created, every
effect attempt and outcome, is on the log in order. From it the platform can reconstruct any
node's history, compare it with the node's current row, replay any handler, and tell an operator
exactly why a node is where it is. Interventions are events too, carrying the operator's identity
and reason, so the log stays complete.

## Where the pieces live

| Piece | Namespace | What it does |
|---|---|---|
| Compiler | `voucha.compiler` | definition map in, checked artifact out |
| Kernel | `voucha.kernel` | one event, one transaction, one checkpoint |
| Plan | `voucha.plan` | what a handler returns |
| Dispatcher | `voucha.dispatcher` | runs effects after commit, delivers callbacks, fires timeouts |
| Adapters | `voucha.adapter` | the protocols your integrations implement |
| Runtime | `voucha.runtime` | assembles handlers and adapters; worker loops |
| Inspection | `voucha.inspect`, `voucha.replay` | why-stuck, history, chain views, replay |
| Testing | `voucha.harness`, `voucha.fake`, `voucha.simulate` | process tests, scripted fakes, simulation |
| CLI | `voucha.cli` | the `voucha` command |

The kernel knows nothing about files, batches or payments. Everything domain-specific is in the
definition and its handlers, and a test asserts that no kernel namespace mentions a node type by
name.
