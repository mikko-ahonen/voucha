(ns voucha.provenance-test
  "Phase D (design: testing-api.md): replay provenance.

  A Fault-tier transform is dev-only, but it is never silent. When one rewrites a
  transition's input or plan, the committed checkpoint is tagged `debug_provenance`
  naming the seam, so an auditor replaying the log sees 'this value was
  debug-injected' rather than a fabricated-but-plausible transition. Control-flow
  faults (sleep/block/crash) and non-Fault interceptors leave no such mark, because
  they alter no recorded value."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.example.batch :as app]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

(defn- new-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(defn- receive! []
  (let [rt {:handlers app/handlers :event {:max-attempts 3}}]
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "e1" :tree-id "T" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:name "f" :batches []}}))
    (kernel/drain! *ds* rt)))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- last-checkpoint []
  (last (db/in-tx [tx *ds*] (tlog/history tx "T" "F1"))))

(deftest a-fault-transform-is-stamped-onto-the-checkpoint
  (new-tree!)
  ;; A dev transform rewrites the handler's input at a transition seam.
  (debug/transform! :transition/before-handler
                    (fn [data] (assoc-in data [:event :payload :injected] true)))
  (receive!)
  (is (= "WRITING_RECEIVE_FILE" (state-of "F1")) "the transition still ran and committed")

  (testing "and the checkpoint carries the footprint of the debug transform"
    (let [prov (:debug-provenance (last-checkpoint))]
      (is (= 1 (count prov)))
      (is (= "transition/before-handler" (:point (first prov))))
      (is (re-find #"^icept/" (:interceptor (first prov)))))))

(deftest a-clean-transition-carries-no-provenance
  (new-tree!)
  (receive!)
  (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
  (is (nil? (:debug-provenance (last-checkpoint)))
      "with no debug transform, the row is a plain, honest checkpoint"))

(deftest a-control-flow-fault-leaves-no-data-footprint
  ;; observe! and sleep! run at the seam but return the data unchanged: they alter
  ;; timing or read, not the recorded computation, so they must not be stamped.
  (new-tree!)
  (debug/observe! :transition/before-handler)
  (debug/sleep! :transition/after-handler 1)
  (receive!)
  (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
  (is (nil? (:debug-provenance (last-checkpoint)))
      "no value was rewritten, so there is nothing to disclose"))

(deftest only-fault-tier-transforms-are-treated-as-provenance
  ;; A production-tier interceptor (Observe/Decide) that rewrites data is a
  ;; legitimate part of the computation, not a debug injection, so it is not
  ;; stamped as debug provenance.
  (new-tree!)
  (intercept/install! {:point :transition/before-handler
                       :tier :observe
                       :action (fn [data] (assoc-in data [:event :payload :prod] true))})
  (receive!)
  (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
  (is (nil? (:debug-provenance (last-checkpoint)))
      "only the dev Fault tier is disclosed as debug-injected"))
