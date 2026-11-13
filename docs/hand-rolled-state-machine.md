# Lessons for a hand-rolled multi-level state machine

Lessons from building voucha, applied to the more common situation: the state machine is
half-custom — a status column on a few entity tables, application code that moves statuses,
hand-written calls to external systems and hand-written database writes. No generic kernel, no
declarative artifact, no effect subsystem. This document is what still applies from the
platform work, what can be simplified because you are hand-coding, and what you must build as a
substitute for the guarantees the platform would have given you.

The organizing idea survives intact: **the database is the engine**. Your service processes are
disposable. Every guarantee you actually have is a `WHERE` clause, a lock, or a transaction —
never a fact held in memory, and never "this code path is only called once".

## 1. The state machine itself: one table, one chokepoint

Hand-coded does not mean implicit. The two cheapest high-value disciplines:

- **Declare the transitions as data**, even if the engine is plain code: one table/constant of
  `(entity, from-state, to-state)` rows, in one file. Generate your state diagram from it, so
  the diagram cannot lie.
- **One chokepoint for every state write.** A single `transition(entity, to_state, ...)`
  function that refuses pairs not in the table. Scattered `obj.state = X; save()` calls are how
  undeclared transitions creep in and how the diagram and the code drift apart.

Guard the write itself with a compare-and-swap:

```sql
UPDATE entity SET state = 'B', ... WHERE id = ? AND state = 'A'
```

and **check the row count**. Zero rows means a concurrent writer moved the entity first — stop,
re-read, re-decide. This one habit removes the majority of state-machine race bugs, costs
nothing, and needs no framework. Never write a state without saying which state you believe you
are leaving.

Two semantic rules that we relearned the hard way and that are free to adopt:

- **Status ≠ verdict.** The state column encodes process progress ("we could not complete
  screening"); the business outcome ("screening said no", "funding declined") lives in its own
  column. A decline is a *completed* process. Encode verdicts as failure states and every
  aggregate, alert, and report built on states inherits the lie.
- **Terminal is terminal.** Guard every write with `AND state NOT IN (<terminal states>)`. Late
  callbacks *will* arrive for entities you have already closed (the external call was in flight
  while you cancelled — that is by design, not a bug). Refuse the write, but record the answer
  somewhere an operator can see it.

## 2. Hierarchy on a budget

If parents aggregate children (a batch completes when all its transactions settle), the
platform lessons shrink to two rules:

- **Count, don't cache.** Cached counters need current-membership semantics, same-transaction
  maintenance, and decrement-on-leave — all easy to get wrong (an increment-only counter is
  satisfied by a child that entered a counted state and later left it). At hand-rolled scale,
  skip all of it: when a child reaches a terminal state, run a `COUNT` over the children inside
  the same transaction and advance the parent if complete. Only introduce cached counters when
  the count query measurably hurts, and then adopt the full discipline.
- **Lock the parent first.** The classic bug: the last two children finish concurrently, each
  counts "one sibling still open", and nobody advances the parent (or both do). The fix is
  mechanical: `SELECT ... FOR UPDATE` the parent row *before* counting, in the child's own
  transaction. And keep one global lock order — parent before child, always, in every code
  path. Mixed orders deadlock; we found this out empirically, twice.

The same lock rule covers hierarchical *constraints* ("a child may do X only while its parent
is in state Y"): read the parent's state under a lock or you have only formalized the race. An
unlocked read of the parent is worth nothing the moment the check passes.

## 3. External calls, hand-coded

This is where hand-rolled systems lose data. The platform's effect subsystem compresses to four
rules you apply at every call site:

**The transaction boundary is the rollback boundary.** Put inside the state transaction
everything the database can roll back, and nothing it cannot. An external call has three
moments, and they get opposite treatment:

- *Deciding*: the intent — or the transition into a calling state — commits **atomically with
  the state change** that decided it. This half belongs inside.
- *Doing*: the wire call stays **outside**. An external system cannot join your transaction:
  if the call runs inside and the transaction then rolls back (deadlock, constraint violation,
  crash before commit), the payment left but the record didn't — and the retry sends it again.
  Rollback undoes rows; it cannot unsend a request. A transaction retry would also re-execute
  the call, and the open transaction holds your locks for the duration of someone else's
  network latency.
- *Recording*: the outcome and the state change it causes go together in one **new**
  transaction, atomic again.

So the minimal safe pattern is state-first: commit a transition into a "calling" state
(`SENDING`, `DEBITING`, …), *then* make the call, *then* record the outcome in a new
transaction. The gap between "sent" and "recorded" is physically irreducible — no transaction
layout removes it, because a crash can always land between the wire and the commit. Putting the
call inside doesn't close the window, it makes its failure mode worse (sent-but-rolled-back,
inviting a resend). The design's job is to make the window honest: a crash leaves the entity
resting in the calling state, which is the truth — *we may have sent it*. Never "recover" that
state by blindly resending.

**Decide the uncertainty policy per call, at design time.** When the call throws or times out,
the outcome is *unknown* — which is not a failure. For each external call, write down (in code,
next to the call) which of three it is:

- *retry-safe* — the endpoint is idempotent under your idempotency key; resend with backoff,
  bounded, then fail.
- *query* — the system can be asked what happened (by the request id it returned, or your
  key); resolve unknowns by asking, never by resending. A blind resend of a payment
  double-debits.
- *manual* — neither; park the entity in an explicit "needs operator" state.

The unexamined default — catch, log, retry — is a silent choice of *retry-safe* for calls that
are not.

**Send an idempotency key on every request** (derive it from the entity id + operation +
attempt semantics, don't generate a random one per attempt), and **store the correlation
handle** the moment you get one (their request id, your key) so an answer arriving hours later
on a different channel can find its entity. Async replies without correlation are unroutable
exactly when you need them most.

**Dedupe inbound.** Callbacks and queue messages are delivered at-least-once. Keep an inbox
table with a unique constraint on the delivery id (or your correlation key + outcome), insert
before processing, and skip on conflict. Processing a callback twice must be impossible-by-
constraint, not unlikely-by-timing.

The rollback-boundary rule also gives you the exception: **a write to a table in the same ACID
database is not I/O in this sense — put it in the state transaction.** Atomicity is strictly
better than any intent/retry machinery, which exists only for targets that cannot join your
transaction. Only when the write crosses to another database or another service's API does the
effect shape apply, in miniature: write a log row keyed `(entity, target, record-key)` with a
unique constraint, so a repeated write is a no-op — that is what makes the write safe to retry
after a crash.

## 4. Timeouts and stuck entities

Two different timeouts, easily conflated:

- **Dwell timeouts**: entity has been in state X too long. A poller scanning
  `state = ? AND state_changed_at < ?` is enough — store the state-change timestamp on every
  transition (your chokepoint makes this one line).
- **Answer timeouts**: the external system acknowledged and then went silent. This deadline
  belongs to the *request*, not the state — acknowledgement typically moves the entity onward,
  which is precisely what disarms a dwell timeout. Track it on the request/attempt record.

Both fire as ordinary "decide what this means" logic through the chokepoint, guarded by the
same CAS — a timeout racing a real answer must lose cleanly.

And the closing rule: **when an entity goes terminal, close its outstanding work** in the same
transaction — cancel pending intents, and flag anything possibly already sent for an operator.
Otherwise every failed parent leaks orphaned in-flight requests that sit in a queue forever
with nobody to consume their answers.

## 5. Substitutes for what the platform gave you

Without a kernel enforcing invariants, three cheap tools stand in:

- **An append-only transition log.** In the chokepoint, insert `(entity, from, to, cause,
  actor, timestamp)` on every move. It costs one insert and answers "how did this entity get
  here?", "who forced it?", and most audit questions. This is the single highest-value
  substitute — without it, every incident starts from archaeology.
- **A consistency checker**: one script of cross-entity invariant queries you can run anytime
  (and on a schedule): terminal parents with live children; live requests owned by terminal
  entities; entities in a calling state longer than the call could possibly take; counts that
  don't reconcile. The platform prevents these; you instead *detect* them fast, before
  operators do.
- **Refusal with reasons.** When the chokepoint or a guard refuses a move, record *why*
  (expected state vs found, which rule). "Why is this stuck?" must be answerable from stored
  data, not by re-deriving in your head.

One genuine simplification available to you that the platform couldn't assume: **serialize per
entity at the queue level** — partition work so one entity's events are handled by one worker
at a time. Most locking pressure disappears. Keep the CAS guards anyway: queues redeliver,
operators run scripts, and "mostly single-writer" is not a guarantee.

## 6. Testing on a budget

You won't have interception seams, but the failure modes are the same, and all of them are
testable with plain tools:

- Test against a **real database** — the guarantees are claims about locks and transactions,
  which an in-memory fake will happily fail to model.
- Make fakes for external systems **scripted, including misbehavior**: throw after send,
  acknowledge and go silent, answer "unknown", deliver the callback twice, deliver it late
  against an entity you've since closed.
- **Test the crash window** at every call site: inject a throw between the external call and
  the outcome-recording transaction, assert the entity rests in its calling state and that
  your uncertainty policy resolves it. This is the test that would have caught most of the
  real bugs.
- **Test the races with two threads**: last-two-children completing concurrently; a callback
  racing a timeout; a cancel racing an in-flight call. Use a short lock timeout and assert the
  *blocking*, not just the end state.
- If any one concurrency design carries the money, a small **formal model** (TLA+/P) of just
  that part is cheaper than it sounds and finds interleavings no test suite will.

## 7. What you may skip — and when to stop skipping

Permission list, with the tripwire that ends it:

- *Generic kernel / declarative constraints* — skip while the rules fit in guard clauses at
  the chokepoint. Stop when you find the same hierarchy rule hand-coded in a third place.
- *Event-sourced input log* — skip; the transition log + inbound dedupe table cover audit and
  idempotency. Stop if you need replay or dispute-grade reconstruction.
- *Deterministic handlers / replay* — skip; hand-coded logic may read clocks and databases
  freely *because* the transition log records what happened instead.
- *Versioned/pinned machine definitions* — skip while all in-flight entities can tolerate the
  newest rules. Stop the first time a deploy changes the rules under a long-running entity and
  someone asks why March behaved differently.
- *Cached aggregate counters* — skip (count, don't cache) until the count query is a measured
  problem.

What you may **never** skip, at any scale: the CAS guard on every state write, nothing
unrollbackable inside a state transaction (wire calls out; same-database writes in), an
uncertainty decision per external call, idempotency keys out and dedupe in,
terminal-refuses-writes, parent-first locking, and the transition log. Those seven are the
platform, reduced to disciplines.
