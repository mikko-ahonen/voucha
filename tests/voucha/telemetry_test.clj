(ns voucha.telemetry-test
  "The Observe tier (design: testing-api.md): an audit observer emits a telemetry
  record as a *post-commit effect* — atomic with the transition that produced it,
  delivered only after commit, and never as in-transaction external I/O. This is
  the production-safe cross-cutting path (logging / tracing / audit)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.telemetry :as telemetry]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

(defn- new-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a)
                (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- pending-telemetry []
  (db/in-tx [tx *ds*]
    (db/execute! tx ["SELECT * FROM outbox WHERE type = 'telemetry'"])))

(defn- submit! [event-id]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id event-id :tree-id "T" :target-node-id "F1"
                              :event-type "FileReceived" :payload {:name "f" :batches []}})))

(deftest an-audit-observer-emits-telemetry-atomically-then-after-commit
  (new-tree!)
  (let [sink (atom [])
        rt {:handlers app/handlers :event {:max-attempts 5}
            :telemetry-sink #(swap! sink conj %)}]
    (telemetry/install-audit!)
    (submit! "e1")
    (kernel/drain! *ds* rt)

    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (testing "the telemetry row was written in the transition's own transaction"
      (is (= 1 (count (pending-telemetry)))))
    (testing "but not delivered: no I/O happened inside the kernel transaction"
      (is (empty? @sink)))

    (testing "the dispatcher ships it to the sink after commit"
      (dispatcher/drain-outbox! *ds* rt)
      (is (= 1 (count @sink)))
      (let [rec (first @sink)]
        (is (= "F1" (:node-id rec)))
        (is (= "File" (:node-type rec)))
        (is (= "FILE_NEW" (:from rec)))
        (is (= "WRITING_RECEIVE_FILE" (:to rec)))
        (is (= "file/received" (:handler rec)))))))

(deftest a-rolled-back-transition-emits-no-telemetry
  ;; Audit is atomic with the transition: install the observer, then a crash after
  ;; it at the same seam. The observer enqueues, the crash aborts, and the whole
  ;; transaction -- telemetry included -- rolls back.
  (new-tree!)
  (let [sink (atom [])
        rt {:handlers app/handlers :event {:max-attempts 1}
            :telemetry-sink #(swap! sink conj %)}]
    (telemetry/install-audit!)
    (debug/crash! :transition/before-commit)
    (submit! "e2")
    (kernel/drain! *ds* rt)

    (is (= "FILE_NEW" (state-of "F1")) "the transition rolled back")
    (is (empty? (pending-telemetry)) "and so did its telemetry")
    (is (empty? @sink))))

(deftest no-observer-means-no-telemetry-and-no-cost
  (new-tree!)
  (let [rt {:handlers app/handlers :event {:max-attempts 5} :telemetry-sink (constantly nil)}]
    (submit! "e3")
    (kernel/drain! *ds* rt)
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (empty? (pending-telemetry)) "the seam is inert until an observer is installed")))
