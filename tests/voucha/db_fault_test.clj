(ns voucha.db-fault-test
  "Database-fault injection on the chaos harness (test plan T3), server-side.

  The faults a real deployment sees and no unit test produces: connections
  terminated mid-transaction, lock timeouts under contention, a starved
  connection pool. The expectation is the same every time -- nothing partial,
  the work retried on the next pass, every invariant intact, and the workload
  complete with its expected states.

  Slow (every worker is a JVM); the sustained fault runs belong to the nightly
  tier."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.chaos :as chaos]
            [voucha.db :as db]
            [voucha.test-db :as tdb :refer [*ds*]]
            [voucha.workload :as w]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private config
  {:dispatcher {:batch-size 50 :backoff-ms 50 :max-attempts 5 :lease-ms 3000 :poll-interval-ms 50}
   ;; Transient faults must never exhaust an event's budget: the budget is for
   ;; poison, not for a sibling's lock or a killed connection (D7).
   :event {:max-attempts 50}})

(defn- run [workload opts]
  (let [r (chaos/run! *ds* workload (merge {:jdbc-url (tdb/jdbc-url) :config config :max-steps 600} opts))]
    (doseq [line (chaos/report r)] (println line))
    r))

(defn- faults-applied [r] (reduce + 0 (map :count (filter #(= :fault (:event %)) (:schedule r)))))
(defn- parked-events []
  (:n (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM event_log WHERE status = 'PARKED'"]))))

(defn- assert-clean [r]
  (is (:settled? r) (pr-str (:file-states r)))
  (is (empty? (:violations r)) (pr-str (take 5 (:violations r))))
  (is (empty? (get-in r [:verify :mismatches])) (pr-str (get-in r [:verify :mismatches])))
  (is (:ok? (get-in r [:verify :invariants])) (pr-str (get-in r [:verify :invariants :violations])))
  (is (zero? (parked-events)) "a transient fault never parks an event within budget"))

(deftest connections-terminated-mid-transaction-lose-nothing
  ;; The kernel holds every transaction open 400 ms at the commit boundary, and
  ;; the controller terminates the kernel's backends every other step for the
  ;; first forty. Each kill lands mid-transaction: the server rolls it back, the
  ;; event is retried next pass, and the checkpoint is either whole or absent.
  (let [workload (w/generate 404 {:files 2 :batches [1 2] :transactions [1 3]})
        r (run workload {:kernels 2 :dispatchers 1 :rails 1
                         :worker-flags {"kernel" ["--sleep-at" ":transition/before-commit" "--sleep-ms" "400"]}
                         :faults [{:fault :terminate-backends :role "kernel" :every 2 :from-step 4 :until-step 40}]})]
    (is (pos? (faults-applied r)) "terminations actually hit live kernel transactions")
    (assert-clean r)
    (is (:ok? r))))

(deftest a-lock-timeout-storm-is-absorbed-by-next-pass-retry
  ;; One kernel dawdles 300 ms with its ancestor locks held; its sibling runs with
  ;; a 100 ms lock_timeout and fails fast on every contended parent. Nothing is
  ;; double-applied and nothing parks: the retry budget is for poison, not for a
  ;; sibling's lock.
  (let [workload (w/generate 505 {:files 1 :batches [2 2] :transactions [3 4]})
        r (run workload {:kernels 2 :dispatchers 1 :rails 1
                         :worker-flags {["kernel" 0] ["--sleep-at" ":transition/context-loaded" "--sleep-ms" "300"]
                                        ["kernel" 1] ["--lock-timeout-ms" "100"]}})]
    (assert-clean r)
    (is (:ok? r))))

(deftest a-starved-connection-pool-degrades-without-hanging
  ;; Every worker has a single connection and a short checkout timeout. The
  ;; classic self-inflicted hang -- waiting on the pool while holding a
  ;; transaction -- would stall the workload forever; completing it is the proof.
  (let [workload (w/generate 606 {:files 1 :batches [1 2] :transactions [1 3]})
        r (run workload {:kernels 1 :dispatchers 1 :rails 1
                         :worker-flags {"kernel" ["--pool-size" "1" "--connection-timeout-ms" "1500"]
                                        "dispatcher" ["--pool-size" "1" "--connection-timeout-ms" "1500"]
                                        "rail" ["--pool-size" "1" "--connection-timeout-ms" "1500"]}})]
    (assert-clean r)
    (is (:ok? r))))
