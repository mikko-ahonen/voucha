# Benchmark ledger

One row per `clojure -X:test voucha.bench/record!` run, in-process against an
embedded PostgreSQL on whatever machine ran it -- compare trends, not machines.
Budgets: `docs/testing/budgets.edn`.

| date | commit | throughput-events-per-s | fanout-events-per-s | backlog-events-per-s | invariant-check-ms | replay-check-ms |
|---|---|---|---|---|---|---|
| 2026-10-25 | b3d1f48 | 36.7 | 36.0 | 15.0 | 36.4 | 126.8 |
| 2026-10-31 | 9f2449d | 132.1 | 105.9 | 215.3 | 35.0 | 135.5 |
| 2026-10-31 | eaa0c2b | 268.1 | 235.9 | 876.5 | 26.9 | 114.0 |
