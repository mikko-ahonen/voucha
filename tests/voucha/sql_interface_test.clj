(ns voucha.sql-interface-test
  "The SQL interface (docs/design/sql-interface.md): an application that starts
  a tree and submits events from inside its own transactions, and a warehouse
  that carries out effects as a participant, work and report committed together."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.codec :as codec]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.order :as order]
            [voucha.example.order-adapters :as adapters]
            [voucha.example.order-data :as order-data]
            [voucha.example.order-participant :as participant]
            [voucha.invariants :as inv]
            [voucha.migrate :as migrate]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(defn- with-example-tables!
  "The example's tables, and one of the application's own."
  []
  (migrate/migrate! *ds* "example/order/migrations")
  (db/execute! *ds* ["CREATE TABLE IF NOT EXISTS app_order (order_id TEXT PRIMARY KEY, customer TEXT NOT NULL)"])
  (db/execute! *ds* ["TRUNCATE app_order, example_shipment, example_payment, example_reservation, example_customer, example_product CASCADE"])
  (order-data/seed! *ds*))

(defn- store-artifact! []
  (db/in-tx [tx *ds*]
    (let [a (compiler/compile-definition participant/definition)]
      (artifact/insert! tx a) (:artifact-id a))))

(defn- rt []
  (runtime/runtime (merge {:handlers order/handlers
                           :config {:dispatcher {:batch-size 25 :backoff-ms 20 :max-attempts 3} :event {:max-attempts 3}}}
                          (adapters/adapters *ds*))))

(defn- settle-with-participant!
  "The worker and the participant take turns until neither has anything."
  [r]
  (loop [i 0]
    (runtime/settle! *ds* r)
    (when (and (< i 20) (pos? (participant/run-once! *ds*)))
      (recur (inc i)))))

(def ^:private items
  [{:item-id "O-1-L1" :sku "SKU-1001" :qty 2 :price 1250}
   {:item-id "O-1-L2" :sku "SKU-1003" :qty 1 :price 1800}])

(defn- q [sql & params] (db/execute! *ds* (into [sql] params)))
(defn- q1 [sql & params] (db/execute-one! *ds* (into [sql] params)))
(defn- state-of [tree nid] (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree nid))))

(defn- place-order-in-sql!
  "What an application does: its own row and the tree's first event, one transaction."
  [tx aid]
  (db/execute-one! tx ["INSERT INTO app_order (order_id, customer) VALUES ('O-1', 'C-100')"])
  (db/execute-one! tx ["SELECT voucha.create_tree('O-1', ?, 'O-1', 'Order') AS tree" aid])
  (:event_id (db/execute-one! tx ["SELECT voucha.submit_event('O-1', 'O-1', 'OrderPlaced', ?) AS event_id"
                                  (codec/->jsonb {:customer "C-100" :items items})])))

(deftest the-definition-with-a-participant-compiles
  (is (empty? (compiler/validate participant/definition))
      (compiler/explain (compiler/validate participant/definition))))

(deftest a-participant-must-be-sql-external-and-not-query
  (let [with (fn [m] (compiler/validate (update-in order/definition [:effects "inventory.reserve"] merge m)))]
    (is (some #(= :invalid-participant (:code %)) (with {:participant :http})))
    (is (some #(= :invalid-participant (:code %)) (with {:participant :sql :uncertainty-policy :query :status-query "x"})))
    (is (some #(= :invalid-participant (:code %)) (with {:participant :sql :kind :store :adapter "a"})))))

(deftest an-order-placed-in-sql-is-filled-by-a-sql-participant
  (with-example-tables!)
  (let [aid (store-artifact!)
        event-id (db/in-tx [tx *ds*] (place-order-in-sql! tx aid))]
    (is (string? event-id))
    (testing "the tree and its root exist as the artifact says"
      (is (= "ORDER_NEW" (state-of "O-1" "O-1")))
      (is (= {:status "ACTIVE" :artifact_id aid} (q1 "SELECT status, artifact_id FROM voucha.trees WHERE tree_id = 'O-1'"))))
    (settle-with-participant! (rt))
    (is (= "ORDER_COMPLETED" (state-of "O-1" "O-1")))
    (testing "the warehouse's rows and its reports committed together"
      (is (= 38 (:stock (q1 "SELECT stock FROM example_product WHERE sku = 'SKU-1001'"))))
      (is (= 2 (count (q "SELECT 1 FROM example_reservation WHERE order_id = 'O-1' AND status = 'RESERVED'"))))
      (is (= [{:n 2}] (q "SELECT COUNT(*) AS n FROM effect_report WHERE status = 'APPLIED'")))
      (is (= #{{:status "COMPLETED" :participant "sql" :claimed_by "warehouse" :last_report "APPLIED"}}
             (set (q "SELECT status, participant, claimed_by, last_report FROM voucha.effects WHERE effect_name = 'inventory.reserve'")))))
    (testing "the adapters did the rest"
      (is (= [{:status "CHARGED"}] (q "SELECT status FROM example_payment WHERE order_id = 'O-1'")))
      (is (= 1 (count (q "SELECT 1 FROM example_shipment WHERE order_id = 'O-1'")))))
    (testing "the views show the event's journey"
      (is (= "PROCESSED" (:status (q1 "SELECT status FROM voucha.events WHERE event_id = ?" event-id))))
      (is (pos? (count (q "SELECT 1 FROM voucha.transitions WHERE tree_id = 'O-1'")))))
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers order/handlers))))))

(deftest an-unknown-event-type-fails-the-applications-transaction
  (with-example-tables!)
  (let [aid (store-artifact!)]
    (is (thrown-with-msg? Exception #"OrderMisplaced is not declared"
                          (db/in-tx [tx *ds*]
                            (db/execute-one! tx ["INSERT INTO app_order (order_id, customer) VALUES ('O-1', 'C-100')"])
                            (db/execute-one! tx ["SELECT voucha.create_tree('O-1', ?, 'O-1', 'Order')" aid])
                            (db/execute-one! tx ["SELECT voucha.submit_event('O-1', 'O-1', 'OrderMisplaced', '{}')"]))))
    (is (empty? (q "SELECT 1 FROM app_order")) "the application's row rolled back with the refused event")
    (is (empty? (q "SELECT 1 FROM voucha.trees")))
    (is (thrown-with-msg? Exception #"unknown tree"
                          (db/execute-one! *ds* ["SELECT voucha.submit_event('nope', 'O-1', 'OrderPlaced', '{}')"])))
    (is (thrown-with-msg? Exception #"no node type"
                          (db/execute-one! *ds* ["SELECT voucha.create_tree('O-2', ?, 'O-2', 'Basket')" aid])))))

(deftest the-same-submission-twice-is-one-event
  (with-example-tables!)
  (let [aid (store-artifact!)
        submit (fn [] (:e (q1 "SELECT voucha.submit_event('O-1', 'O-1', 'OrderCancelRequested', ?) AS e"
                              (codec/->jsonb {:reason "changed my mind"}))))]
    (db/execute-one! *ds* ["SELECT voucha.create_tree('O-1', ?, 'O-1', 'Order')" aid])
    (is (= (submit) (submit)))
    (is (= 1 (count (q "SELECT 1 FROM voucha.events WHERE event_type = 'OrderCancelRequested'"))))
    (testing "an operator's event records who and why"
      (q1 "SELECT voucha.intervene('O-1', 'O-1', 'OrderCancelRequested', '{\"reason\": \"fraud\"}', 'ops-1', 'card reported stolen')")
      (is (= {:operator_id "ops-1" :operator_reason "card reported stolen"}
             (q1 "SELECT operator_id, operator_reason FROM voucha.events WHERE operator_id IS NOT NULL")))
      (is (thrown-with-msg? Exception #"names its operator"
                            (q1 "SELECT voucha.intervene('O-1', 'O-1', 'OrderCancelRequested', '{}', NULL, NULL)"))))))

(defn- first-reserve-effect []
  (q1 "SELECT effect_instance_id, status, claimed_by, retry_count FROM effect_instance WHERE effect_name = 'inventory.reserve' ORDER BY created_at LIMIT 1"))

(deftest a-claim-reclaimed-past-its-lease-refuses-the-late-report
  (with-example-tables!)
  (let [aid (store-artifact!)
        r (rt)]
    (db/in-tx [tx *ds*] (place-order-in-sql! tx aid))
    (runtime/settle! *ds* r)
    (testing "the worker left the participant's effects alone"
      (is (= "CREATED" (:status (first-reserve-effect)))))
    (let [claimed (q "SELECT * FROM voucha.claim_effects('inventory.reserve', 'w1', 1)")
          eid (:effect_instance_id (first claimed))]
      (is (= 1 (count claimed)))
      (is (= 1 (:attempt (first claimed))))
      (is (= {:status "DISPATCHING" :claimed_by "w1"} (select-keys (first-reserve-effect) [:status :claimed_by])))
      (testing "a second participant does not see what the first holds"
        (is (= 1 (count (q "SELECT * FROM voucha.claim_effects('inventory.reserve', 'w2', 5)"))) "only the other line"))
      (testing "the lease runs out and the policy takes the effect back"
        (q1 "UPDATE effect_instance SET updated_at = now() - interval '10 minutes' WHERE effect_instance_id = ?" eid)
        (is (= 1 (dispatcher/reclaim-stranded! *ds* r)))
        (is (= "DISPATCH_FAILED" (:status (q1 "SELECT status FROM effect_instance WHERE effect_instance_id = ?" eid))))
        (is (thrown-with-msg? Exception #"not held by participant w1"
                              (db/in-tx [tx *ds*]
                                (db/execute-one! tx ["INSERT INTO example_reservation (reservation_id, order_id, item_id, sku, qty, status) VALUES ('late', 'O-1', 'late', 'SKU-1001', 1, 'RESERVED')"])
                                (db/execute-one! tx ["SELECT voucha.report_outcome(?, 'w1', 'completed', '{}')" eid]))))
        (is (empty? (q "SELECT 1 FROM example_reservation WHERE reservation_id = 'late'")) "the late work rolled back with its report")
        (is (thrown-with-msg? Exception #"not held" (q1 "SELECT voucha.extend_lease(?, 'w1')" eid))))
      (testing "after the backoff the effect is claimable again, as a second attempt"
        (Thread/sleep 60)
        (let [again (q "SELECT * FROM voucha.claim_effects('inventory.reserve', 'w1', 5)")]
          (is (= [eid] (map :effect_instance_id again)))
          (is (= 2 (:attempt (first again))))
          (q1 "SELECT voucha.extend_lease(?, 'w1')" eid))))))

(deftest a-report-on-an-effect-that-went-terminal-is-superseded
  (with-example-tables!)
  (let [aid (store-artifact!)
        r (rt)]
    (db/in-tx [tx *ds*] (place-order-in-sql! tx aid))
    (runtime/settle! *ds* r)
    (let [eid (:effect_instance_id (q1 "SELECT * FROM voucha.claim_effects('inventory.reserve', 'w1', 1)"))]
      (q1 "SELECT voucha.report_outcome(?, 'w1', 'failed', '{\"error\": \"shelf empty\"}')" eid)
      (is (thrown-with-msg? Exception #"already has a report"
                            (q1 "SELECT voucha.report_outcome(?, 'w1', 'completed', '{}')" eid)))
      ;; The owner was cancelled before the worker applied the report.
      (q1 "UPDATE effect_instance SET status = 'CANCELLED' WHERE effect_instance_id = ?" eid)
      (is (= 1 (dispatcher/apply-reports! *ds* r)))
      (is (= {:status "SUPERSEDED" :note "effect was already CANCELLED"}
             (q1 "SELECT status, note FROM effect_report WHERE effect_instance_id = ?" eid)))
      (is (= "CANCELLED" (:status (q1 "SELECT status FROM effect_instance WHERE effect_instance_id = ?" eid))))
      (is (empty? (q "SELECT 1 FROM event_log WHERE event_type = 'ItemOutOfStock'")) "no event reached the owner"))))
