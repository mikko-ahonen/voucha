(ns voucha.perf
  "Performance tests that find the platform's bottlenecks (docs/design/performance.md).

  Measurements, not gates: each scenario varies one parameter and reports the
  cost per unit, so a bottleneck shows as the parameter the cost follows. Run on
  demand against the embedded database:

      clojure -X:dev voucha.perf/run! :scenario s1      ; or :all

  Reports go to tmp/perf/<date>/<scenario>.md, the samples as EDN beside them."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [voucha.bench :as bench]
            [voucha.db :as db]
            [voucha.kernel :as kernel]
            [voucha.repo.event :as event]
            [voucha.workload :as w])
  (:import (java.time LocalDate)))

(defn- truncate! [ds]
  (db/execute! ds ["TRUNCATE effect_write_log, runtime_record, timeout_schedule, outbox, effect_attempt,
                    effect_instance, event_log, node_transition_log, state_node, execution_tree,
                    execution_artifact CASCADE"]))

(defn- no-failures [shape]
  (merge shape {:p-invalid 0 :p-rejected 0 :p-screening-transient 0 :p-rail-fail-once 0 :p-rail-permanent 0}))

(defmacro ^:private timed [& body]
  `(let [t0# (System/nanoTime) r# (do ~@body)] [(/ (- (System/nanoTime) t0#) 1e6) r#]))

(defn- fmt [v] (cond (float? v) (format "%.2f" (double v)) (ratio? v) (format "%.2f" (double v)) :else (str v)))

;; --- what the rows say --------------------------------------------------------------------

(defn event-latency
  "The received-to-processed time of every settled event: p50 and p99 in ms, and the count."
  [ds]
  (db/in-tx [tx ds]
    (db/execute-one! tx ["SELECT COUNT(*) AS n,
                                 percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (processed_at - received_at)) * 1000) AS p50,
                                 percentile_cont(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (processed_at - received_at)) * 1000) AS p99
                          FROM event_log WHERE processed_at IS NOT NULL"])))

;; --- S1: event anatomy -----------------------------------------------------------------------

(defn event-anatomy
  "A whole file settled under statement accounting: statements per event by class and table,
  and the share of time each takes. The map every other scenario refines."
  [ds]
  (truncate! ds)
  (let [wl (w/generate 7 (no-failures {:files 1 :batches [2 2] :transactions [5 5]}))
        rt (w/runtime wl)]
    (w/submit! ds wl)
    (let [[[elapsed _] records] (db/with-accounting (timed (w/drive! ds rt wl :pause-ms 0)))
          events (:n (db/in-tx [tx ds] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM event_log WHERE status <> 'RECEIVED'"])))
          summary (db/summarize records)
          total (:total summary)
          rows (->> (dissoc summary :total)
                    (map (fn [[[class table] {:keys [n ms]}]]
                           {:class class :table table :per-event (/ n (double events)) :ms-share (/ ms (:ms total)) :ms-each (/ ms n)}))
                    (sort-by :ms-share >))]
      {:events events :elapsed-ms elapsed :statements (:n total) :statements-per-event (/ (:n total) (double events))
       :statement-ms (:ms total) :statement-share-of-elapsed (/ (:ms total) elapsed)
       :rows rows
       ;; the individual statements that cost most, by text
       :top (->> (group-by :sql records)
                 (map (fn [[sql rs]] {:sql sql :n (count rs) :ms (reduce + 0.0 (map :ms rs))}))
                 (sort-by :ms >)
                 (take 8)
                 (map #(assoc % :per-event (/ (:n %) (double events)) :ms-each (/ (:ms %) (:n %)) :ms-share (/ (:ms %) (:ms total)))))
       :latency (event-latency ds)})))

;; --- S1b: transaction anatomy ----------------------------------------------------------------

(defn transaction-anatomy
  "Where a transaction's fixed cost sits: the first statement (which carries the BEGIN), the
  statements after it, and the commit. Twenty transactions of five `SELECT 1`."
  [ds]
  (let [reps 20
        samples (vec (repeatedly reps
                                 (fn []
                                   (let [t0 (System/nanoTime)
                                         times (db/in-tx [tx ds]
                                                 (vec (repeatedly 5 #(first (timed (db/execute-one! tx ["SELECT 1 AS one"]))))))
                                         total (/ (- (System/nanoTime) t0) 1e6)]
                                     {:first (first times) :rest (/ (reduce + (rest times)) 4.0) :total total
                                      :outside (- total (reduce + times))}))))
        avg (fn [k] (/ (reduce + (map k samples)) (double reps)))]
    ;; the pieces outside the statements, each timed alone on a raw connection
    (let [piece (fn [f] (/ (reduce + (repeatedly reps f)) (double reps)))
          checkout (piece #(first (timed (with-open [c (jdbc/get-connection ds)] nil))))
          [autocommit-off commit autocommit-on]
          (with-open [^java.sql.Connection c (jdbc/get-connection ds)]
            [(piece #(first (timed (.setAutoCommit c false))))
             (piece #(do (db/execute-one! c ["SELECT 1 AS one"]) (first (timed (.commit c)))))
             (piece #(first (timed (do (.setAutoCommit c true) (.setAutoCommit c false)))))])]
      {:transaction-ms (avg :total) :first-statement-ms (avg :first) :later-statement-ms (avg :rest)
       :outside-statements-ms (avg :outside)
       :checkout-ms checkout :autocommit-off-ms autocommit-off :commit-ms commit :autocommit-toggle-ms autocommit-on})))

;; --- S2: claim cost --------------------------------------------------------------------------

(defn claim-cost
  "The claim query's cost against the size of the pending set: events for distinct nodes,
  10^3, 10^4, 10^5, with and without the pending-order index."
  [ds]
  (truncate! ds)
  (let [wl (w/generate 9 (no-failures {:files 1 :batches [1 1] :transactions [1 1]}))
        {:keys [tree-id]} (first (:files wl))]
    (w/submit! ds wl)
    (vec (for [n [1000 10000 100000]]
           (do (db/in-tx [tx ds]
                 (db/execute! tx ["DELETE FROM event_log WHERE event_id LIKE 'claim-%'"])
                 (db/execute! tx [(str "INSERT INTO event_log (event_id, tree_id, target_node_id, event_type, payload, status, received_at)
                                        SELECT 'claim-' || i, ?, 'N' || i, 'Nothing', '{}'::jsonb, 'RECEIVED', now() - (i || ' ms')::interval
                                        FROM generate_series(1, " n ") AS i") tree-id]))
               (db/execute! ds ["ANALYZE event_log"])
               (let [reps 20
                     ;; both measured inside one transaction, so the transaction's own cost (measured
                     ;; apart, below) does not stand in for the query's
                     claim-in-tx (fn [tx] (/ (reduce + (repeatedly reps #(first (timed (event/claim-pending tx 1))))) (double reps)))
                     plan (fn [conn] (->> (db/execute! conn ["EXPLAIN SELECT * FROM event_log e
                                                               WHERE e.status = 'RECEIVED'
                                                                 AND NOT EXISTS (SELECT 1 FROM event_log o
                                                                                  WHERE o.tree_id = e.tree_id AND o.target_node_id = e.target_node_id
                                                                                    AND o.status = 'RECEIVED'
                                                                                    AND (o.received_at, o.event_id) < (e.received_at, e.event_id))
                                                               ORDER BY e.received_at, e.event_id LIMIT 1 FOR UPDATE SKIP LOCKED"])
                                          (map (comp str first vals))
                                          (take 3)
                                          (str/join " / ")))
                     [with-index plan-with] (db/in-tx [tx ds] (claim-in-tx tx) [(claim-in-tx tx) (plan tx)])
                     tx-overhead (/ (reduce + (repeatedly reps #(first (timed (db/in-tx [tx ds] (db/execute-one! tx ["SELECT 1 AS one"])))))) (double reps))
                     ;; the index dropped inside a transaction that only rolls back
                     [without-index plan-without]
                     (jdbc/with-transaction [tx ds {:rollback-only true}]
                       (db/execute! tx ["DROP INDEX ix_event_log_pending_order"])
                       [(claim-in-tx tx) (plan tx)])]
                 {:pending n :claim-ms with-index :claim-ms-without-index without-index :tx-overhead-ms tx-overhead
                  :plan plan-with :plan-without-index plan-without}))))))

;; --- running and reporting ---------------------------------------------------------------------

(def scenarios
  {:s1 {:name "S1 event anatomy" :f event-anatomy}
   :s1b {:name "S1b transaction anatomy" :f transaction-anatomy}
   :s2 {:name "S2 claim cost" :f claim-cost}})

(defn- report-s1 [{:keys [events elapsed-ms statements statements-per-event statement-ms statement-share-of-elapsed rows top latency]}]
  (concat
   [(format "events %d, elapsed %.0f ms, %d statements: %.1f per event, %.0f%% of elapsed in statements" events elapsed-ms statements statements-per-event (* 100 statement-share-of-elapsed))
    (format "latency p50 %.1f ms, p99 %.1f ms over %d events" (double (:p50 latency)) (double (:p99 latency)) (:n latency))
    "" "| class | table | per event | share of statement time | ms each |" "|---|---|---|---|---|"]
   (for [{:keys [class table per-event ms-share ms-each]} rows]
     (format "| %s | %s | %.2f | %.0f%% | %.2f |" (name class) (or table "-") per-event (* 100 ms-share) ms-each))
   ["" "The statements that cost most:" "" "| statement | per event | share | ms each |" "|---|---|---|---|"]
   (for [{:keys [sql per-event ms-share ms-each]} top]
     (format "| `%s` | %.2f | %.0f%% | %.2f |" sql per-event (* 100 ms-share) ms-each))))

(defn- report-s1b [{:keys [transaction-ms first-statement-ms later-statement-ms outside-statements-ms checkout-ms autocommit-off-ms commit-ms autocommit-toggle-ms]}]
  [(format "a transaction of five SELECT 1: %.2f ms; the first statement %.2f ms, each later one %.2f ms, outside the statements %.2f ms"
           transaction-ms first-statement-ms later-statement-ms outside-statements-ms)
   (format "the pieces outside, alone: pool checkout and return %.2f ms, autocommit off %.2f ms, commit after a read %.2f ms, autocommit on and off again %.2f ms"
           checkout-ms autocommit-off-ms commit-ms autocommit-toggle-ms)])

(defn- report-s2 [rows]
  (concat ["| pending | claim ms, with the index | claim ms, without | one transaction with SELECT 1, ms |" "|---|---|---|---|"]
          (for [{:keys [pending claim-ms claim-ms-without-index tx-overhead-ms]} rows]
            (format "| %d | %.2f | %.2f | %.2f |" pending claim-ms claim-ms-without-index tx-overhead-ms))
          [""]
          (for [{:keys [pending plan plan-without-index]} rows]
            (format "- %d pending, plan with the index: %s; without: %s" pending plan plan-without-index))))

(defn- write-report! [key result]
  (let [dir (io/file (str "tmp/perf/" (LocalDate/now)))
        lines (case key :s1 (report-s1 result) :s1b (report-s1b result) :s2 (report-s2 result))]
    (.mkdirs dir)
    (spit (io/file dir (str (name key) ".md")) (str "# " (:name (scenarios key)) "\n\n" (str/join "\n" lines) "\n"))
    (spit (io/file dir (str (name key) ".edn")) (with-out-str (pp/pprint result)))
    (doseq [l lines] (println l))
    (println "report:" (str (io/file dir (str (name key) ".md"))))))

(defn run!
  "`clojure -X:dev voucha.perf/run! :scenario s1` (or :all)."
  [{:keys [scenario] :or {scenario :all}}]
  (let [keys* (if (= :all (keyword scenario)) (keys scenarios) [(keyword scenario)])]
    (bench/with-embedded-db
      (fn [ds]
        (doseq [k keys*]
          (println "==" (:name (scenarios k)))
          (write-report! k ((:f (scenarios k)) ds)))))))
