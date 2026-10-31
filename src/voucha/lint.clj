(ns voucha.lint
  "Determinism linting (design 58).

  Handlers and operations must not reach the outside world. The structural
  defence is the SDK: a handler receives `ctx` and returns a plan, and there is
  no I/O function on that path. This linter is the second line -- it reads
  handler namespaces and reports references to the forbidden vars and classes.

  It is deliberately advisory rather than a compile gate (decision D9): static
  analysis of dynamic Clojure is leaky, and deterministic replay is the
  authoritative check. A finding here is a strong signal, not a proof."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def forbidden
  "Each entry: a regex over source text, and why it breaks replay."
  [[#"\(System/currentTimeMillis\)" "reads the wall clock; use (facts/transaction-time ctx)"]
   [#"\(System/nanoTime\)" "reads the wall clock; use (facts/transaction-time ctx)"]
   [#"\(Instant/now\)" "reads the wall clock; use (facts/transaction-time ctx)"]
   [#"\(java\.time\.Instant/now\)" "reads the wall clock; use (facts/transaction-time ctx)"]
   [#"\(random-uuid\)" "generates a random id; use (facts/next-id ctx)"]
   [#"\(UUID/randomUUID\)" "generates a random id; use (facts/next-id ctx)"]
   [#"\(rand\b" "non-deterministic; derive the value from event payload"]
   [#"\(rand-int\b" "non-deterministic; derive the value from event payload"]
   [#"\(System/getenv" "reads the environment; pass configuration through the event"]
   [#"\(System/getProperty" "reads the environment; pass configuration through the event"]
   [#"\(slurp\b" "performs filesystem or network I/O; model it as an effect"]
   [#"\(spit\b" "performs filesystem I/O; model it as a store effect"]
   [#"\(future\b" "starts uncontrolled async work"]
   [#"\(Thread\." "starts a thread"]
   [#"\(\.start\s+\(Thread\." "starts a thread"]
   [#"jdbc/execute" "writes a database directly; use (plan/store ...)"]
   [#"next\.jdbc" "reaches the database; runtime-owned writes belong to the kernel"]
   [#"http-kit|clj-http|HttpClient|WebClient|RestTemplate" "performs HTTP; use (plan/call ...)"]])

(defn- ns->path
  [ns-sym]
  (str "src/" (-> (str ns-sym) (str/replace "." "/") (str/replace "-" "_")) ".clj"))

(defn- strip-comments-and-strings
  "Crude, but enough to stop a docstring that *names* Instant/now from tripping
  the linter that forbids *calling* it."
  [src]
  (-> src
      (str/replace #"(?m);.*$" "")
      (str/replace #"\"(?:[^\"\\]|\\.)*\"" "\"\"")))

(defn lint-source
  [source location]
  (let [src (strip-comments-and-strings source)]
    (for [[pattern why] forbidden
          :let [m (re-find pattern src)]
          :when m]
      {:location location :found (str m) :why why})))

(defn lint-ns
  [ns-sym]
  (let [path (ns->path ns-sym)
        f (io/file path)]
    (if (.exists f)
      (lint-source (slurp f) (str ns-sym))
      [{:location (str ns-sym) :found "" :why (str "source not found at " path)}])))

(defn lint
  "Lint the namespaces that define handlers. Returns a vector of findings."
  [ns-syms]
  (vec (mapcat lint-ns ns-syms)))

(defn report
  [findings]
  (if (empty? findings)
    "determinism lint: clean"
    (str/join "\n"
              (cons (format "determinism lint: %d finding(s)" (count findings))
                    (map (fn [{:keys [location found why]}]
                           (format "  %s: %s -- %s" location found why))
                         findings)))))
