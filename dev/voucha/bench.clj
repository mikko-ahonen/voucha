(ns voucha.bench
  "Performance budgets and the trend ledger (test plan T6).

  A few robust metrics, measured in-process against an embedded PostgreSQL:
  kernel throughput on a seeded workload, hot-parent fan-out (many children
  folding counters into one parent row), event-pipeline overhead draining a
  backlog, the invariant checker's cost, and replay's cost. Budgets live in
  `docs/testing/budgets.edn` as data; `budget_test` gates the suite (and so CI)
  on them with a tolerance generous enough for shared runners. Precise numbers
  go to the ledger: `clojure -X:test voucha.bench/record!` appends a row to
  `docs/testing/results/bench-ledger.md` with the commit it measured.

  The deeper scale series (10^5 nodes, depth cost, lock-hold profiles) is a
  nightly job built on the chaos harness, not this file."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.invariants :as inv]
            [voucha.kernel :as kernel]
            [voucha.migrate :as migrate]
            [voucha.workload :as w])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(def budgets-file "docs/testing/budgets.edn")
(def ledger-file "docs/testing/results/bench-ledger.md")

;; Scenario sizes: large enough to measure, small enough for every CI run.
(def ^:private throughput-shape {:files 1 :batches [5 5] :transactions [10 10]})
(def ^:private fanout-children 200)
(def ^:private backlog-events 1500)
(def ^:private warmup-shape {:files 1 :batches [1 1] :transactions [2 2]})

(defn- no-failures [shape]
  (merge shape {:p-invalid 0 :p-rejected 0 :p-screening-transient 0 :p-rail-fail-once 0 :p-rail-permanent 0}))

(defmacro ^:private timed
  "[elapsed-ms result]"
  [& body]
  `(let [t0# (System/nanoTime) r# (do ~@body)]
     [(/ (- (System/nanoTime) t0#) 1e6) r#]))

(defn- processed-events [ds]
  (:n (db/in-tx [tx ds] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM event_log WHERE status <> 'RECEIVED'"]))))

(defn- truncate! [ds]
  (db/execute! ds ["TRUNCATE effect_write_log, runtime_record, timeout_schedule, outbox, effect_attempt,
                    effect_instance, event_log, node_transition_log, state_node, execution_tree,
                    execution_artifact CASCADE"]))

(defn- drive-workload!
  "Seeded workload driven to completion in-process. Returns {:ms :events :events-per-s :settled?}."
  [ds seed shape]
  (let [wl (w/generate seed (no-failures shape))
        rt (w/runtime wl)]
    (w/submit! ds wl)
    (let [[elapsed driven] (timed (w/drive! ds rt wl :pause-ms 0))
          events (processed-events ds)]
      {:ms elapsed :events events :events-per-s (/ events (/ elapsed 1000.0)) :settled? (:settled? driven)})))

;; --- scenarios ----------------------------------------------------------------------------

(defn throughput
  "A whole file paid end to end: kernel + dispatchers + callbacks, one thread."
  [ds]
  (truncate! ds)
  (drive-workload! ds 7 throughput-shape))

(defn hot-parent-fanout
  "One batch, many transactions: every child's milestone folds a counter into the
  same parent row, serializing on it. The T4 contention profile, measured serially."
  [ds]
  (truncate! ds)
  (drive-workload! ds 8 {:files 1 :batches [1 1] :transactions [fanout-children fanout-children]}))

(defn backlog-drain
  "Event-pipeline overhead: many events queued while no worker runs, then drained.
  Each is a full kernel transaction (claim, lock, resolve, record), with no
  transition work to speak of."
  [ds]
  (truncate! ds)
  (let [wl (w/generate 9 (no-failures warmup-shape))
        rt (w/runtime wl)
        {:keys [tree-id root-node-id]} (first (:files wl))]
    (w/submit! ds wl)
    (db/in-tx [tx ds]
      (doseq [i (range backlog-events)]
        (kernel/submit-event! tx {:event-id (str "backlog-" i) :tree-id tree-id :target-node-id root-node-id
                                  :event-type "ResultFileWritten" :payload {}})))
    (let [[elapsed outcomes] (timed (kernel/drain! ds rt))
          n (count outcomes)]
      {:ms elapsed :events n :events-per-s (/ n (/ elapsed 1000.0))})))

(defn invariant-check
  "The oracle's own cost over the database left by the throughput scenario."
  [ds]
  (truncate! ds)
  (drive-workload! ds 7 throughput-shape)
  (let [[elapsed r] (timed (db/in-tx [tx ds] (inv/check-all tx)))]
    {:ms elapsed :ok? (:ok? r)}))

(defn replay-check
  "Replay fidelity (I10) over the same database: the audit's cost."
  [ds]
  (let [[elapsed r] (timed (db/in-tx [tx ds] (inv/replay-fidelity tx app/handlers)))]
    {:ms elapsed :divergences (count r)}))

(defn run-all
  "Every scenario, after a warm-up run. Returns {metric {:value :unit ...}}."
  [ds]
  (truncate! ds)
  (drive-workload! ds 1 warmup-shape)
  (let [t (throughput ds)
        f (hot-parent-fanout ds)
        b (backlog-drain ds)
        i (invariant-check ds)
        r (replay-check ds)]
    {:throughput-events-per-s {:value (:events-per-s t) :unit :events-per-s :higher-is-better true :detail t}
     :fanout-events-per-s     {:value (:events-per-s f) :unit :events-per-s :higher-is-better true :detail f}
     :backlog-events-per-s    {:value (:events-per-s b) :unit :events-per-s :higher-is-better true :detail b}
     :invariant-check-ms      {:value (:ms i) :unit :ms :higher-is-better false :detail i}
     :replay-check-ms         {:value (:ms r) :unit :ms :higher-is-better false :detail r}}))

;; --- budgets and the gate -------------------------------------------------------------------

(defn load-budgets []
  (when (.exists (io/file budgets-file)) (edn/read-string (slurp budgets-file))))

(defn gate
  "Compare results with budgets. A metric regresses when it is worse than its
  budget by more than the tolerance. Returns {:ok? :regressions [..] :unbudgeted [..]}."
  [results budgets]
  (let [checks (for [[k {:keys [value higher-is-better]}] results
                     :let [{:keys [budget tolerance] :or {tolerance 0.5}} (get budgets k)]]
                 (cond
                   (nil? budget) {:metric k :unbudgeted true :value value}
                   higher-is-better (let [floor (* budget (- 1 tolerance))]
                                      (when (< value floor)
                                        {:metric k :value value :budget budget :floor floor}))
                   :else (let [ceiling (* budget (+ 1 tolerance))]
                           (when (> value ceiling)
                             {:metric k :value value :budget budget :ceiling ceiling}))))
        checks (remove nil? checks)]
    {:ok? (empty? (remove :unbudgeted checks))
     :regressions (vec (remove :unbudgeted checks))
     :unbudgeted (vec (filter :unbudgeted checks))}))

(defn- fmt [v] (if (number? v) (format "%.1f" (double v)) (str v)))

(defn report [results {:keys [regressions unbudgeted] :as gate-result}]
  (into [(format "benchmarks: %s" (if (:ok? gate-result) "within budget" "REGRESSION"))]
        (concat
         (map (fn [[k {:keys [value unit]}]] (format "  %-28s %10s %s" (name k) (fmt value) (name unit))) (sort results))
         (map (fn [{:keys [metric value budget floor ceiling]}]
                (format "  REGRESSION %s: %s vs budget %s (%s %s)" (name metric) (fmt value) (fmt budget)
                        (if floor "floor" "ceiling") (fmt (or floor ceiling))))
              regressions)
         (map (fn [{:keys [metric]}] (format "  (no budget for %s)" (name metric))) unbudgeted))))

;; --- the ledger ---------------------------------------------------------------------------------

(defn- git-sha [] (str/trim (:out (sh/sh "git" "rev-parse" "--short" "HEAD"))))

(def ^:private ledger-columns
  [:throughput-events-per-s :fanout-events-per-s :backlog-events-per-s :invariant-check-ms :replay-check-ms])

(defn append-ledger!
  "One row per run: date, commit, every metric. Creates the file with its header."
  [results]
  (let [f (io/file ledger-file)
        header (str "| date | commit | " (str/join " | " (map name ledger-columns)) " |\n"
                    "|---|---|" (str/join (repeat (count ledger-columns) "---|")) "\n")
        row (str "| " (java.time.LocalDate/now) " | " (git-sha) " | "
                 (str/join " | " (map #(fmt (get-in results [% :value])) ledger-columns)) " |\n")]
    (.mkdirs (.getParentFile f))
    (when-not (.exists f)
      (spit f (str "# Benchmark ledger\n\nOne row per `clojure -X:test voucha.bench/record!` run, in-process against an\n"
                   "embedded PostgreSQL on whatever machine ran it -- compare trends, not machines.\n"
                   "Budgets: `docs/testing/budgets.edn`.\n\n" header)))
    (spit f row :append true)
    (str f)))

;; --- entry points --------------------------------------------------------------------------------

(defn with-embedded-db
  "An embedded PostgreSQL behind the same pool production uses. The embedded
  server's own datasource opens a connection per request, 3 ms each on this
  host, which the perf scenarios found standing in for the kernel's cost."
  [f]
  (let [pg (.start (EmbeddedPostgres/builder))
        ds (db/pool {:jdbc-url (.getJdbcUrl ^EmbeddedPostgres pg "postgres" "postgres") :max-pool-size 10})]
    (try (migrate/migrate! ds) (f ds)
         (finally (db/close-pool! ds) (.close ^EmbeddedPostgres pg)))))

(defn run!
  "Measure everything on a fresh embedded database. Returns results."
  []
  (with-embedded-db run-all))

(defn record!
  "Measure, print, gate against the budgets, and append to the ledger.
  `clojure -X:test voucha.bench/record!`"
  [& _]
  (let [results (run!)
        g (gate results (load-budgets))]
    (doseq [l (report results g)] (println l))
    (println "ledger:" (append-ledger! results))
    (when-not (:ok? g) (System/exit 1))))
