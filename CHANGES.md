# Changes

## 0.1.0

First public release.

- Kernel: one event, one database transaction, one checkpoint; hierarchical trees with
  kernel-maintained counters and declarative hierarchy rules; version-pinned, content-addressed
  artifacts.
- Compiler: structural, reachability and liveness checks over the definition, each error naming
  its fix.
- Effects: intents recorded in the kernel transaction and executed after commit; retry-safe,
  query and manual uncertainty policies; leases for lost dispatchers; transition and effect
  timeouts; cancellation on owner terminality with operator flagging.
- SDKs: Clojure plan API and Java annotations over the same core.
- Tooling: the `voucha` command (init, compile, dev, inspect, why, history, chain, graph,
  send-event, replay, verify, check, export-model) and a development runtime with an embedded
  database, fakes and an admin API.
- Testing: process harness, scripted fakes, seeded workloads with an expected-state oracle,
  an in-memory simulator over an interleaving-exploring model, an invariant oracle, deterministic
  replay, fault interception, a hard-kill drill, and performance budgets.
- Example: a SEPA credit-transfer file in public ISO 20022 and EPC vocabulary.
