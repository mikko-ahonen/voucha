# Getting started

This guide runs the bundled example, then has you write and test a transition of your own.
It takes about twenty minutes.

## Prerequisites

- A JDK, 17 or later.
- The [Clojure CLI](https://clojure.org/guides/install_clojure).

You do not need PostgreSQL installed. Development mode and the test suite start an embedded
server.

## 1. Run the tests

```bash
git clone https://github.com/mikko-ahonen/voucha && cd voucha
clojure -T:build javac    # compiles the Java SDK; needed once, and after Java changes
clojure -X:test
```

The suite takes a few minutes. It starts an embedded PostgreSQL, runs the kernel and
dispatchers in-process, and includes a subprocess that is hard-killed mid-transaction to prove
nothing correctness-critical lives in memory.

## 2. Run the example

```bash
bin/voucha dev voucha.example.batch/app
```

`voucha dev` compiles the example's definition, starts an embedded database, runs the kernel
and dispatcher workers, fakes every external system the definition names, and serves an admin
API on `127.0.0.1:8710`. It prints something like:

```
voucha dev runtime — artifact 3f1c0a9e7b2d4c61
  admin API   http://127.0.0.1:8710
  database    jdbc:postgresql://localhost:41123/postgres

  export VOUCHA_DB_URL="jdbc:postgresql://localhost:41123/postgres"
  ... so that voucha inspect / why / chain reach this instance

  faked effects (no adapter supplied): screening.check, account.debit, account.reverse,
    rail.inst.send, rail.sct.submit, file.writeReceive, file.writeResult
```

Export the `VOUCHA_DB_URL` line in a second terminal so the command-line tools can reach this
instance. Then create a tree and hand it a file:

```bash
curl -XPOST localhost:8710/tree -d '{"tree-id":"t1","root-node-id":"F1","root-node-type":"File"}'

curl -XPOST localhost:8710/event -d '{
  "tree-id":"t1","node-id":"F1","event-type":"FileReceived",
  "payload":{"name":"pain.001","batches":[
    {"batch-id":"B1","transactions":[
      {"tx-id":"B1-T1","amount":100,"payment-type":"SCT"},
      {"tx-id":"B1-T2","amount":250,"payment-type":"INST"}]}]}}'
```

The file is archived, the batch validated, both transactions screened, and both paid. The SCT
transaction completes on its own, because its fake rail answers synchronously. The instant
transaction stops, because the instant rail's fake only reports that the message left. Ask why:

```bash
bin/voucha why t1 B1-T2-A0
```

```
B1-T2-A0 is in ATTEMPT_INST_WAITING
can advance on: InstPaymentAccepted, InstPaymentRejected
  outstanding effect: rail.inst.send (DISPATCHED)
```

That is what an asynchronous payment rail looks like from inside: the send has left, the answer
has not come back. In production the rail's callback would deliver it. Here, you are the rail:

```bash
curl -XPOST localhost:8710/simulate/callback -d '{
  "tree-id":"t1","correlation-key":"B1-T2-A0","effect-name":"rail.inst.send",
  "outcome":"completed","payload":{"rail-ref":"INST-0001"}}'
```

Now the attempt succeeds, the transaction completes, the batch's counters show both paid, the
batch completes, and the file writes its status report:

```bash
bin/voucha chain t1 BatchChain B1
bin/voucha inspect t1 F1
bin/voucha history t1 B1-T2
```

`history` shows every transition the transaction made, the handler that made it, the patch it
applied to the node's data, and the effects it created. That log is the source of truth for
everything else: `voucha replay` reconstructs state from it, and `voucha verify` re-runs every
handler against it to prove they are still deterministic.

## 3. Scaffold a project

```bash
bin/voucha init my-process
cd my-process && clojure -X:test
```

This creates:

```
my-process/
├── deps.edn
├── src/my_process/definition.clj   # the example's definition plus your domain layer
├── src/my_process/handlers.clj     # one handler, overriding one of the example's
└── tests/my_process/smoke_test.clj # a harness test against an embedded database
```

The generated project depends on voucha by version. Until a release is published, point the
dependency at your checkout, as the comment at the top of its `deps.edn` says.

## 4. Write a transition

Open `src/my_process/handlers.clj`. The generated handler replaces the example's handling of
`FileReceived`:

```clojure
(defn file-received
  [_ctx ev]
  (-> (plan/plan)
      (plan/state "WRITING_RECEIVE_FILE")
      (plan/patch (list 'assoc-in [:batches] (get-in ev [:payload :batches])))
      (plan/store "file.writeReceive" {:name (get-in ev [:payload :name])})
      (plan/await* "ReceiveFileWritten")))
```

Read it as a decision, not as an action. The handler says: move to this state, remember the
batches on the node, write the receive file, and expect to hear that it was written. It does not
write the file. The kernel records a store-effect intent in the same database transaction as the
state change, and a dispatcher performs the write after that transaction commits. If the
process dies between the two, the intent is still there when it restarts.

Three rules make this work, and the compiler, the linter and replay enforce them:

1. **A handler returns a plan.** It never performs I/O, reads a clock, or generates a random
   number. If it needs the time or an id, it asks the facts the kernel passes in.
2. **Everything the handler wants done is in the plan.** State, data patches, events to other
   nodes, effects, the event it now awaits.
3. **The plan must fit the definition.** A handler cannot move to a state its transition did not
   declare, or create an effect its node type may not emit. The kernel rejects the whole
   transition, and nothing lands.

Change the handler to record the batch count as well:

```clojure
      (plan/patch (list 'assoc-in [:batches] (get-in ev [:payload :batches]))
                  (list 'assoc-in [:batch-count] (count (get-in ev [:payload :batches]))))
```

and extend the smoke test to assert it. The harness builds a tree in any state you describe,
sends an event, and lets you assert on the outcome:

```clojure
(deftest a-file-counts-its-batches
  (let [h (-> (harness/for-artifact *ds* d/definition {:handlers (:handlers d/app)})
              (harness/given-tree [{:id "F1" :type "File" :state "FILE_NEW"}])
              (harness/when-event "F1" "FileReceived"
                                  {:name "pain.001" :batches [{:batch-id "B1" :transactions []}]})
              (harness/expect-transitioned)
              (harness/expect-state "F1" "WRITING_RECEIVE_FILE")
              (harness/expect-effect "F1" "file.writeReceive:v1"))]
    (is (= 1 (:batch-count (:local-data (harness/node h "F1")))))))
```

Run `clojure -X:test` again.

## 5. Add a state of your own

The definition in `src/my_process/definition.clj` layers a domain map over the example's
profile. Suppose files above a threshold need a four-eyes approval before any batch runs. Add a
state, an event and two transitions to the domain layer:

```clojure
(def domain
  {:events #{"FileApprovalRequired" "FileApproved"}
   :node-types {"File" {:states ["FILE_AWAITING_APPROVAL"]}}
   :transitions
   [{:node-type "File" :from "WRITING_RECEIVE_FILE" :on "FileApprovalRequired"
     :to "FILE_AWAITING_APPROVAL" :handler 'file/hold-for-approval}
    {:node-type "File" :from "FILE_AWAITING_APPROVAL" :on "FileApproved"
     :to "FILE_PROCESS_BATCHES" :handler 'file/start-batches}]})
```

Write `file/hold-for-approval`, register it in the handlers map, and run the tests. If you
forget to declare the event, misspell a state, or leave the new state with no way out, the
compiler tells you which, with the fix it suggests. The `FileApproved` event is one an operator
submits, with their identity and reason recorded on it:

```bash
bin/voucha send-event t1 F1 FileApproved --operator alice --reason "four-eyes check passed"
```

## Where to go next

- `java-src/example/OrderProfile.java` and `OrderHandlers.java` are a complete small process in
  Java, an order and its line items. Run it with `bin/voucha dev voucha.example.order/app`; its
  state diagram is `docs/diagrams/order.svg`.

- [Concepts](concepts.md) explains the model you have just used: trees, events, plans, effects,
  counters and artifacts.
- [SDK guide](sdk-guide.md) covers each thing you will want to do in a handler, including calling
  external systems and handling uncertain outcomes, in Clojure and in Java.
- [Testing](testing.md) covers the harness in full, scripted fakes, the simulator and fault
  injection.
- [Reference](reference/) has the complete definition format, effect lifecycle, CLI and admin
  API.
