# The SQL interface

The schema `voucha` is the interface an application uses from inside its own transactions: it
starts trees, submits events, intervenes, reads, and carries out effects as a *participant*,
each in the same transaction as its own rows. Every language with a PostgreSQL driver
integrates this way; the worker (kernel and dispatcher) runs beside the database and is never
addressed. The design note is `docs/design/sql-interface.md`.

## Functions

| Function | Returns | What it does |
|---|---|---|
| `voucha.create_tree(tree_id, artifact_id, root_node_id, root_node_type, profile_name, profile_version)` | the tree id | the tree, active, and its root in the node type's initial state; the profile fields default to null |
| `voucha.submit_event(tree_id, target_node_id, event_type, payload jsonb, event_id, operator_id, operator_reason, actor, claims jsonb, correlation_key)` | the event id | the event, received, for the worker to process. `payload` defaults to `{}`; `event_id` defaults to a hash of the content, so the same submission twice is one event; everything after it defaults to null. A null target is an event that bubbles along the definition's routes |
| `voucha.intervene(tree_id, target_node_id, event_type, payload, operator_id, reason)` | the event id | `submit_event` with the operator and the reason required |
| `voucha.claim_effects(effect_name, participant, n)` | rows | up to `n` effects of that name waiting for a participant, oldest first, now `DISPATCHING` and held by `participant` |
| `voucha.report_outcome(effect_instance_id, participant, outcome, payload, external_request_id)` | the report id | the outcome, `completed` or `failed`, of an effect this participant holds |
| `voucha.extend_lease(effect_instance_id, participant)` | | renews the lease on an effect this participant holds |

All of them raise inside the caller's transaction, with a `voucha:` message, on an unknown
tree, artifact, node, node type or event type, so the application finds out before it commits.
`create_tree` needs the artifact's catalog (below); `submit_event` checks the event type only
when the catalog is there. The worker's own submit and create, the Clojure `submit-event!`
and `create-tree!` behind the admin API and the CLI, call these same functions after the
edge-auth seam has run, so the contract has one implementation and one event-id derivation.

## Views

`voucha.trees`, `voucha.nodes` (`state`, `data`), `voucha.events`, `voucha.effects` (with
`participant`, `claimed_by` and `last_report`) and `voucha.transitions` are the platform's
tables under stable names. The reasoning over them, `voucha why` and the histories, stays in
the CLI and the admin API.

## The participant protocol

An effect declared with `:participant :sql` (Java: `.participant("sql")`) is not dispatched by
an adapter. It waits in `CREATED` until a participant claims it:

```sql
BEGIN;
SELECT effect_instance_id, request_payload, idempotency_key, attempt
  FROM voucha.claim_effects('inventory.reserve', 'warehouse-1', 10);
-- do the work, write your rows, keyed by the idempotency key
SELECT voucha.report_outcome('<effect_instance_id>', 'warehouse-1', 'completed', '{"reservation": "RSV-1"}');
COMMIT;
```

The claim's row carries the effect instance id, the tree and owner node, the request payload,
the idempotency key, the uncertainty policy and the attempt number. The report is a row in
`effect_report` committed with the work: both or neither. The worker's dispatcher applies
pending reports on its next turn, through the path a callback takes: the effect reaches
`COMPLETED` or `FAILED` and the owner node receives the completion or failure event with the
report's payload. A report on an effect that had gone terminal meanwhile is kept and marked
`SUPERSEDED`.

The claim is a lease, five minutes by default (`:dispatcher :lease-ms`). An effect left in
`DISPATCHING` past it is reclaimed and resolved by its uncertainty policy: `:retry-safe` makes
it claimable again after a backoff, as the next attempt; `:manual` parks it for an operator.
`:query` is not allowed with a participant, because no adapter could ask. A report from the
former holder after a reclaim is refused, so the work it was committing with rolls back, and
the retry redoes it under the same idempotency key. A participant that needs longer calls
`extend_lease` before the lease runs out.

## The catalog

The functions read a few facts of the artifact from `execution_artifact.catalog`, JSON the
artifact store writes when the artifact is inserted: the event names, the node types' initial
states, and the effects' participants and outcome events. An artifact stored before this
column existed has none until it is stored again (`voucha compile`).

## Roles

The functions are `SECURITY DEFINER`, `EXECUTE` on them is revoked from `PUBLIC`, and the
views are plain, so a deployment grants by role and never on the tables: a reader gets
`SELECT` on the views; an application `EXECUTE` on `create_tree` and `submit_event`; an
operator on `intervene` and the `metrics_*` functions; each participant on `claim_effects`,
`report_outcome` and `extend_lease`; and the worker process its whole surface with
`SELECT voucha.grant_worker('<role>')`. The worker runs on that alone: it has no privilege on
any table. The roles are the deployment's and are not created by the migrations.

```sql
CREATE ROLE voucha_worker LOGIN PASSWORD '...';
SELECT voucha.grant_worker('voucha_worker');
CREATE ROLE shop_app LOGIN PASSWORD '...';
GRANT USAGE ON SCHEMA voucha TO shop_app;
GRANT EXECUTE ON FUNCTION voucha.create_tree(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO shop_app;
GRANT EXECUTE ON FUNCTION voucha.submit_event(TEXT, TEXT, TEXT, JSONB, TEXT, TEXT, TEXT, TEXT, JSONB, TEXT) TO shop_app;
GRANT SELECT ON ALL TABLES IN SCHEMA voucha TO shop_app;
```

## Example

The order example's warehouse runs as a participant in `voucha.example.order-participant`:
its `definition` hands the two inventory effects to a participant, and `run-once!` claims, reserves
or releases, and reports, one transaction per effect. `tests/voucha/sql_interface_test.clj`
drives an order placed in SQL through it.
