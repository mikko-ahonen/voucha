# Testing and verifying a process

A process definition can be wrong in ways no single test run shows: a wait that nothing ends,
an answer that arrives after the node moved on, a command sent twice, a retry that charges
twice. voucha ships the tools to find those, from ordinary tests to a proof, and one command
that runs the exploring and proving kinds with sensible defaults. This guide says how to run
them, what each one can find, and what it cannot.

## Run it

```bash
bin/voucha verify voucha.example.order/app
```

Ten minutes by default. The command compiles the definition, then checks the scenario the app
declares (one order with one line, here) at every level the installed tools reach, and prints
one line per stage:

```
verify voucha.example.order/app: one Order O1 from OrderPlaced, budget 10 min
  compile   ok          24 transition(s), 4 invariant(s): 3 provable, 1 checkable; 0 warning(s)
  simulate  ok          every interleaving: 414 worlds, 60 finished, no violation         0.7 s
  model     ok          P: 1000 schedules, no bug                                         25 s
  tlc       ok          TLC: every reachable state, 219 distinct, no violation            3.1 s
  prove     ok          proven for every tree size the bounds admit (2,2,2): ...          8 min
RESULT: no violation, and the properties are proven for every tree size the bounds admit
```

Each stage ends one of four ways:

- **ok**: it finished and found nothing.
- **VIOLATION**: it found something; the path or state that shows it is printed, and the
  command exits 1.
- **incomplete**: it ran out of its share of the budget before finishing. Nothing was found in
  what it covered; `--budget 1h` or more takes it further. A first model is usually checked at
  the default, and the budget raised once the model is stable.
- **skipped**: the tool is not installed, and the line says which one. The simulator is
  built in; the P checker, TLC and Apalache are optional and separately installed.

The scenario comes from the app map's `:verify` entry, the root node, its first event and
payload, and how many children a step may create; `voucha init` generates one, and `--root`,
`--event`, `--payload` and `--children` override it. `--only simulate,tlc` or `--skip prove`
choose stages; `--out <dir>` keeps the generated models.

## What each stage finds

**compile.** What the definition itself gets wrong: a state nothing reaches, a state no end can
be reached from, an outcome event no transition takes, a plan that asks for an effect its node
type may not create, a child type the parent may not contain, an event a state both handles
and ignores. These are errors with the fix named; `voucha compile` prints the same.

**simulate.** Every order in which the scenario can unfold, with the real handlers: events
processed in any order, every external call completing or failing, an operator's or caller's
event at any moment. On each world it checks that no event was rejected, no child is in a state
its parent forbids, the counters agree with the children, no live effect is owned by a finished
node, every business invariant holds, and, when nothing can move, that the root is finished.
This is where most process bugs show up first: the example processes' own stuck states were
found here, on the first run. It covers one scenario, so choose one with a few children and the
interesting branches; `voucha simulate` runs it alone, with random walks for large trees.

**model.** The same semantics as a model for the P checker, which samples a thousand schedules.
A quick second opinion from a different engine, and the home of the replayable traces; what
`voucha model` runs.

**tlc.** Every reachable state of the scenario, enumerated and counted. Where the simulator
and P explore, TLC is exhaustive for the instance: the count it prints is the whole space. It is
also the stage that grows fastest; a SEPA file of two transactions is out of its reach, one
transaction takes two minutes. `voucha prove --check` runs it alone, `--symmetric` helps when
children are alike.

**prove.** The three checks of an inductive invariant: the initial state satisfies it, one step
from any state that satisfies it stays in it, and it implies the properties. When all three
pass, the properties hold for every tree of every size and every input, not just the scenario.
The invariant is generated from the definition; no proof is written by hand. The properties
are the framework's, no rejected event, no child where its parent forbids it, no tree that
stops before finishing, and the definition's business invariants of the provable class. What
"every size" means exactly is in the limitations below.

## Business invariants

The framework's properties say nothing about whether the process is right for its business.
A definition states that itself, as `:invariants` over its nodes and effects: a payment never
succeeds without its debit, a transaction is never paid twice, an order never ships unpaid.
Every stage checks them, the oracle checks them over a database, and the provable ones, over
the tree and the effects with quantifiers, are proven; the ones with a count or a sum are
checked, not proven, and the report says which. The format is in
[reference/definition.md](reference/definition.md); the examples declare four to five each.

## Writing tests

Three kinds, cheapest first.

**Handlers** are pure functions, so test them like functions: build the context, call the
handler, assert on the plan. Most handler bugs are not in the handler, though; they are in what
it asks for that the definition does not allow, or in what the hierarchy does.

**The harness** builds a tree in any state you describe, sends an event and lets you assert on
the outcome, against a real database (the test suite starts an embedded PostgreSQL):

```clojure
(require '[voucha.harness :as h])

(deftest a-transaction-cannot-pay-before-its-batch-is-paying
  (-> (h/for-artifact *ds* definition {:handlers handlers})
      (h/given-tree [{:id "F1"  :type "File"        :state "FILE_PROCESS_BATCH"}
                     {:id "B1"  :type "Batch"       :state "BATCH_NEW" :parent "F1"}
                     {:id "TX1" :type "Transaction" :state "TX_SCREENED"     :parent "B1"}])
      (h/when-event "TX1" "TransactionPayCommanded")
      (h/expect-rejected-transition #"parent is BATCH_NEW, must be one of")
      (h/expect-state "TX1" "TX_SCREENED")))
```

| Step | What it does |
|---|---|
| `for-artifact ds definition opts` | compile the definition and start a harness; `opts` carries `:handlers`, `:effect-adapters`, `:store-adapters` |
| `given-tree nodes` | create the nodes, root first; the counters are derived from the children |
| `when-event node event [payload]` | submit the event and process everything it triggers |
| `dispatch-effects!` | run the fake dispatchers once and process the events they produce |
| `expect-state`, `expect-transitioned`, `expect-rejected-transition [re]` | assert the node's state or the last event's outcome |
| `expect-effect node "name:version"`, `expect-no-effects`, `expect-await node event` | assert what the node asked for |
| `expect-counter node counter n`, `node node-id`, `why-stuck node` | read what the kernel keeps |

**Fakes** stand in for the external systems, scripted with the outcomes they report, in order,
the last repeating, and they can misbehave on purpose:

```clojure
(require '[voucha.fake :as fake])

(fake/effect-adapter [{:outcome :completed}])                         ; always succeeds
(fake/effect-adapter [{:outcome :failed :payload {:error "AC04"}}     ; rejects once,
                      {:outcome :completed}])                         ; then succeeds
(fake/effect-adapter [{:outcome :dispatched}])                        ; sends and goes quiet
(fake/effect-adapter [{:throw "socket timeout after send"}]           ; unknown outcome,
                     {:outcome :completed :external-request-id "R1"}) ; resolved by a status query
```

`fake/calls` lists every call an adapter received, with its request, so a test can assert that
a payment was sent exactly once, or that an unknown outcome led to a status query and never to
a resend. A process test drives a runtime with fakes and settles it:

```clojure
(let [rt (runtime/runtime {:handlers handlers
                           :effect-adapters {"payment.charge" charge ...}
                           :store-adapters  {"record.update" (fake/store-adapter)}})]
  (kernel/submit-event! ...)
  (runtime/settle! *ds* rt)          ; step until nothing changes
  (is (= "ORDER_COMPLETED" (state-of "O1"))))
```

An asynchronous system, one that answers later, is a fake that reports `:dispatched` and a
test that delivers the answer with `dispatcher/deliver-callback!`; a timeout is
`dispatcher/fire-effect-timeouts!` with a later clock. The examples' tests under
`tests/voucha/` do all of this.

## After the fact: the oracle and replay

`voucha check` runs the invariant oracle over a database: every rule that must hold of the rows
at any instant, declared states, children where their parents allow, counters that agree with
the children, no live effect under a finished node, no event stranded, every business
invariant. Run it at the end of a process test and in production. `voucha verify-replay`
re-runs every recorded handler of a tree on its recorded input and compares the plan with the
committed one, which proves the handlers are deterministic; `voucha.lint` catches the common
offenders statically.

## Faults and the hard kill

Named points in the kernel and dispatcher accept actions a test installs: crash here, pause
there, block until released, force an outcome. The actions live in `voucha.debug`, under
`dev/`, so a production classpath cannot load them.

```clojure
(debug/crash! :transition/before-commit)           ; roll the transaction back
(debug/crash! :effect/after-call :on-hit 2)        ; the second call is sent but never recorded
(debug/sleep! :transition/after-handler 50)        ; widen a race window
(debug/fail-effect! :error "rail refused")         ; force an outcome without a bespoke adapter
```

`:effect/after-call` is the one that matters most: a crash there is a call that was sent and
never recorded, the situation the uncertainty policies exist for. The strongest test is the
hard kill, `tests/voucha/kill_drill_test.clj`: a subprocess halted in the middle of a
transaction, then checked for the committed work surviving, the in-flight work rolled back and
a fresh runtime resuming without redoing anything. The same points carry production concerns
that are not faults: an audit sink, request redaction, authorization policy.

## Benchmarks

`dev/voucha/bench.clj` measures throughput, fan-out, invariant checking and replay against the
budgets in `docs/testing/budgets.edn`, and `tests/voucha/budget_test.clj` fails the build when
a measurement falls outside its budget. The ledger of past runs is in `docs/testing/results/`.

## Limitations

- **A scenario is one input.** The simulator, P and TLC check the scenario you give them. The
  proof covers every input, which is why it is worth the wait.
- **"Every size" is relative to the proof's bounds.** The step check starts from any tree of
  up to a few nodes, queued events and effects (two of each by default, `--bounds` raises it).
  A property that relates siblings, one success per transaction, is exercised only where the
  siblings fit; the examples' pair properties need three to five nodes. Raising a bound costs
  hours to days of solving, which `voucha prove --skeleton` makes resumable shape by shape.
- **The proof abstracts.** Collections in the data are not carried; a step creates some number
  of children up to a bound, with arbitrary records; a handler written in code rather than as a
  plan is checked as what it declares, not as what it does. Plans, which the examples use
  throughout, are translated exactly.
- **Events from outside are injected by the simulator and TLC**, an operator's cancel at any
  moment, but not yet by the P checker or the proof.
- **Lost answers and timeouts** are explored only with `--drop`; without it every call is
  assumed to answer eventually.
- **The models are the handlers by construction and by tests**, a conformance suite against the
  kernel and a differential test of the Java extraction, not by a theorem; the runtime's own
  database behaviour is not modelled.
- **A proof that fails is read, not fixed blindly.** Apalache prints the state the invariant
  could not carry across a step. So far every such state has been one the runtime cannot
  produce and became a rule the generator derives, so report it; a `--lemmas` file is the last
  resort for a definition the generator cannot close.
