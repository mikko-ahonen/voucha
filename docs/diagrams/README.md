# Diagrams

Generated from the compiled definitions by `bin/render-diagrams`, which runs
`voucha graph <definition> --plantuml` and renders the result with PlantUML over Graphviz.
Rerun it after changing a definition; the sources and the renderings are committed so the
documentation renders without tooling.

- [The order example](order.svg) ([source](order.puml)): Order → Item, written in Java.
- [The SEPA example](sepa.svg) ([source](sepa.puml)): File → Batch → Transaction → Attempt, in Clojure.

How to read them: a child node type's machine is drawn inside the parent state in which its
instances live, and that state's start and end markers are the hosted machine's.
Each hierarchy level has its own colour. States are named as the definition names them, with
the level in the prefix. A transition label reads `event / effects`, `^Event` is an event
bubbled up to the parent, and `(guarded)` marks a hierarchy rule.
