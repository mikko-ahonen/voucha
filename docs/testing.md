# Testing a process

voucha ships the tools to test a process at four levels: a handler on its own, a process
against a real database with scripted external systems, every interleaving of a process with no
database at all, and the runtime under crashes and faults. This guide covers them in that order.

## Handlers

A handler is a pure function, so test it like one:

```clojure
(deftest a-zero-amount-fails-validation
  (let [ctx {:node {:node-id "T1" :node-type "Transaction" :local-state "TX_NEW" :local-data {}}}
        plan (h/tx-start-validation ctx {:event-type "TransactionStartCommanded"
                                         :payload {:amount 0 :payment-type "SCT"}})]
    (is (= "TX_VALIDATING" (:state plan)))
    (is (= "TransactionValidationFailed" (-> plan :emit first :event-type)))))
```

Most handler bugs are not in the handler, though. They are in what the handler asks for that
the definition does not allow, or in what the hierarchy does when several nodes interact. For
those, use the harness.

## Processes: the harness

The harness builds a tree in any state you describe, sends an event, runs the fake dispatchers
if you ask it to, and lets you assert on the outcome. It needs a database; the test suite starts
an embedded PostgreSQL per run, and `voucha init` generates a test that does the same.

```clojure
(require '[voucha.harness :as h])

(deftest a-transaction-cannot-pay-before-its-batch-is-paying
  (-> (h/for-artifact *ds* definition {:handlers handlers})
      (h/given-tree [{:id "F1"  :type "File"        :state "FILE_PROCESS_BATCH"}
                     {:id "B1"  :type "Batch"       :state "BATCH_NEW" :parent "F1"}
                     {:id "TX1" :type "Transaction" :state "TX_SCREENED"     :parent "B1"}
                     {:id "TX2" :type "Transaction" :state "TX_VALIDATING"   :parent "B1"}])
      (h/when-event "TX1" "TransactionPayCommanded")
      (h/expect-rejected-transition #"parent is BATCH_NEW, must be one of")
      (h/expect-state "TX1" "TX_SCREENED")))
```

`given-tree` sets states directly, so a test describes a situation rather than replaying the
history that produced it. It derives the parent counters from the children you describe, so a
test cannot pass against counters the kernel would never have produced.

| Step | What it does |
|---|---|
| `for-artifact ds definition opts` | compile the definition and start a harness; `opts` carries `:handlers`, `:effect-adapters`, `:store-adapters` |
| `given-tree nodes` | create the nodes, root first |
| `when-event node event [payload]` | submit the event and process everything it triggers |
| `dispatch-effects!` | run the fake dispatchers once and process the events they produce |
| `expect-state node state` | assert the node's state |
| `expect-transitioned` / `expect-rejected-transition [re]` | assert the outcome of the last event |
| `expect-effect node "name:version"` | assert an effect intent was created |
| `expect-no-effects node` | assert none was |
| `expect-await node event` | assert the node now awaits the event |
| `expect-counter node counter n` | assert a kernel-maintained counter |
| `node node-id` | read the node, for assertions the helpers do not cover |
| `why-stuck node` | the same answer `voucha why` gives |

Each expectation throws with a plain message on failure, so a test needs no `is` around it.

## External systems: scripted fakes

A fake adapter is scripted with the outcomes it will report, in order, the last one repeating.
It can misbehave on purpose.

```clojure
(require '[voucha.fake :as fake])

(fake/effect-adapter [{:outcome :completed}])                         ; always succeeds
(fake/effect-adapter [{:outcome :failed :payload {:error "AC04"}}     ; rejects once,
                      {:outcome :completed}])                         ; then succeeds
(fake/effect-adapter [{:outcome :dispatched}])                        ; sends and goes quiet
(fake/effect-adapter [{:throw "socket timeout after send"}]           ; unknown outcome,
                     {:outcome :completed :external-request-id "R1"}) ; resolved by a status query
(fake/store-adapter)
```

`fake/calls` returns every call an adapter received, so a test can assert that a payment was
sent exactly once, or that an unknown outcome led to a status query and never to a resend.

A process test with fakes drives the whole thing from a runtime:

```clojure
(let [rt (runtime/runtime {:handlers handlers
                           :effect-adapters {"rail.sct.submit" sct "screening.check" screening ...}
                           :store-adapters  {"file.writeReceive" (fake/store-adapter) ...}})]
  (kernel/submit-event! ...)
  (runtime/settle! *ds* rt)          ; step until nothing changes
  (is (= "FILE_COMPLETED" (state-of "F1"))))
```

For an asynchronous fake, one that answers `:dispatched`, the test delivers the later answer with
`dispatcher/deliver-callback!`, exactly as the real system's callback would.
`tests/voucha/example_test.clj` has a `drive!` loop that does this for the instant rail.

## Every interleaving: the simulator

The harness and fakes test one ordering of events and outcomes. The simulator, in
`voucha.simulate` under `dev/`, explores all of them, with no database, over an in-memory model
of the kernel that the test suite keeps equal to the real one. From the command line:

```
voucha simulate voucha.example.order/app --root Order O1 --event OrderPlaced \
    --payload '{:customer "C-9" :items [{:item-id "L1" :sku "A" :qty 2 :price 10}]}'
```

creates the root, submits the event, and explores every order in which the events can be
processed and the effects can complete or fail. It prints how many worlds it visited and every
violation with the path of moves that led to it. `--walks N` runs seeded random walks instead,
for trees too large to exhaust, and `--succeed` lets every effect only complete. From Clojure:

```clojure
(require '[voucha.simulate :as sim] '[voucha.workload :as w])

(let [wl (w/generate 1 {:files 1 :batches [1 1] :transactions [2 2]})
      r  (sim/explore (sim/world-for specs wl) handlers sim/any-outcome (root wl) {})]
  (is (empty? (:violations r)) (pr-str (take 3 (:violations r)))))
```

`sim/any-outcome` lets every effect succeed or fail in any order. `sim/explore` walks every
reachable world up to a bound, checking on each one that no event was rejected, that no live
child sits under a terminal parent, that counters agree with children, and that no live effect
is owned by a finished node; and on each quiescent world that the root is terminal.
`sim/random-walk` does seeded walks over larger workloads; `sim/world-from` builds a world from
a root type and its first event, as the command does.

What it finds is the class of bug the compiler cannot see: a handler that lands in a wait state
and never emits the event that leaves it, a wait that nothing will ever end, or a command sent
twice, whose second copy the receiving node refuses. The example profile's own liveness bugs of
that kind were found by this tool, the first on its first run.

## Workloads

`voucha.workload` generates reproducible workloads from a seed: files of batches of
transactions, a script saying which batches are rejected, which screenings fail transiently and
which rail attempts are rejected, and the terminal state every node must reach as a result.
`w/run!` submits, drives and verifies against that expectation and against the invariant oracle,
so a failing run is reproducible from its seed alone.

## Model checking: the P model

`voucha model` writes the definition as a model for the [P](https://p-org.github.io/P/) model
checker and, with `--check`, runs it:

```
voucha model voucha.example.batch/app --root File F1 --event FileReceived \
    --payload file.edn --check --schedules 2000
```

The model is the simulator's semantics written in P: one machine holds the tree, the queue and
the effects, and at every step chooses among everything the kernel could do next, any eligible
event in per-node receipt order or any outcome of any live effect, so that the checker explores
the interleavings. The definition's tables are generated into it: states, transitions with their
hierarchy rules, counters, `:child-states-allowed`, `:ignores`, routing and effect specs.

A transition whose handler is a plan is translated exactly: its ops become a P function over
the node's data and the event's payload, so the model does what the handler does and the tree
takes its shape from the payload the run gives, as in production. A transition on a code
handler, which the model cannot run, is abstracted to what the definition says it may do, and
the checker tries every way of doing it: it moves to its `:to`, creates the effects it declares,
creates the children its `:creates` names, as many as `--children` says, sends its `:commands`
to them (or to every child, when it creates none), and emits one of its `:emit` events. Fired
more than `--max-repeat` times on one node it emits only the last of its alternatives, which
bounds retry loops; declare the give-up event last. `:creates` and `:commands` are derived from
a plan and declared, or `creates` and `commands` on the Java annotation, for a code handler.

The checker reports as bugs: an event the kernel would reject, a node that moves after reaching
a terminal state, a child in a state its parent forbids, and a tree in which nothing can move
while the root is not terminal. `--drop` lets an effect be lost, which only a declared timeout
recovers from; without timeouts it finds every wait that depends on an answer. The trace of
the first bug is printed, and the checker's own files, including a replayable schedule, are
under the model directory's `PCheckerOutput/BugFinding`.

For plans this checks the application as written. For code handlers it checks the design: a
handler that does less than the definition allows may be fine where the model finds a bug, and
one that does something the definition does not declare is a bug the model cannot see. The
simulator above runs the real handlers either way; the two complement each other.

## Exhaustive checking: the TLA+ specification

`voucha prove` writes the same model as a TLA+ specification and, with `--check`, runs TLC,
the exhaustive model checker, over it:

```
voucha prove voucha.example.order/app --root Order O1 --event OrderPlaced \
    --payload '{:customer "C-9" :items [{:item-id "O1-L1" :sku "A" :qty 2 :price 10}]}' --check
```

Where the P checker samples schedules, TLC enumerates every reachable state of the instance,
deduplicated and across cores, and reports the exact count, which is a bound rather than a
statistic: the order above has 219 distinct states, all visited, an operator's cancel at every
moment among them; the SEPA file of one transaction 166,353. The same invariants are
checked, no rejected event, no child in a state its parent forbids, no quiescent tree with a
live root; with `--liveness` TLC also checks that a terminal node never moves and that the tree
finishes under weak fairness, which is the correct statement of "every tree completes". A
violation comes with the state sequence that reaches it.

Children that are alike are the lever for larger instances. `--symmetric tx-id` declares the
nodes the payload's `tx-id` fields name interchangeable: TLC then identifies states that differ
only by a permutation of them, which divides the state space by up to the factorial of their
number. Their records must be identical apart from the id; TLC does not check that. The
specification keeps collections as sets and the queue in receipt order without numbering, so
that a permutation of ids maps a state to a state; a plan that depended on the order of a
collection would not translate, and none does.

## Toward proof: the typed specification and Apalache

TLC checks one instance. A property that holds for every tree size needs an inductive
invariant: a predicate true of the initial state, preserved by every step, and implying the
property. `voucha prove --inductive` generates the typed specification Apalache needs, from the
definition's `:data` schemas per node type and `:payloads` per event, and runs the three checks:

```
voucha prove voucha.example.batch/app --root File F1 --event FileReceived --payload file.edn --inductive
```

The invariant it checks is the well-formedness of the state, the properties, and the
application's lemmas, `TRUE` until `--lemmas <file>` supplies a TLA+ predicate. Where a step
from a state in the invariant leaves it, Apalache prints that state: the counterexample to
induction, which is what a lemma has to rule out. The properties as stated are rarely inductive
on their own, so the first runs are expected to produce such states; the tooling makes finding
the lemmas a loop with a solver rather than a paper exercise. `--symbolic N` runs Apalache's
bounded symbolic check instead, every behaviour of up to N steps, which is the same coverage
TLC gives but reasoned rather than enumerated.

The typed specification is an abstraction of the model, kept to what a proof reads. Node ids
are integers allocated fresh. A node's data is a record of the scalar fields of its type's
`:data` schema and a payload a record of the scalar fields of the `:payloads` schemas;
collections are not carried. A plan that creates one child per record of a collection creates
instead some number of children, chosen by the checker up to the largest `--children` bound,
each commanded with an arbitrary record of the scalars its schema declares, and a count or a sum
over a collection is an arbitrary integer: every file is covered, and the properties hold of the
abstraction only if they hold of every file. There is no absent value: a field has its type's
default, and a field a handler asks about with `:nil?` is declared `[:maybe T]`. The definition
is specialised per transition, one action each with literal states, guards, destination and
emits, and no table indexed by a symbolic value, which is what makes a step cheap for the
solver: a step of the SEPA specification takes seconds where the first version took minutes
or exhausted memory.

The invariant the induction starts from carries what the runtime guarantees of any state,
derived from the definition: children are of the types their parent creates, and the parent is
in the creating transition's target state or one reachable from it; a queued event is one its
target's type handles, from nothing (the first event, or an effect's outcome, matched to an
effect the target owns in that status), from the target itself, its parent, or a descendant, and
the sender is in the emitting transition's target state or beyond; an auto request names an auto
transition and there is at most one per node; a creation command is the first event its fresh
child sees; each node accepts its queued events in order from its state now, which is the rule
a queued system needs for the step to close; outcome events carry their effect instance, only
the last instance of a specification a node requested can be pending, a live effect's owner is
in a state where it awaits it and a node in such a state has one pending; a node whose auto
transition is enabled has something queued, a node in a state entered only by emitting to
itself has that event queued, a fresh child has its creation command queued; and a node that
is not done has an event or a live effect on its own line of ancestors and descendants, which
is the inductive form of the no-dead-tree property. What is left for `--lemmas` is the
application's own: what its guards guarantee about the children, and what its fallback states
mean.

Where this stands, measured at the default bounds of two, 2026-10-31. Both examples pass all
three checks with no lemma file and with their business invariants among the properties: SEPA
(five properties) the initial state in 31 s, the step in 25 min, the implication in 35 s; the
order example (three) in 17 s, 11 min and 18 s. The framework's properties, no rejection,
children in allowed states, no quiet tree with a live root, and the examples' own hold in every
reachable state of every input, under the abstraction above and for the bounded shapes the
step check starts from, and the whole inductive invariant is generated from the definition.
The bound is not a formality: a step check at bounds of three, given a 10 GB heap, found after
68 minutes a shape the bound-two invariant admitted that needs three nodes, a batch in its
fallback state with every transaction already screened and nothing queued, unreachable but
not excluded, since the fallback rule could not tell which guarded path the batch had come
from. Each node now carries the state it came from, a history variable the proof may read and
the runtime need not keep, and the fallback rule is exact per entry; the proofs were rerun.
It was not always so: the loop first produced six lemmas for SEPA, four of two structural
shapes that are now derived, what a cover guard guarantees afterwards where it dominates and
what a fallback state means, and two that turned out redundant with the other generated rules.
The order example, through the same path with its schemas declared from Java, found a real
gap on its first run, a charge completing after an operator's cancel, which no simulated or
enumerated run had reached because none sends an operator's event; the example now handles
it. At bounds of three the step check found its step and verified 26 of the invariant's
conjuncts in 29 minutes, then the solver ran out of memory under a 14 GB cap; the proofs
stand at bounds of two. `--lemmas` remains for a definition whose invariant the generator
cannot close.

## The outside world's events

An event the definition handles but nothing in it produces, an operator's cancel, a caller's
command, comes from outside. The simulator and the TLC specification inject each such event
once per node and event wherever the node's state handles it (`compiler/external-events` lists
them), so an operator acting at any moment is among the interleavings. The order example's
cancel during a charge in flight, after which the charge completes and its event reached a
node that could not take it, was found by the induction check first and is in reach of the
simulator and TLC since; the P model does not inject yet.

## Business invariants

What the framework checks, no rejection, children in allowed states, no dead tree, says
nothing about whether the process is right for its business: whether a payment can succeed
without its debit, or a transaction be paid twice. A definition states those once, as
`:invariants` over its nodes and effects ([definition.md](reference/definition.md),
[design](design/invariants.md)), and the simulator checks them on every world it explores,
with the path to the first violation, and the runtime oracle over the rows of every tree. The
SEPA example declares five, the order example four; each has a test that a planted defect the
framework's properties cannot see is caught by its own property: an attempt that reports its
debit done without requesting it is found by `success-only-after-debit` on the first world
that pays it. The P model asserts them after every step and the TLC specification checks them
as invariants, so `voucha model --check` and `voucha prove --check` cover them too. The
provable ones are part of what `voucha prove --inductive` proves: on both examples the three
checks pass with them, so a payment never succeeds without its debit and an order never ships
unpaid in any reachable state of any input, under the proof's stated bounds; the report names
them, and names the checkable one it leaves to the other checkers.

## The invariant oracle

`voucha.invariants/check-all` checks a database for things that must never be true: an
undeclared state, a live child under a terminal parent, a counter that disagrees with the
children, a live effect under a terminal node, an effect stranded mid-dispatch, an event and
checkpoint that disagree, a query effect dispatched twice, duplicate idempotency keys, and a
quiescent tree that is not finished. Run it at the end of any process test, and in production
with `voucha check`.

## Determinism: replay

`replay/verify-deterministic` re-runs every recorded handler on its recorded input and compares
the plan with the committed one. Run it at the end of a process test to prove the handlers you
just exercised are still deterministic, and with `voucha verify` against a real database.
`voucha.lint` catches the common offenders statically.

## Faults: interception

Named points in the kernel and dispatcher accept actions a test installs: crash here, pause
there, block until released, rewrite this plan. The actions live in `voucha.debug`, under
`dev/`, so a production classpath cannot load them.

```clojure
(require '[voucha.debug :as debug] '[voucha.intercept :as intercept])

(debug/crash! :transition/before-commit)           ; roll the transaction back
(debug/crash! :effect/after-call :on-hit 2)        ; the second call is sent but never recorded
(debug/sleep! :transition/after-handler 50)        ; widen a race window
(debug/block! :transition/context-loaded :hold)    ; park a worker holding its locks ...
(debug/signal :hold)                               ; ... and release it
(debug/fail-effect! :error "rail refused")         ; force an outcome without a bespoke adapter
(intercept/reset!)                                 ; between tests
```

The points, in order of execution:

| Point | Where |
|---|---|
| `:event/before-submit`, `:event/claimed` | before the kernel transaction |
| `:transition/context-loaded`, `:transition/constraints-checked`, `:transition/before-handler`, `:transition/after-handler`, `:transition/before-commit` | inside it |
| `:effect/before-dispatch`, `:effect/after-call`, `:effect/before-apply` | in the dispatcher |
| `:timeout/before-fire`, `:outbox/before-drain`, `:worker/tick` | the loops |

`:effect/after-call` is the one that matters most: a crash there is a call that was sent and
never recorded, which is exactly the situation the uncertainty policies exist for.

When a test rewrites a handler's input or plan through one of these points, the committed
checkpoint is tagged with the point and the interceptor, so a replay can always tell an injected
value from a genuine one.

The same mechanism carries production concerns that are not faults: an audit sink at
`:transition/before-commit` (`voucha.telemetry/install-audit!`), request annotation or redaction
at dispatch (`voucha.effect/annotate-requests!`, `redact-requests!`), and authorization policy
at `:transition/constraints-checked` (`voucha.policy`). The dev admin API exposes the
declarative actions over HTTP for driving a runtime from another process.

## The hard kill

The strongest test a process can be given is a `Runtime.halt` in the middle of a transaction:
no shutdown hook, no `finally`, no exception anything could catch. `tests/voucha/kill_drill_test.clj`
starts a subprocess, commits one event, arms `debug/kill!` at `:transition/before-commit`, drives
a second event into it, and then asserts from the parent that the committed work survived, the
in-flight work rolled back, and a fresh runtime resumed from the checkpoint without redoing
anything. If you change anything near the kernel transaction, this is the test to watch.

## Benchmarks

`dev/voucha/bench.clj` measures throughput, fan-out, invariant checking and replay against the
budgets in `docs/testing/budgets.edn`; `tests/voucha/budget_test.clj` fails the build when a
measurement falls outside its budget. The ledger of past runs is in `docs/testing/results/`.
The gate catches regressions; finding where the time goes is the performance test design in
[design/performance.md](design/performance.md), ten scenarios each varying one parameter, run
with `clojure -X:dev voucha.perf/run! :scenario s1` (or `:all`). Its first finding was the
harness: the embedded server's own datasource opened a connection per transaction, 3 ms each;
behind the production pool, which the fixture and the benchmarks use now, the gate's numbers
tripled or better and the budgets were reset.
