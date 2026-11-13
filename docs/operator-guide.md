# Operator guide

How to see what a running system is doing, and how to intervene when it needs you.

The one rule: **you never edit `state_node` by hand.** Every intervention is an event, validated
against the artifact, recorded with your identity and your reason. If a change cannot be
expressed as an event the artifact accepts, that is a modelling gap to fix in the definition,
not a reason to reach for SQL.

All commands below need `VOUCHA_DB_URL` exported, or a `voucha.edn` naming the database.

## Why is this node stuck?

```bash
voucha why t1 B1-T1
```

```
B1-T1 is in TX_SCREENED
cannot advance on any event
  blocked: TransactionPayCommanded -> TX_PAYING
    - parent is BATCH_NEW, must be one of BATCH_PAY_TRANSACTIONS, BATCH_PROCESSING, BATCH_SCREEN_TRANSACTIONS
```

The reason comes from the same rule evaluator the kernel runs, so it cannot drift from the rule
that actually executes. Every blocking rule is listed, not just the first. A node waiting on an
external call shows the effect and its status:

```
B1-T2-A0 is in ATTEMPT_INST_WAITING
can advance on: InstPaymentAccepted, InstPaymentRejected
  outstanding effect: rail.inst.send (DISPATCHED)
```

Related:

```bash
voucha inspect t1 [B1-T1]          # full node or tree state
voucha history t1 B1-T1            # every transition, with patches, effects and what was read
voucha chain   t1 BatchChain B1    # the process's own progress view over a batch
```

## Health

`voucha.metrics/snapshot` is one query a dashboard or health check can poll:

| Field | Rising means |
|---|---|
| `event-backlog` | the kernel is behind, or a worker died |
| `parked-events` | events that failed repeatedly and now need you |
| `due-timeouts` | the timeout scheduler is behind |
| `effect-backlog` | grouped by effect name and status; see below |
| `uncertain-effects` | outcomes nobody can determine; each needs a decision |
| `unresolved-external-work` | effects the runtime closed while the outside world may still hold them |
| `stalled-nodes` | active nodes idle beyond a threshold |

Within `effect-backlog`, a rising `DISPATCH_FAILED` count for one effect means that external
system is down. A rising `ACKNOWLEDGED` count means it accepted work it has not finished, which
may be normal or may mean its callback is not reaching you. A rising `TIMED_OUT` count means it
is accepting work it does not complete.

`voucha check` runs the invariant oracle over the whole database and exits non-zero on any
violation. Run it on a schedule.

## Parked events

An event whose handler fails is retried once per drain pass. Once its attempts are exhausted it
is `PARKED`: recorded with its error, its node untouched. Nothing cascades into the tree.

To resolve, fix the cause and submit a fresh event carrying who you are and why:

```bash
voucha send-event t1 F1 FileReceived --operator alice --reason "reprocessed after parser fix"
```

Both fields land in the event log and stay there.

## Uncertain effects

An effect in `DISPATCH_FAILED` with `requires-operator` set is one whose outcome nobody knows and
whose policy forbids both a blind retry and a status query. The external system may hold the
request. Resending could pay twice; abandoning it could lose the payment.

Find out what actually happened, then deliver the answer:

```clojure
(dispatcher/deliver-callback! ds {:external-request-id "SCT-REQ-77"
                                  :outcome :completed
                                  :payload {:rail-ref "SCT-0077"}})
```

or over the dev admin API with `POST /simulate/callback`. The answer becomes the effect's
completion or failure event and drives the node like any other. Delivering an outcome to an
effect that is already terminal is refused.

## Unresolved external work

When a node reaches a terminal state, the effects it still owes are cancelled: no transition
remains that could consume their outcomes. Cancelling settles the runtime's side only. If the
request may already have reached the external system, because the effect was `DISPATCHING`,
`DISPATCHED` or `ACKNOWLEDGED`, it is cancelled *and* flagged `requires-operator`, and it appears
in `unresolved-external-work` with the status it was cancelled out of. The rail may still be
holding that payment. Somebody has to look.

An effect cancelled from `CREATED` or `DISPATCH_FAILED` never left the runtime, so nothing needs
reconciling and nothing is flagged. Every cancellation is recorded as an attempt on the effect,
so `voucha inspect` on the owner explains why it stopped where it did.

## Dev mode

`voucha dev <ns/app-var>` runs the kernel, the dispatchers, an embedded PostgreSQL and an admin
API in one process, faking any effect that has no adapter. It prints the `VOUCHA_DB_URL` to
export so the commands above reach it.

The admin API binds to loopback and has no authentication. It is a development tool;
authorization in a deployment is applied at the edge, where events are submitted.

```bash
curl -XPOST localhost:8710/tree  -d '{"tree-id":"t1","root-node-id":"F1","root-node-type":"File"}'
curl -XPOST localhost:8710/event -d '{"tree-id":"t1","node-id":"F1","event-type":"FileReceived","payload":{...}}'
curl localhost:8710/why/t1/F1
curl -XPOST localhost:8710/simulate/callback -d '{"tree-id":"t1","correlation-key":"B1-T2-A0","effect-name":"rail.inst.send","outcome":"completed"}'
curl -XPOST localhost:8710/simulate/timeouts -d '{}'
curl localhost:8710/health
```

## Restart

Restart is not rerunning anything. It is resuming from the latest committed checkpoint. A
restarted runtime does not re-validate work that already committed, does not resend an
acknowledged request, does not rewrite a completed store effect, and does not rerun a completed
child. Start the workers; there is no recovery step.

## Reprocessing

Reprocessing is modelled, not improvised. The artifact declares which events are valid in which
states, so a reprocess request that does not make sense is rejected with a reason rather than
corrupting the tree. If your process needs a reprocess path the artifact does not have, add the
transition and compile a new artifact. Existing trees keep running the artifact they were pinned
to.

## Replay and verification

```bash
voucha replay t1 [B1-T1]               # reconstruct history from the log
voucha verify t1 my.app/app            # re-run every handler, compare with what was committed
```

Replay never executes an effect. `verify` is how you prove a handler is still deterministic
after a change: if it now reads a clock or a random number, its patch differs and the divergence
is reported, with the transition it came from.

## Upgrading a definition

Compile the new definition with `voucha compile`. New trees start under the new artifact. Trees
pinned to the old one run to completion under it, and `voucha inspect` explains an old tree under
the rules that governed it. Two versions run side by side; nothing in flight is migrated.
