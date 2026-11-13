(ns voucha.repo-test
  "Persistence tests: tree/node repositories, optimistic locking,
  transition-log checkpoints, and runtime records."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler]
            [voucha.db :as db]
            [voucha.profiles.file-batch-transaction]
            [voucha.patch :as patch]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.repo.runtime-record :as rr]
            [voucha.repo.transition-log :as tlog]
            [voucha.repo.tree :as tree]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(defn- seed-tree!
  "A small three-level tree, deliberately using profile-shaped node type names to
  prove the kernel stores them as opaque strings."
  [conn]
  (let [aid (tdb/seed-artifact! conn)]
    (tree/insert! conn {:tree-id "T" :artifact-id aid :root-node-id "F1"
                        :profile-name "file-batch-transaction" :profile-version "v1"})
    (node/insert! conn {:tree-id "T" :node-id "F1" :node-type "File" :local-state "FILE_NEW"})
    (node/insert! conn {:tree-id "T" :node-id "B1" :parent-node-id "F1"
                        :node-type "Batch" :local-state "BATCH_NEW"})
    (node/insert! conn {:tree-id "T" :node-id "T1" :parent-node-id "B1"
                        :node-type "Transaction" :local-state "TX_NEW"})
    (node/insert! conn {:tree-id "T" :node-id "T2" :parent-node-id "B1"
                        :node-type "Transaction" :local-state "TX_VALIDATED"})
    aid))

(deftest artifact-storage-is-idempotent
  (db/in-tx [tx *ds*]
    (let [specs {:node-types {:File {:may-contain ["Batch"]}}}]
      (artifact/insert! tx {:artifact-id "a1" :specs specs :profile-name "p" :profile-version "v1"})
      (artifact/insert! tx {:artifact-id "a1" :specs specs :profile-name "p" :profile-version "v1"})
      (is (= specs (:specs (artifact/fetch tx "a1"))))
      (is (= 1 (:n (db/execute-one! tx ["SELECT COUNT(*) AS n FROM execution_artifact"])))))))

(deftest compiled-artifact-round-trips-through-the-database
  (db/in-tx [tx *ds*]
    (let [a (voucha.compiler/compile-definition voucha.profiles.file-batch-transaction/definition)]
      (artifact/insert! tx a)
      (let [loaded (artifact/fetch tx (:artifact-id a))]
        (is (= (:specs a) (:specs loaded))
            "specs survive canonical EDN storage without loss")
        (is (= "file-batch-transaction" (:profile-name loaded)))
        (is (= (:artifact-id a)
               (get-in loaded [:specs :manifest :artifact-id])))))))

(deftest tree-and-node-round-trip
  (db/in-tx [tx *ds*]
    (seed-tree! tx)
    (testing "tree pins its artifact and profile"
      (let [t (tree/fetch tx "T")]
        (is (= "artifact-1" (:artifact-id t)))
        (is (= "F1" (:root-node-id t)))
        (is (= tree/status-active (:status t)))))

    (testing "nodes carry opaque profile-defined types"
      (is (= "Batch" (:node-type (node/fetch tx "T" "B1")))))

    (testing "children are found by parent"
      (is (= ["T1" "T2"] (mapv :node-id (node/children tx "T" "B1")))))

    (testing "ancestors are returned nearest-parent-first"
      (is (= ["B1" "F1"] (mapv :node-id (node/ancestors tx "T" "T1")))))

    (testing "child state counts aggregate by local state"
      (is (= {"TX_NEW" 1 "TX_VALIDATED" 1} (node/child-state-counts tx "T" "B1"))))))

(deftest optimistic-lock-accepts-the-first-writer
  (db/in-tx [tx *ds*]
    (seed-tree! tx)
    (let [n (node/fetch tx "T" "B1")
          new-data (patch/apply-patch (:local-data n) '[(assoc-in [:validated] true)])
          v (node/update-state! tx (assoc n :local-state "BATCH_VALIDATING" :local-data new-data))]
      (is (= 1 v))
      (let [n' (node/fetch tx "T" "B1")]
        (is (= "BATCH_VALIDATING" (:local-state n')))
        (is (= {:validated true} (:local-data n')))
        (is (= 1 (:lock-version n')))))))

(deftest optimistic-lock-rejects-a-stale-writer
  ;; Regression guard for lost updates (design 54): two readers load the same
  ;; lock_version, both write. The second must be rejected, not silently
  ;; overwrite the first -- otherwise a transition would commit against a tree
  ;; context that no longer holds.
  (db/in-tx [tx *ds*]
    (seed-tree! tx)
    (let [reader-a (node/fetch tx "T" "B1")
          reader-b (node/fetch tx "T" "B1")]
      (is (= (:lock-version reader-a) (:lock-version reader-b)))
      (node/update-state! tx (assoc reader-a :local-state "BATCH_VALIDATING"))
      (let [ex (try (node/update-state! tx (assoc reader-b :local-state "BATCH_PERMANENT_FAILURE"))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "second writer must be rejected")
        (is (= :voucha.repo.node/stale-node (:type (ex-data ex))))))
    (testing "the first writer's value survives"
      (is (= "BATCH_VALIDATING" (:local-state (node/fetch tx "T" "B1")))))))

(deftest concurrent-writers-serialise-through-lock-version
  ;; The same guarantee across real connections, not just within one transaction.
  (db/in-tx [tx *ds*] (seed-tree! tx))
  (let [n (db/in-tx [tx *ds*] (node/fetch tx "T" "B1"))
        outcomes (->> [(future (try (db/in-tx [tx *ds*]
                                      (node/update-state! tx (assoc n :local-state "A")))
                                    :ok (catch Exception _ :rejected)))
                       (future (try (db/in-tx [tx *ds*]
                                      (node/update-state! tx (assoc n :local-state "B")))
                                    :ok (catch Exception _ :rejected)))]
                      (mapv deref)
                      frequencies)]
    (is (= 1 (get outcomes :ok)) "exactly one writer commits")
    (is (= 1 (get outcomes :rejected)) "exactly one writer is rejected")
    (is (= 1 (:lock-version (db/in-tx [tx *ds*] (node/fetch tx "T" "B1")))))))

(deftest transition-log-checkpoint-round-trip
  (db/in-tx [tx *ds*]
    (seed-tree! tx)
    (let [p '[(assoc-in [:release-request-id] "REQ-1") (inc-in [:completed] 1)]
          read-set {:parent-state "FILE_PROCESS_BATCH" :outstanding-effects 0}]
      (tlog/append! tx {:transition-id "tr-1" :tree-id "T" :node-id "B1"
                        :artifact-id "artifact-1" :triggering-event-id "ev-1"
                        :from-state "BATCH_PAY_TRANSACTIONS" :to-state "BATCH_COMPLETED"
                        :handler-name "release-and-credit" :context-read-set read-set
                        :state-patch p
                        :emitted-events [{:type "BatchSettled"}]
                        :created-effects [{:effect "account.reverse:v1"}]})
      (let [[e :as h] (tlog/history tx "T" "B1")]
        (is (= 1 (count h)))
        (is (= "BATCH_COMPLETED" (:to-state e)))
        (is (= p (:state-patch e)) "patch survives as EDN, not JSON")
        (is (= read-set (:context-read-set e)))
        (is (= (voucha.codec/content-hash read-set) (:hierarchical-context-hash e))
            "context hash addresses the read-set (D4)")
        (is (= [{:effect "account.reverse:v1"}] (:created-effects e)))))))

(deftest transition-log-is-append-only-per-node
  (db/in-tx [tx *ds*]
    (seed-tree! tx)
    (doseq [[i from to] [[1 "BATCH_NEW" "BATCH_VALIDATING"] [2 "BATCH_VALIDATING" "BATCH_SCREEN_TRANSACTIONS"]]]
      (tlog/append! tx {:transition-id (str "tr-" i) :tree-id "T" :node-id "B1"
                        :artifact-id "artifact-1" :triggering-event-id (str "ev-" i)
                        :from-state from :to-state to :handler-name "h"
                        :state-patch []}))
    (is (= ["BATCH_NEW" "BATCH_VALIDATING"] (mapv :from-state (tlog/history tx "T" "B1"))))
    (is (= 2 (count (tlog/tree-history tx "T"))))))

(deftest runtime-records-are-generic-and-idempotent
  (db/in-tx [tx *ds*]
    (seed-tree! tx)
    (let [rec {:tree-id "T" :node-id "B1" :record-type "enrichment_result"
               :record-key "chunk-1" :record-version "v1"
               :payload {:enriched 7500 :failed 10}}]
      (rr/put! tx rec)
      (rr/put! tx rec)
      (is (= {:enriched 7500 :failed 10} (:payload (rr/fetch tx rec))))
      (is (= 1 (count (rr/by-type tx "T" "B1" "enrichment_result")))))))
