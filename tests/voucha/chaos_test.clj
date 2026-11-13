(ns voucha.chaos-test
  "The chaos harness (test plan T0 §6.4) and the T0 exit criterion.

  Workers are real processes sharing the test's embedded PostgreSQL over TCP.
  The baseline run -- several workers, no kills -- must complete the seeded
  workload with zero invariant violations: the floor every later tier perturbs.
  The two kill runs are the first rungs of T2 and T5: a dispatcher halted at the
  'sent but not recorded' seam and respawned, and seeded random kills.

  Slow by nature (each worker is a JVM); the kill matrix proper runs nightly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.chaos :as chaos]
            [voucha.db :as db]
            [voucha.test-db :as tdb :refer [*ds*]]
            [voucha.workload :as w]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private lease-ms 3000)
(def ^:private config
  {:dispatcher {:batch-size 50 :backoff-ms 50 :max-attempts 3 :lease-ms lease-ms :poll-interval-ms 50}
   :event {:max-attempts 3}})

(defn- run [workload opts]
  (let [r (chaos/run! *ds* workload (merge {:jdbc-url (tdb/jdbc-url) :config config :max-steps 500} opts))]
    (doseq [line (chaos/report r)] (println line))
    r))

(defn- lost-call-attempts []
  (:n (db/in-tx [tx *ds*]
        (db/execute-one! tx ["SELECT COUNT(*) AS n FROM effect_attempt WHERE error_detail LIKE 'dispatcher lost mid-call%'"]))))

(deftest T0-baseline-many-workers-no-kills-zero-violations
  (let [workload (w/generate 101 {:files 2 :batches [1 2] :transactions [1 3]})
        r (run workload {:kernels 2 :dispatchers 2 :rails 1})]
    (is (:settled? r) (pr-str (:file-states r)))
    (is (empty? (:violations r)) "zero mid-run invariant violations")
    (is (empty? (remove #(contains? #{:violations :ready} (:event %)) (:schedule r))) "nothing died, nothing was killed")
    (is (empty? (get-in r [:verify :mismatches])) (pr-str (get-in r [:verify :mismatches])))
    (is (:ok? (get-in r [:verify :invariants])) (pr-str (get-in r [:verify :invariants :violations])))
    (is (:ok? r))))

(deftest a-dispatcher-halted-mid-call-is-replaced-and-its-stranded-effect-reclaimed
  ;; The real version of stranded_effect_test: a dispatcher *process* dies after the
  ;; adapter call and before recording, a new one is started, and the lease-based
  ;; reclaim resolves the effect -- across processes, with the oracle watching.
  (let [workload (w/generate 202 {:files 1 :batches [1 1] :transactions [1 2]
                                  :p-rejected 0 :p-invalid 0 :p-rail-permanent 0})
        r (run workload {:kernels 1 :dispatchers 1 :rails 1
                         :armed {["dispatcher" 0] {:point :effect/after-call :on-hit 1}}})]
    (is (some #(and (= :died (:event %)) (= "dispatcher" (:role %))) (:schedule r))
        "the armed dispatcher halted at the seam")
    (is (some #(and (= :respawned (:event %)) (= "dispatcher" (:role %))) (:schedule r))
        "and was replaced")
    (is (pos? (lost-call-attempts)) "the lost call is on the record, resolved by policy")
    (is (:settled? r) (pr-str (:file-states r)))
    (is (empty? (:violations r)) (pr-str (:violations r)))
    (is (:ok? r) (pr-str (dissoc r :logs :schedule)))))

(deftest seeded-random-kills-with-respawn-still-complete-the-workload
  ;; Stepping starts once every worker is ready, so kills must come early and the
  ;; workload must outlast them: a bigger file set, kills at steps 2, 4 and 6.
  (let [workload (w/generate 303 {:files 3 :batches [2 3] :transactions [2 4]})
        plan (chaos/random-kill-plan 303 {:count 3 :every 2})
        r (run workload {:kernels 2 :dispatchers 2 :rails 1 :kill-plan plan})]
    (is (= 3 (count (filter #(= :killed (:event %)) (:schedule r)))) "every planned kill happened")
    (is (= 3 (count (filter #(= :respawned (:event %)) (:schedule r)))) "and every victim was replaced")
    (is (:settled? r) (pr-str (:file-states r)))
    (is (empty? (:violations r)) (pr-str (:violations r)))
    (is (:ok? r) (pr-str (dissoc r :logs :schedule)))
    (testing "the result is reproducible from its seed: the plan is a pure function of it"
      (is (= plan (chaos/random-kill-plan 303 {:count 3 :every 2}))))))
