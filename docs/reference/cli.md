# Command line and admin API

## `voucha`

`bin/voucha` runs the command line from a checkout. It is equivalent to
`clojure -M:dev -m voucha.cli`. Commands that read or write a database use `VOUCHA_DB_URL`,
`VOUCHA_DB_USER` and `VOUCHA_DB_PASSWORD` from the environment, or the `:db` map in `voucha.edn`
(or the file `VOUCHA_CONFIG` names).

| Command | Does |
|---|---|
| `voucha init <name> [--dir <path>]` | scaffold a project: definition, handlers, smoke test, `deps.edn` |
| `voucha compile <ns/var>` | compile a definition (or an app map holding one) and store the artifact |
| `voucha dev <ns/app-var> [--port <n>] [--scenario <n> [--seed <n>]]` | run kernel, dispatchers, an embedded database, the app's own migrations, data and adapters (fakes for the rest) and the admin API in one process; `--scenario` runs the app's scenario, such as placing n generated orders |
| `voucha profiles` | list the profiles in stored artifacts |
| `voucha inspect <tree> [node]` | a node, or the whole tree |
| `voucha why <tree> <node>` | why the node is where it is: allowed events, blocked transitions and their rules, outstanding effects, parked events |
| `voucha history <tree> <node>` | the node's transitions, with patches, created effects and what each read |
| `voucha chain <tree> <view> <node>` | a chain view over the node |
| `voucha graph <ns/var> [--mermaid \| --plantuml \| --hierarchy]` | the definition as a state diagram: Graphviz by default, Mermaid or PlantUML with the machines nested by containment, or the containment hierarchy alone |
| `voucha send-event <tree> <node> <event> [--operator <id>] [--reason <text>]` | submit an event |
| `voucha replay <tree> [node]` | reconstruct the node's (or tree's) history from the log |
| `voucha verify <tree> <ns/app-var>` | re-run every recorded handler and compare; exit 1 on divergence |
| `voucha check [<ns/app-var>]` | run the invariant oracle over the whole database; with an app var, replay fidelity too; exit 1 on any violation |
| `voucha prove <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn\|file>] [--children T=n,...] [--symmetric field,...] [--out <dir>] [--max-repeat N] [--drop] [--check [--liveness] [--workers N]]` | write a TLA+ specification of the definition under `<dir>` (default `tmp/tla/<profile>`), the same model as `voucha model` with every planned transition exact, and with `--check` run TLC over it: every reachable state of that instance, deduplicated, against the invariants, with `--liveness` also that a terminal node never moves and the tree finishes under weak fairness; `--symmetric tx-id` declares the nodes the payload's `tx-id` fields name interchangeable, so TLC identifies states that differ only by a permutation of them, which needs their records identical apart from the id; exit 1 on a violation with TLC's trace. Needs `tla2tools.jar`: `TLA2TOOLS_JAR` or `~/.local/opt/tla/`. `--symbolic N` and `--inductive [--lemmas <file>] [--bounds n,q,e]` also write the typed specification, which needs the definition's `:data` and `:payloads` schemas, and run Apalache (`APALACHE_MC` or `~/.local/opt/apalache/`): every behaviour of up to N steps, or the three checks of the inductive invariant, which holds for every size when they pass; `--bounds` sizes the states the step check starts from (default 2,2,2: nodes, queued events, effects); the examples need no lemma file; the typed specification carries scalar fields only and creates up to the largest `--children` bound per creating step, see [testing.md](../testing.md) |
| `voucha model <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn\|file>] [--children T=n,...] [--out <dir>] [--max-repeat N] [--drop] [--check [--schedules N]]` | write a complete P model of the definition under `<dir>` (default `tmp/model/<profile>`): the kernel's semantics with the definition's tables, every planned transition translated exactly, a driver that creates the root and sends it the event with the payload, and the specs; `--children` sizes what transitions on code handlers create; with `--check`, compile and run the P checker and exit 1 on a bug, printing its trace |
| `voucha simulate <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn\|file>] [--handlers <ns/var>] [--walks N [--steps N] [--seed N]] [--max-worlds N] [--succeed]` | explore every interleaving of one tree in memory, from the root's first event, with no database; with `--walks`, seeded random walks instead; exit 1 on any violation |

An app var is a map with `:definition`, `:handlers`, `:effect-adapters` and `:store-adapters`,
which is what `voucha init` generates and what `voucha dev` runs. It may also carry
`:migrations` (a resource directory of the app's own migrations), `:setup` (a function of the
datasource, run after them), `:adapters` (a function of the datasource returning
`:effect-adapters` and `:store-adapters` over it) and `:scenario` (a function of datasource,
runtime and `{:artifact-id :n :seed}`), as the order example does.

## Admin API

`voucha dev` serves this on `127.0.0.1:8710` (or `--port`). Bodies are JSON. There is no
authentication; it is a development tool.

| Method and path | Does |
|---|---|
| `POST /tree` `{"tree-id","root-node-id","root-node-type"}` | create a tree under the running artifact |
| `POST /event` `{"tree-id","node-id","event-type","payload"}` | submit an event and process it; returns the outcome |
| `GET /tree/<tree>` | the whole tree |
| `GET /node/<tree>/<node>` | one node |
| `GET /why/<tree>/<node>` | the same answer as `voucha why` |
| `GET /chain/<tree>/<view>/<node>` | a chain view |
| `GET /health` | liveness |
| `POST /simulate/callback` `{"tree-id","correlation-key","effect-name","outcome","payload"}` or `{"external-request-id",...}` | deliver an outcome to a dispatched or acknowledged effect, as the external system's callback would |
| `POST /simulate/timeouts` `{}` | fire every due transition and effect timeout now |
| `POST /debug/install`, `POST /debug/signal`, `POST /debug/reset`, `GET /debug/hits` | the interception facility over HTTP; see [Testing](../testing.md) |

Every effect the app supplies no adapter for is faked: store effects complete, external effects
complete, except that an effect declaring a `:dispatch-event` only reports that it was sent,
so that an asynchronous integration can be exercised with `/simulate/callback`. The startup
banner lists the faked effects.
