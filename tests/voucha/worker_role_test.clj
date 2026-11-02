(ns voucha.worker-role-test
  "The internal API is complete (docs/design/kernel-procedures.md): a role
  granted the worker's functions and nothing on the tables runs the kernel, the
  dispatcher and the operator reads. Any table access left in the worker fails
  here with permission denied."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.order :as order]
            [voucha.fake :as fake]
            [voucha.inspect :as inspect]
            [voucha.invariants :as inv]
            [voucha.kernel :as kernel]
            [voucha.metrics :as metrics]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(defn- worker-pool
  "A pool connected as a role that may execute the worker's functions and
  touch nothing else."
  []
  (db/execute! *ds* ["DO $$ BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'voucha_worker') THEN
                          CREATE ROLE voucha_worker LOGIN;
                        END IF;
                      END $$"])
  (db/execute! *ds* ["SELECT voucha.grant_worker('voucha_worker')"])
  (db/pool {:jdbc-url (tdb/jdbc-url-for "voucha_worker") :max-pool-size 4}))

(defn- rt []
  (runtime/runtime
   {:handlers order/handlers
    :effect-adapters {"inventory.reserve" (fake/effect-adapter [{:outcome :completed :payload {:reservation "R1"}}])
                      "inventory.release" (fake/effect-adapter [{:outcome :completed}])
                      "payment.charge"    (fake/effect-adapter [{:outcome :completed :payload {:payment-ref "PAY-1"}}])
                      "shipping.dispatch" (fake/effect-adapter [{:outcome :completed :payload {:tracking "TRK-1"}}])}
    :config {:dispatcher {:batch-size 25 :backoff-ms 20 :max-attempts 3} :event {:max-attempts 3}}}))

(deftest the-worker-runs-on-execute-alone
  (let [ds (worker-pool)]
    (try
      (testing "the tables are closed to the worker"
        (is (thrown-with-msg? Exception #"permission denied"
                              (db/execute! ds ["SELECT 1 FROM state_node"])))
        (is (thrown-with-msg? Exception #"permission denied"
                              (db/execute! ds ["SELECT 1 FROM voucha.trees"]))))
      (testing "and yet the whole runtime runs through the functions"
        (let [aid (db/in-tx [tx ds]
                    (let [a (compiler/compile-definition order/definition)]
                      (artifact/insert! tx a) (:artifact-id a)))]
          (db/in-tx [tx ds]
            (kernel/create-tree! tx {:tree-id "O" :artifact-id aid :root-node-id "O1" :root-node-type "Order"})
            (kernel/submit-event! tx {:event-id "placed" :tree-id "O" :target-node-id "O1"
                                      :event-type "OrderPlaced"
                                      :payload {:customer "C-9"
                                                :items [{:item-id "O1-L1" :sku "SKU-A" :qty 2 :price 10}
                                                        {:item-id "O1-L2" :sku "SKU-B" :qty 1 :price 25}]}}))
          (runtime/settle! ds (rt))
          (is (= "ORDER_COMPLETED" (:local-state (db/in-tx [tx ds] (node/fetch tx "O" "O1")))))
          (testing "the operator reads as well"
            (is (zero? (:event-backlog (metrics/snapshot ds))))
            (is (= 3 (count (:nodes (db/in-tx [tx ds] (inspect/tree-view tx "O")))))))
          (testing "the oracle, which reads the tables itself, is satisfied"
            (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers order/handlers)))))))
      (finally (db/close-pool! ds)))))
