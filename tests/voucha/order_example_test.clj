(ns voucha.order-example-test
  "The small order example, end to end: the happy path, a short order, a
  declined payment, and an empty order."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.order :as order]
            [voucha.example.order-adapters :as adapters]
            [voucha.example.order-data :as order-data]
            [voucha.migrate :as migrate]
            [voucha.fake :as fake]
            [voucha.invariants :as inv]
            [voucha.kernel :as kernel]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private tree-id "O")

(defn- ok [] {:outcome :completed})

(defn- rt
  "Every effect succeeds unless `overrides` says otherwise."
  ([] (rt {}))
  ([overrides]
   (runtime/runtime
    {:handlers order/handlers
     :effect-adapters (merge {"inventory.reserve" (fake/effect-adapter [{:outcome :completed :payload {:reservation "R1"}}])
                              "inventory.release" (fake/effect-adapter [(ok)])
                              "payment.charge"    (fake/effect-adapter [{:outcome :completed :payload {:payment-ref "PAY-1"}}])
                              "shipping.dispatch" (fake/effect-adapter [{:outcome :completed :payload {:tracking "TRK-1"}}])}
                             overrides)
     :config {:dispatcher {:batch-size 25 :backoff-ms 20 :max-attempts 3} :event {:max-attempts 3}}})))

(defn- place-order! [items]
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition order/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "O1" :root-node-type "Order"})
      (kernel/submit-event! tx {:event-id "placed" :tree-id tree-id :target-node-id "O1"
                                :event-type "OrderPlaced"
                                :payload {:customer "C-9" :items items}}))))

(defn- state-of [nid] (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id nid))))
(defn- data-of [nid] (:local-data (db/in-tx [tx *ds*] (node/fetch tx tree-id nid))))

(def ^:private two-lines
  [{:item-id "O1-L1" :sku "SKU-A" :qty 2 :price 10}
   {:item-id "O1-L2" :sku "SKU-B" :qty 1 :price 25}])

(deftest the-definition-compiles
  (is (empty? (compiler/validate order/definition)) (compiler/explain (compiler/validate order/definition))))

(deftest an-order-is-reserved-charged-and-shipped
  (let [r (rt)]
    (place-order! two-lines)
    (runtime/settle! *ds* r)
    (is (= "ORDER_COMPLETED" (state-of "O1")))
    (is (= "ITEM_RESERVED" (state-of "O1-L1")))
    (is (= "ITEM_RESERVED" (state-of "O1-L2")))
    (testing "the handlers recorded what the outside world told them"
      (is (= 45 (:total (data-of "O1"))))
      (is (= "PAY-1" (:payment-ref (data-of "O1"))))
      (is (= "TRK-1" (:tracking (data-of "O1"))))
      (is (= "R1" (:reservation (data-of "O1-L1")))))
    (testing "the order's counters came from the kernel"
      (is (= 2 (get-in (data-of "O1") [:counters :items-reserved])))
      (is (= 0 (get-in (data-of "O1") [:counters :items-unavailable] 0))))
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers order/handlers))))))

(deftest an-order-with-an-unavailable-line-releases-its-stock-and-is-cancelled
  (let [reserve (fake/effect-adapter [{:outcome :completed :payload {:reservation "R1"}}
                                      {:outcome :failed :payload {:error "out of stock"}}])
        release (fake/effect-adapter [(ok)])
        charge  (fake/effect-adapter [(ok)])
        r (rt {"inventory.reserve" reserve "inventory.release" release "payment.charge" charge})]
    (place-order! two-lines)
    (runtime/settle! *ds* r)
    (is (= "ORDER_CANCELLED" (state-of "O1")))
    (is (= #{"ITEM_RESERVED" "ITEM_UNAVAILABLE"} #{(state-of "O1-L1") (state-of "O1-L2")}))
    (is (= 1 (count (fake/calls release))) "whatever was reserved is released, by order")
    (is (empty? (fake/calls charge)) "and the customer is never charged")
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers order/handlers))))))

(deftest a-failed-charge-is-retried-inside-the-charging-state-then-given-up
  ;; A failed charge waits in ORDER_CHARGE_RETRY_WAIT and retries into
  ;; ORDER_CHARGING. Each retry is a new charge intent, keyed by attempt.
  (let [charge  (fake/effect-adapter [{:outcome :failed :payload {:error "provider unavailable"}}])
        release (fake/effect-adapter [(ok)])
        ship    (fake/effect-adapter [(ok)])
        r (rt {"payment.charge" charge "inventory.release" release "shipping.dispatch" ship})]
    (place-order! two-lines)
    (runtime/settle! *ds* r)
    (is (= "ORDER_CANCELLED" (state-of "O1")))
    (is (= "provider unavailable" (:cancel-reason (data-of "O1"))))
    (is (= 3 (count (filter #(= :dispatch (:op %)) (fake/calls charge)))) "the first charge and two retries")
    (is (= 1 (count (fake/calls release))))
    (is (empty? (fake/calls ship)))))

(deftest a-failed-charge-that-succeeds-on-retry-ships
  (let [charge (fake/effect-adapter [{:outcome :failed :payload {:error "provider unavailable"}}
                                     {:outcome :completed :payload {:payment-ref "PAY-2"}}])
        r (rt {"payment.charge" charge})]
    (place-order! two-lines)
    (runtime/settle! *ds* r)
    (is (= "ORDER_COMPLETED" (state-of "O1")))
    (is (= "PAY-2" (:payment-ref (data-of "O1"))))
    (is (= 1 (:charge-attempts (data-of "O1"))))))

(deftest an-operator-can-cancel-while-the-order-is-being-charged
  ;; The cancel is declared from the charge call and from the retry wait. Here
  ;; the order is between a failed charge and its retry; the cancel lands.
  (let [charge  (fake/effect-adapter [{:outcome :failed :payload {:error "provider unavailable"}}])
        release (fake/effect-adapter [(ok)])
        r (rt {"payment.charge" charge "inventory.release" release})]
    (place-order! two-lines)
    (kernel/drain! *ds* r)                       ; validated, items created and commanded
    (dispatcher/run-once! *ds* r) (kernel/drain! *ds* r)   ; reserved -> deciding -> CHARGE_CALLING
    (is (= "ORDER_CHARGING" (state-of "O1")))
    (dispatcher/run-once! *ds* r) (kernel/drain! *ds* r)   ; the charge fails -> CHARGE_RETRY_WAIT -> retry -> CHARGING
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "cancel" :tree-id tree-id :target-node-id "O1"
                                :event-type "OrderCancelRequested" :payload {:reason "customer called"}
                                :operator-id "alice" :operator-reason "customer called"}))
    (runtime/settle! *ds* r)
    (is (= "ORDER_CANCELLED" (state-of "O1")))
    (is (= "customer called" (:cancel-reason (data-of "O1"))))
    (is (= 1 (count (fake/calls release))))
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers order/handlers))))))

(deftest a-charge-that-completes-after-a-cancel-is-recorded-for-a-refund
  ;; The induction check's finding: the charge is in flight when the operator cancels.
  ;; The cancel is recorded; when the charge completes, the order releases its stock
  ;; and marks itself for a refund, then cancels when the release is through.
  (let [charge  (fake/effect-adapter [{:outcome :dispatched}])
        release (fake/effect-adapter [{:outcome :dispatched}])
        r (rt {"payment.charge" charge "inventory.release" release})
        callback! (fn [effect-name outcome payload]
                    (let [row (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT effect_instance_id FROM effect_instance WHERE tree_id = ? AND effect_name = ? AND status = 'DISPATCHED'" tree-id effect-name]))]
                      (dispatcher/deliver-callback! *ds* {:effect-instance-id (:effect_instance_id row) :outcome outcome :payload payload})))]
    (place-order! two-lines)
    (kernel/drain! *ds* r)
    (dispatcher/run-once! *ds* r) (kernel/drain! *ds* r)
    (is (= "ORDER_CHARGING" (state-of "O1")))
    (dispatcher/run-once! *ds* r)                                ; the charge is dispatched, in flight
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "cancel" :tree-id tree-id :target-node-id "O1"
                                :event-type "OrderCancelRequested" :payload {:reason "customer called"}
                                :operator-id "alice" :operator-reason "customer called"}))
    (kernel/drain! *ds* r)
    (is (= "ORDER_CHARGING" (state-of "O1")) "the cancel is recorded, the charge still in flight")
    (is (true? (:cancel-requested (data-of "O1"))))
    (callback! "payment.charge" :completed {:payment-ref "PAY-LATE"})
    (kernel/drain! *ds* r) (dispatcher/run-once! *ds* r)      ; charged -> releasing, the release in flight
    (is (= "ORDER_RELEASING" (state-of "O1")))
    (is (true? (:charged-after-cancel (data-of "O1"))))
    (is (= "PAY-LATE" (:payment-ref (data-of "O1"))))
    (callback! "inventory.release" :completed {})
    (runtime/settle! *ds* r)
    (is (= "ORDER_CANCELLED" (state-of "O1")))
    (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers order/handlers))))))

(deftest an-uncertain-charge-is-resolved-by-asking-never-by-charging-again
  (let [charge (fake/effect-adapter [{:throw "gateway timeout"}]
                                    {:outcome :completed :payload {:payment-ref "PAY-LATE"}})
        r (rt {"payment.charge" charge})]
    (place-order! two-lines)
    (runtime/settle! *ds* r)
    (is (= "ORDER_COMPLETED" (state-of "O1")))
    (is (= "PAY-LATE" (:payment-ref (data-of "O1"))))
    (is (= [:dispatch :query-status] (mapv :op (fake/calls charge)))
        "one charge, then a status query; the card was never charged twice")))

(deftest an-empty-order-is-rejected-before-anything-is-reserved
  (let [reserve (fake/effect-adapter [(ok)])
        r (rt {"inventory.reserve" reserve})]
    (place-order! [])
    (runtime/settle! *ds* r)
    (is (= "ORDER_REJECTED" (state-of "O1")))
    (is (empty? (fake/calls reserve)))))

;; --- real data: the example's own tables ---------------------------------------------

(defn- with-example-tables!
  "The example's migrations over the test database, and its tables emptied."
  []
  (migrate/migrate! *ds* "example/order/migrations")
  (db/execute! *ds* ["TRUNCATE example_shipment, example_payment, example_reservation, example_customer, example_product CASCADE"])
  (order-data/seed! *ds*))

(defn- real-rt []
  (runtime/runtime (merge {:handlers order/handlers
                           :config {:dispatcher {:batch-size 25 :backoff-ms 20 :max-attempts 3} :event {:max-attempts 3}}}
                          (adapters/adapters *ds*))))

(defn- stock-of [sku] (:stock (db/execute-one! *ds* ["SELECT stock FROM example_product WHERE sku = ?" sku])))
(defn- rows [sql & params] (db/execute! *ds* (into [sql] params)))

(deftest orders-write-real-rows-through-real-adapters
  (with-example-tables!)
  (let [r (real-rt)
        aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition order/definition)]
                (artifact/insert! tx a) (:artifact-id a)))
        place! (fn [o] (order-data/place-order! *ds* aid o))]

    (testing "an order that can be filled reserves stock, charges, and ships"
      (place! {:tree-id "O-1" :order-id "O-1" :customer "C-100"
               :items [{:item-id "O-1-L1" :sku "SKU-1001" :qty 2 :price 1250}
                       {:item-id "O-1-L2" :sku "SKU-1003" :qty 1 :price 1800}]})
      (runtime/settle! *ds* r)
      (is (= "ORDER_COMPLETED" (:local-state (db/in-tx [tx *ds*] (node/fetch tx "O-1" "O-1")))))
      (is (= 38 (stock-of "SKU-1001")) "two notebooks left the shelf")
      (is (= 59 (stock-of "SKU-1003")))
      (is (= #{"RESERVED"} (set (map :status (rows "SELECT status FROM example_reservation WHERE order_id = 'O-1'")))))
      (is (= [{:amount_cents 4300 :status "CHARGED"}]
             (rows "SELECT amount_cents, status FROM example_payment WHERE order_id = 'O-1'")))
      (is (= [{:tracking "TRK-O-1" :customer_id "C-100" :line_count 2}]
             (rows "SELECT tracking, customer_id, line_count FROM example_shipment WHERE order_id = 'O-1'"))))

    (testing "an order a shelf cannot fill is cancelled and what it held goes back"
      (place! {:tree-id "O-2" :order-id "O-2" :customer "C-101"
               :items [{:item-id "O-2-L1" :sku "SKU-1007" :qty 2 :price 3400}
                       {:item-id "O-2-L2" :sku "SKU-1004" :qty 9 :price 12900}]})
      (runtime/settle! *ds* r)
      (is (= "ORDER_CANCELLED" (:local-state (db/in-tx [tx *ds*] (node/fetch tx "O-2" "O-2")))))
      (is (= 3 (stock-of "SKU-1007")) "the wax seal kits reserved for it are back")
      (is (= 6 (stock-of "SKU-1004")) "the lamps were never taken")
      (is (= [{:item_id "O-2-L1" :status "RELEASED"}]
             (rows "SELECT item_id, status FROM example_reservation WHERE order_id = 'O-2' ORDER BY item_id")))
      (is (empty? (rows "SELECT 1 FROM example_payment WHERE order_id = 'O-2'")) "nothing was charged"))

    (testing "a customer over the credit limit is declined, retried as new charges, then cancelled"
      (place! {:tree-id "O-3" :order-id "O-3" :customer "C-102"
               :items [{:item-id "O-3-L1" :sku "SKU-1004" :qty 1 :price 12900}]})
      (runtime/settle! *ds* r)
      (is (= "ORDER_CANCELLED" (:local-state (db/in-tx [tx *ds*] (node/fetch tx "O-3" "O-3")))))
      (is (= ["DECLINED" "DECLINED" "DECLINED"]
             (map :status (rows "SELECT status FROM example_payment WHERE order_id = 'O-3' ORDER BY payment_ref")))
          "three attempts, each its own charge, none of them twice")
      (is (= 6 (stock-of "SKU-1004")) "the lamp is back on the shelf")
      (is (empty? (rows "SELECT 1 FROM example_shipment WHERE order_id = 'O-3'"))))

    (testing "a run of generated orders leaves every tree finished and every shelf consistent"
      (let [placed (order-data/place-orders! *ds* aid {:n 12 :seed 7})]
        (runtime/settle! *ds* r 400)
        (doseq [{:keys [tree-id order-id]} placed]
          (is (contains? #{"ORDER_COMPLETED" "ORDER_CANCELLED"}
                         (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id order-id))))
              order-id))
        (is (every? (complement neg?) (map :stock (rows "SELECT stock FROM example_product"))) "no shelf went negative")
        (let [{:keys [shipments payments]} (order-data/report *ds*)]
          (is (pos? (get shipments "DISPATCHED" 0)) "some orders shipped")
          (is (= (get shipments "DISPATCHED" 0) (get payments "CHARGED" 0)) "every shipment was paid for exactly once"))
        (is (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers order/handlers))))))))

;; --- the Java methods and the plans read from them agree ------------------------------

(deftest the-extracted-plans-and-the-java-methods-produce-the-same-orders
  ;; The same generated orders through the real adapters twice: once with the
  ;; plans the kernel runs, once with the plans dropped so the Java methods run.
  ;; Every order ends in the same state and the tables say the same.
  (let [as-code (update order/definition :transitions (fn [ts] (mapv #(dissoc % :plan :plan-source) ts)))
        run! (fn [definition]
               (tdb/clean-db
                (fn []
                  (with-example-tables!)
                  (let [r (real-rt)
                        aid (db/in-tx [tx *ds*]
                              (let [a (compiler/compile-definition definition)]
                                (artifact/insert! tx a) (:artifact-id a)))
                        placed (order-data/place-orders! *ds* aid {:n 12 :seed 11})]
                    (runtime/settle! *ds* r 400)
                    {:states (into (sorted-map)
                                   (map (fn [{:keys [tree-id order-id]}]
                                          [order-id (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id order-id)))])
                                        placed))
                     :data (into (sorted-map)
                                 (map (fn [{:keys [tree-id order-id]}]
                                        [order-id (dissoc (:local-data (db/in-tx [tx *ds*] (node/fetch tx tree-id order-id))) :counters :child-count)])
                                      placed))
                     :report (order-data/report *ds*)}))))
        with-plans (run! order/definition)
        with-code (run! as-code)]
    (is (= (:states with-plans) (:states with-code)))
    (is (= (:data with-plans) (:data with-code)) "the same patches, field for field")
    (is (= (:report with-plans) (:report with-code)))))
