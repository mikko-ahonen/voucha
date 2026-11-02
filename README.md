<p align="center"><img src="docs/voucha-logo.png" alt="voucha" width="420"></p>

<p align="center">Hierarchical transactional state machines for Java and Clojure, on PostgreSQL.</p>

## What is it?

Business processes that span several systems fail halfway. The retries, the recovery and the
audit trail are hand-written in each service, hard to get right, and tested last.

voucha takes that out of application code. You declare a process as nested state machines, an
order and its line items, a payment file and its batches and transactions, and write a small
pure function per transition. voucha runs it: one event, one database transaction, one
checkpoint. Calls to other systems are recorded inside that transaction and made after it
commits, and the policy you declared per call decides what an unknown outcome means. The
approach is the transaction processing monitor of the mainframe era, modernized on PostgreSQL;
the [introduction](docs/intro.md) has the reasoning.

The runtime is about 5K lines of Clojure and 2K of PL/pgSQL. Every access it makes to its
tables is a function in the `voucha` schema: the database holds the log, the queue, the locking
and the permissions.

## Benefits

- **Declare states and events; the runtime does the rest.** Locking, transactions, retries,
  idempotency keys, crash recovery and audit are not your code.
- **No "what if the call fails halfway" code.** Per external call, declare once whether a
  resend is safe, the other side can be asked, or an operator must decide.
- **Mistakes are compile errors.** Unreachable states, dead ends, unhandled outcomes and
  rules that can never be satisfied are caught before anything runs.
- **Proven, not just tested.** The inductive invariant is generated from the definition, and
  all three examples are proven with their business invariants and no hand-written lemma.
- **SQL is the interface.** Submit events from your own transaction, carry out effects as a
  participant in one transaction, from any language with a PostgreSQL driver.
- **Operable.** `voucha why` names what blocks a node. Interventions are events with an
  operator and a reason, and every transition can be replayed against what was recorded.
- **Testing is built in.** Harness, scripted fakes, a simulator over every interleaving, an
  invariant oracle over the database, fault points, and generated models for P, TLC and
  Apalache.
- **Change the process while it runs.** New work starts under the new definition; work in
  flight finishes under the one it started with.

## Quick start

Requires a JDK (17 or later) and the [Clojure CLI](https://clojure.org/guides/install_clojure).
PostgreSQL is embedded for development and tests.

```bash
git clone https://github.com/mikko-ahonen/voucha && cd voucha
clojure -T:build javac                      # compile the Java SDK (once)
clojure -X:test                             # run the test suite
bin/voucha dev voucha.example.order/app     # run the order example with an admin API on :8710
bin/voucha verify voucha.example.order/app  # explore and prove it, ten minutes by default
```

[Getting started](docs/getting-started.md) walks through the example and your first process.

## Documentation

| Guide | What it covers |
|---|---|
| [Getting started](docs/getting-started.md) | Run the example, scaffold a project, write a transition |
| [Concepts](docs/concepts.md) | Trees, events, plans, effects, counters, artifacts |
| [SDK guide](docs/sdk-guide.md) | Writing a process in Clojure or Java, task by task |
| [Testing](docs/testing.md) | Harness, fakes, simulation, fault injection, model checking, proof |
| [Operator guide](docs/operator-guide.md) | Inspecting, intervening, restarting |
| [Reference](docs/reference/) | Definition format, effect lifecycle, CLI, admin API, SQL interface |
| [Introduction](docs/intro.md) | The problem this solves and how it approaches it |

## Examples

| Example | Language | What it is |
|---|---|---|
| [Order](docs/examples/order.md) | Java | An order and its line items: two levels, four effects, a decision state, a bounded retry. Read this one first. |
| [SEPA payment file](docs/examples/sepa.md) | Clojure | A pain.001 file through validation, screening and settlement over two rails: four levels, retries at two levels, uncertain outcomes. |
| [Travel booking](docs/examples/travel.md) | Clojure | OMG's BPMN travel booking scenario, transcribed: a flight and a hotel booked and charged, the client in the loop with 24-hour limits, compensation and two retry limits. |

Each page has the generated state diagram and the commands to run, simulate, model-check and
prove the example.

## Status

Experimental. Implemented and tested under crash, contention and fault injection, with the
examples proven at the default bounds and the SEPA example at bounds of three, and no
operating history yet. Expect interfaces to
change. PostgreSQL only; no Maven release yet, depend on it as a git dependency.

## License

Apache License 2.0. See [LICENSE](LICENSE).

## Contact

Email mikko@voucha.net about running voucha in production, consulting around it, or building
reliable systems in general.
