<p align="center"><img src="docs/voucha-logo.png" alt="voucha" width="320"></p>

Hierarchical Transactional State Machines For Java and Clojure, on PostgreSQL

## What is it?

Building reliable business processes that span several systems or services is difficult.
Retry mechanisms and error handling are often hand-written, in each service, and they are hard
to get right and thus error-prone. Operations and testing are often an afterthought, and
thus have limited supports.

Using transactional queues can solve some issues, but introduces new, complex distributed
failure modes that are hard to diagnose and fix.

**voucha** takes that burden out of application code. It keeps the process state, the record of 
every outbound call and the policy for resolving an unknown outcome in one transactional log, so 
that the application is reduced to small pure functions that decide what should happen next.

**voucha** provides durable, verifiable execution of hierarchical state machines, implemented
on top of PostgreSQL. It borrows the approach and algorithms from databases and the transaction 
processing monitors of the mainframe era, modernized. More details in 
the [introduction](docs/intro.md).

You declare a process as nested state machines, whatever the nesting is in your domain: an
order and its line items, a claim and its assessments, a payment file and its batches and
transactions. You write a small pure function per transition, in Java or Clojure. voucha runs
it: one event, one database transaction, one checkpoint. Calls to the outside world are recorded 
as intents inside that transaction and executed after the transaction commits. When a call's outcome 
is unknown, effect's declared policy is used to decide how it is handled. No hand-written locking, 
retrying, recovery, or audits.

The runtime core is under 3K lines of Clojure, with another 3K for supporting code and tooling.
PostgreSQL provides the log, the queue and the locking.

## Benefits

- **You declare only the states and what happens on events.** A transition handler is a few lines
  that returns what should happen next. Locking, transactions, retries, idempotency keys, crash recovery and
  audit are provided by the runtime.
- **The "what if the call fails halfway" code is gone.** For every call to another system you
  declare once whether a resend is safe, whether the other side can be asked, or whether an
  operator must decide.
- **Verified, documented state machine.** The process is declared, the compiler checks it, and
  you can draw it with `voucha graph`. Invalid state transitions, unhandled outcomes and broken hierarchical
  dependencies are compile errors instead of incidents.
- **You can inspect the state.** `voucha why` names the rule, the outstanding call or
  the parked event, directly from the evaluator. No log archaeology, no SQL.
- **Testing is first-class and becomes cheap.** Crash mid-transaction, a call sent but never recorded,
  every interleaving of a batch, a hard kill of the process: the harness, fakes, simulator and
  fault points are built in, so you do not write that scaffolding yourself.
- **The process can be changed while it runs.** New work starts under the new definition; work in
  flight finishes under the one it started with. Nothing needs a migration.
- **A refactor cannot silently change the past.** Handlers are deterministic, so replay re-runs
  them against what was recorded and tells you if the result differs.
- **Interventions are events, not SQL at 3 a.m.** An operator submits an event with their
  name and reason, the definition validates it, and the log stays complete.

## Features

- **Hierarchy with aggregation.** A parent's transition can be guarded by rules over its
  children, evaluated from kernel-maintained counters, never by scanning.
- **Effects with uncertainty policies.** An external call that throws is not a failure. Declare
  the effect `retry-safe`, `query` or `manual`, and the runtime resends under an idempotency
  key, asks the other side, or waits for an operator.
- **A compiled, checked definition.** Unreachable states, dead ends, undeclared events, effect
  outcomes nothing consumes, and aggregation rules that can never be satisfied are compile
  errors.
- **Replay.** Handlers are deterministic, so any transition can be re-run against its recorded
  input and compared with what was committed.
- **Operability.** `voucha why` tells you which rule is blocking a node. Interventions are events
  with an operator and a reason, never SQL.
- **Testing built in.** A harness for process tests, scripted fakes for external systems, a
  simulator that explores interleavings without a database, an invariant oracle over the
  store, and generated models of the definition for the P checker and, as a TLA+
  specification, for exhaustive checking with TLC and for proof by induction with Apalache,
  the inductive invariant generated from the definition.
- **Business invariants.** What must hold of every tree beyond what the framework checks,
  stated once in the definition over its nodes and effects, checked by the simulator on every
  world and by the oracle on the rows: a payment that succeeds without its debit is found on
  the first run.
- **Java and Clojure.** Annotated methods in Java and a plan API in Clojure produce the same
  artifact; the kernel cannot tell which wrote a handler. A process can be written entirely in
  either.
- **PostgreSQL only.** One database is the log, the queue and the lock manager; workers are
  disposable and scale horizontally.

## Quick start

Requires a JDK (17 or later) and the [Clojure CLI](https://clojure.org/guides/install_clojure).
PostgreSQL is embedded for development and tests.

```bash
git clone https://github.com/mikko-ahonen/voucha && cd voucha
clojure -T:build javac                      # compile the Java SDK (once)
clojure -X:test                             # run the test suite
bin/voucha dev voucha.example.batch/app     # run the example with an admin API on :8710
```

Then, in another terminal:

```bash
curl -XPOST localhost:8710/tree  -d '{"tree-id":"t1","root-node-id":"F1","root-node-type":"File"}'
curl -XPOST localhost:8710/event -d '{"tree-id":"t1","node-id":"F1","event-type":"FileReceived",
  "payload":{"name":"pain.001","batches":[{"batch-id":"B1",
  "transactions":[{"tx-id":"B1-T1","amount":100,"payment-type":"SCT"}]}]}}'
curl localhost:8710/tree/t1
```

[Getting started](docs/getting-started.md) walks through running the example and writing your own
first process.

## Documentation

| | |
|---|---|
| [Getting started](docs/getting-started.md) | Run the example, scaffold a project, write a transition |
| [Concepts](docs/concepts.md) | Trees, events, plans, effects, counters, artifacts |
| [SDK guide](docs/sdk-guide.md) | Writing a process in Clojure or Java, task by task |
| [Testing](docs/testing.md) | Harness, fakes, simulation, fault injection |
| [Operator guide](docs/operator-guide.md) | Inspecting, intervening, restarting |
| [Reference](docs/reference/) | Definition format, effect lifecycle, CLI, admin API, SQL interface |
| [Why](docs/intro.md) | The problem this solves and how it approaches it |

Further reading: [lessons for hand-rolling a smaller state machine](docs/hand-rolled-state-machine.md).

## The examples

| Example | Language | What it is |
|---|---|---|
| [Order](docs/examples/order.md) | Java | An order and its line items: two levels, four effects, a decision state, a bounded retry. The one to read first. |
| [SEPA instant payment](docs/examples/sepa.md) | Clojure, handlers as data | A pain.001 file through validation, screening and settlement over two rails: four levels, retries at two levels, uncertain outcomes. The fixture for most of the test suite. |

Each page has the state diagram, generated from the compiled definition, and the commands to run
the example, simulate every interleaving of it, model-check it, and prove it.

## Status

Experimental. The kernel, compiler, dispatchers, SDKs and tooling
are implemented and have been comprehensively tested under crash, contention and fault injection,
and the design is believed to be sound.

It is an experiment, with a test suite and a simulator. Expect interfaces to change. Only
PostgreSQL is supported.  No release has been published to a Maven repository; depend on it as a git 
or local dependency.

## License

Apache License 2.0. See [LICENSE](LICENSE).

## Contact and Consulting

Email mikko@voucha.net if you are interested in running voucha in production, consulting related to it or in
how to build reliable systems in general.
