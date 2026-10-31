# Effect lifecycle

An effect is a request to the outside world, recorded as an intent in the kernel transaction and
performed by a dispatcher after commit. This page is the complete lifecycle.

## Statuses

| Status | Meaning |
|---|---|
| `CREATED` | the intent is committed; no dispatcher has claimed it |
| `DISPATCHING` | a dispatcher has claimed it and is calling the adapter |
| `DISPATCHED` | the request left; nothing has come back yet, or the outcome is unknown and the policy is `query` |
| `ACKNOWLEDGED` | the external system accepted the request and returned a request id, but has not finished |
| `COMPLETED` | done; the completion event has been delivered to the owner |
| `FAILED` | the external system gave a definite refusal; the failure event has been delivered |
| `DISPATCH_FAILED` | the call failed or its outcome is unknown; waiting for a retry, or for an operator if `requires-operator` is set |
| `TIMED_OUT` | the effect's own completion timeout elapsed; the timeout event has been delivered |
| `CANCELLED` | the owning node reached a terminal state with the effect still outstanding |

`COMPLETED`, `FAILED`, `TIMED_OUT` and `CANCELLED` are terminal. Every change is recorded as an
attempt on the effect, with its outcome or error, so the history of an effect is readable from
`voucha inspect` on its owner.

## Adapter outcomes

The dispatcher calls `dispatch` on the adapter registered for the effect's name and applies what
it returns.

| Return | New status | Event delivered |
|---|---|---|
| `{:outcome :completed :payload m}` | `COMPLETED` | `:completion-event` with `m` as payload |
| `{:outcome :failed :payload m}` | `FAILED` | `:failure-event` |
| `{:outcome :acknowledged :external-request-id id :payload m}` | `ACKNOWLEDGED` | `:ack-event` |
| `{:outcome :dispatched :external-request-id id?}` | `DISPATCHED` | `:dispatch-event` |
| throws | per policy, below | none yet |

An outcome event an effect does not declare is simply not delivered; the effect still moves. A
`query` effect must also implement `query-status`, which returns the same shape or
`{:outcome :unknown}`.

## An unknown outcome

When the adapter throws, the request may or may not have reached the other side. The effect's
`:uncertainty-policy` decides:

| Policy | What the dispatcher does |
|---|---|
| `:retry-safe` | status `DISPATCH_FAILED`, retry count incremented, next attempt after a backoff; the request is sent again under the same idempotency key, up to the dispatcher's `:max-attempts`, after which it is `DISPATCH_FAILED` with `requires-operator` |
| `:query` | calls `query-status`; applies its answer as above. If the answer is `:unknown`, status `DISPATCHED` with a next attempt scheduled, and the query is repeated; the request itself is never sent again |
| `:manual` | status `DISPATCH_FAILED` with `requires-operator`; nothing further happens until an operator delivers the outcome |

A dispatcher that dies between calling the adapter and recording the result leaves the effect
`DISPATCHING`. When its lease expires (the dispatcher's `:lease-ms`), another dispatcher reclaims
it and resolves it under the same policy, recording the lost call as an attempt in error.

## Callbacks

A system that answers later delivers its answer with `voucha.dispatcher/deliver-callback!`,
naming the effect by `:effect-instance-id`, by `:external-request-id`, or by `:tree-id`,
`:correlation-key` and `:effect-name` together. The outcome and payload are applied exactly as
an adapter's return would be. A callback for an effect that is already terminal is refused, as is
a correlation key that matches more than one live effect.

## Timeouts

A **transition timeout**, armed by `plan/timeout-after`, fires an event at the node if it is
still in the state the transition entered when the delay elapses. It is disarmed the moment the
node leaves that state, including when an effect's dispatch or acknowledgement event moves it.

An **effect timeout**, declared with `:completion-timeout-ms` and `:timeout-event` on the effect,
covers the case the transition timeout cannot: an effect that was dispatched or acknowledged and
then never completed. When it elapses the effect becomes `TIMED_OUT` and its timeout event
drives the owner.

## Cancellation

When a node reaches a terminal state, every non-terminal effect it owns is cancelled. An effect
cancelled from `CREATED` or `DISPATCH_FAILED` never left the runtime and is simply closed. One
cancelled from `DISPATCHING`, `DISPATCHED` or `ACKNOWLEDGED` may be held by the external system:
it is closed with `requires-operator` set and its prior status recorded, and it is listed by
`voucha.metrics/unresolved-external-work` until someone reconciles it.

## Participants

An effect declared with `:participant :sql` is not dispatched by an adapter. It waits in
`CREATED` until a participant claims it with `voucha.claim_effects`, which moves it to
`DISPATCHING` under a lease, does the work, and reports with `voucha.report_outcome` in the same
transaction. The dispatcher applies the report as it would a callback. See the
[SQL interface](sql-interface.md).

## Store effects

A store effect is a write to a database outside the kernel's tables, performed by a
`StoreAdapter`. The adapter returns `{:target .. :record-key .. :payload ..}`, and the kernel
records the write under a unique (effect, target, record key) so a repeated write is a no-op.
Store effects are retry-safe by nature and go straight to `COMPLETED`.

## Idempotency keys

Two intents with the same idempotency key are the same intent: creating the second returns the
first. The key is built from the effect's name and version plus the fields named in its
`:idempotency-key`, taken from the effect's context (`:tree-id`, `:node-id`) and from the request
the handler passed. A replayed transition therefore collides with the intent it already created,
while a handler that re-issues an effect in a retry cycle and puts the cycle number in the
request mints a new one.
