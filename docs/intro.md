# Building modern reliable systems

## Introduction

Modern business systems are complex and often too fragile. Most transactions within an organization need several
systems to interoperate, and the chain may reach services in another organization, over
unreliable networks.

Historically, reliable systems were built on Transaction Processing (TP) monitors on mainframes. Their
great benefit was that a unit of work spanning several services committed or rolled back as one. The
machinery for that still exists -- XA and two-phase commit -- but it has been mostly abandoned by the
industry. A distributed transaction couples the availabilities of all participants, and it cannot
enlist a third party you do not control.

It was replaced by **eventual consistency**: replicas converge once updates stop, but it has weak 
guarantees for the typical situation. There is no particular moment when a business rules such as 
"every debit has a matching credit" holds, and every consumer of the data must be built around the
gap. As there are typically many consumers, including frontends, the consistency problem is multiplied.

The proposed remedy is the **saga**: a sequence of local transactions, each with a compensating action
that undoes it if a later step fails. This is undermined by two facts. Firstly, compensation often does not
exist -- money is already sent, a message is already delivered, a letter is already posted. Secondly, sagas give
up isolation: between steps, every other reader sees the intermediate state and may act on it. Of the
ACID properties, a saga can only guarantee durability.

Transactional message queues can close some of the gap, but the popular queues optimize for throughput.
Kafka transactions, for example, make a group of writes and the consumer's offset commit atomic within
Kafka. The recipient's processing cannot participate in the transaction. End-to-end transactions 
stop at the broker.

In practice, building reliable systems using queues creates a complex implicit distributed state machine. 
When the states are implicit, there is no way to directly inspect the state of the process becomes,
but it has to be reasoned: for example when a message is in a dead letter queue, it has failed. You often
need to find out from logs.

There are no general solutions for the distributed failure modes which the TP monitors made impossible. They are
rare, and most organizations absorb them with manual reconciliation and error investigation by 
the operational personnel.

TP monitors also had limitations that contemporary requirements expose. Systems are more
interconnected than before, and external systems may be on the critical path. For example, European banks 
face new requirements for online payments, and compliance checks may depend on third-party services. A call
into another organization has new failure modes that were not present in mainframe systems. Modern systems may
also need to interoperate with legacy systems built around batch processing, which is difficult to 
reconcile with online processing.

## A Modern Approach

A modern successor to the TP monitor cannot bring the external call back inside the transaction. But
*uncertainty* of that call can be made a first-class, durable part of the system's state,
that can be resolved by a declared policy, instead of hand-writing the handling in each service.

The important requirements for a modern equivalent:

* Business state changes exactly once, durably, in one local transaction, with a checkpoint that lets
  processing resume after any failure.
* An effect on an external system is recorded as an intent in that same transaction and executed
  afterwards. The outcome -- including "unknown" -- is recorded and drives the next state change.
* The uncertain outcome is handled depending on the integrated system. If the system supports
  retries, the call is repeated maintaining a consistent key. If the system supports querying the 
  state, the query is carried out. In some cases, the situation needs to be handled by a human operator.
  "Exactly once" is achieved for recorded state; for the external call the guarantee is
  at-least-once or at-most-once, made safe by idempotency and state queries.
* Ordering is preserved where it matters: per unit of work, but not globally.

Also needed:

* Temporary network failures are automatically masked.
* External systems are reached over queues, or over RPC calls (e.g. REST).
* Reachability, liveness and the routing of every outcome can be formally verified before deployment.
* Observability sufficient for operations and testing, including deterministic replay from the log.
* Versioned deployment: new versions of the processing logic can be rolled out while old versions
  are still available.

Typical business use can drop one requirement: the performance and scalability of hyperscalers, such 
as Google and Facebook. Most systems and processes in a corporation process thousands of transactions 
a second at most, often far less.

Durable execution and workflow engines give durable state, but retries, idempotency and status queries against
the external system need to be implemented in the activity code, possibly using a separate integration platform.
While integration platforms have those features, they do not have durable workflows, so the state of the 
business process and the state of the outbound call live in different systems and drift apart. Neither category 
handles the case where a batch and its online transactions must complete as one.

## Vision

Is it realistic to build a successor to systems battle-tested over decades? Three things make it
plausible.

The algorithms are known. The research and the textbooks that describe how TP monitors and database
engines work are available to anyone.

The uncertain outcomes can be solved with a persistent state machine that has built-in support for
various handling policies. If the state machine supports hierarchical states, the use cases combining
batches and online transactions can be supported.

PostgreSQL supplies most of what a TP monitor had to build itself: the durable log, locking, recovery,
and -- through `SELECT ... FOR UPDATE SKIP LOCKED` -- a transactional work queue. Message formats with
bindings for every language exist.

What remains is small: the state machine runtime, effect execution and verification tooling, because 
the architecture can be built on top of a database and queues.

We should optimize for reliability instead of throughput. The industry has optimized throughput partly
because the solutions are well known, but most corporate workloads do not need them.

Microservice architectures offered independent deployments, and this can be delivered by versioning the 
deployed state machine hierarchy as a whole, instead of services around it.

As hierarchical state machines are often complex, the platform should also support formal verification of 
the state machine.

## How Should It Work

The platform manages **hierarchical state machines**. A top-level state machine contains child state machines, 
which may have children of their own. The top-level machine is the unit of consistency. Business rules are stated 
over it, not over the individual children.

In batch processing, for instance, batch files typically contain batches, and batches contain operations.
The consistency unit is the file, not the individual operations.

The state machines are **declared, not coded**. The definition must include both the states
and events each state supports. The terminal states must be identified. To implement uncertainty
policies, every transition must declare the effects that the transition may create and then await.

The platform includes a compiler that turns the definition into a **compiled definition**: 
content-addressed, immutable, and verified for unreachable states, dead ends, effects without a consumer, 
and unsatisfiable aggregation rules. A running hierarchy is processed under the compiled definition it started with 
for its whole life, so in-flight work never needs migration.

Processing is driven by **events**, each addressed to a state machine in the hierarchy. The 
platform takes one event at a time and does the following inside a single database transaction:

1. Claim the event from the queue, in arrival order per target state machine, skipping anything another
   worker process holds.
2. Lock the target state machine and its ancestors, shallowest first. Concurrent workers on the same
   hierarchy never deadlock.
3. Check the compiled definition: is there a transition from the current state on this event, and are
   the transition's conditions on the parent and on the children met? If not, the event is rejected
   and the rejection is recorded.
4. Run the transition handler. Transition handlers are pure functions that can access the event, 
   the compiled definition, and local data in the database. The handler returns a **plan**: the new state, 
   changes to the local data, children to create, events to send to other state machines, effects to request, and 
   the events being awaited. Handlers are not allowed to perform any I/O. Everything they want done is in the plan, 
   and the platform does it, making handlers replayable.
5. The plan is validated against the compiled definition -- a handler cannot move to an undeclared state or request
   an effect that may not be emitted from that state -- and applies it: record the state and data, create the
   child state machines, enqueue the outgoing events, insert each effect as an **intent** with the request it
   needs, and append a checkpoint to the transition log.
6. Commit.

The processing is all-or-nothing. If the worker crashes during processing, the event is left unclaimed and
the local data is not changed. The next available worker restarts processing. The handler always sees 
a consistent snapshot.

Changes to the outside world are **effects**, for example an operation sent
over a queue, a call made to a mainframe system, or a written file. The handler
only records the intent of the effect. A separate **dispatcher** claims
committed intents and calls the adapter, the code that talks to the external
system. The intent is fully self-contained, the current data is not sent, as
it may have changed. The result -- dispatched, acknowledged, completed, failed,
timed out -- becomes an event itself, processed by the state machine that
created the intent, using the same single-transaction rule.

If the call fails in any way that leaves the result unknown, it is resolved by the effect's declared 
**uncertainty policy**. A *retry-safe* effect is sent again under the same idempotency key, so it can be
deduplicated by the receiver. A *query* effect is asked about, and the answer becomes the outcome. A *manual*
effect waits for an operator. Dispatcher crashes are handled by expiring leases,
using the same policy. Handlers do not have to deal with the implementation complexity.

Parent state machines have access to their children's **counters**. When a child transitions, the 
parent's count for the child's new state is adjusted inside the enclosing transaction. A parent's transition may be
guarded by an aggregate rule -- for example "every child is successful or failed", expressed as counters covering the
child count -- and the rule is evaluated against those counters, instead of scanning children. A child
that enters a state its parent is waiting on sends the parent an event. If the rule is 
satisfied, the event is processed, otherwise it is rejected. Completion of a parent becomes an ordinary transition, 
triggered by the last child to finish.

Every event, rejection, transition, effect outcome and data change is **logged** in order. 
A state machine's history can be reconstructed from the log, and compared with the
current data, which can be used to run consistency checks on the store. Pure
handlers make **replay** of events possible. The recorded handler can be executed on the recorded 
input and the plan can be compared with the committed plan. Deployment that would change the past can be
prevented.

Versioning is based on compiled definitions. A changed definition compiles to a new one, and new processing starts
under it. Processing that started under the old definition runs to completion under the old definition. Two versions run side
by side, without migration of in-flight work.
