(ns voucha.example.order-adapters
  "The systems the order example's effects reach, backed by the example's own
  tables: a warehouse that reserves and releases stock, a payment provider
  that charges against a credit limit, a carrier that ships. Each behaves as
  its real counterpart does at the boundary that matters to the platform: it
  deduplicates on the key the platform sends, it refuses definitely when it
  must, and it can be asked about a charge whose outcome was lost.

  Adapters run after the kernel transaction that created the effect has
  committed, and they never touch the platform's tables; they write their own."
  (:require [voucha.adapter :as adapter]
            [voucha.db :as db]))

;; --- the warehouse -------------------------------------------------------------

(defn reserve-in!
  "Reserve a line's stock, in the caller's transaction. Deduplicates on the
  line: a reservation that exists is the answer. Refuses, definitely, when the
  stock is short."
  [tx {:keys [item-id sku qty] :as _request}]
  (do
    (if-let [existing (db/execute-one! tx ["SELECT reservation_id, status FROM example_reservation WHERE item_id = ?" item-id])]
      (if (= "RESERVED" (:status existing))
        {:outcome :completed :payload {:reservation (:reservation_id existing)}}
        {:outcome :failed :payload {:error "reservation was released"}})
      (let [product (db/execute-one! tx ["SELECT stock FROM example_product WHERE sku = ? FOR UPDATE" sku])
            qty (long (or qty 0))]
        (cond
          (nil? product) {:outcome :failed :payload {:error (str "unknown product " sku)}}
          (< (:stock product) qty) {:outcome :failed :payload {:error (format "out of stock: %s has %d, %d asked" sku (:stock product) qty)}}
          :else
          (let [reservation-id (str "RSV-" item-id)]
            (db/execute-one! tx ["UPDATE example_product SET stock = stock - ? WHERE sku = ?" qty sku])
            (db/execute-one! tx ["INSERT INTO example_reservation (reservation_id, order_id, item_id, sku, qty, status)
                                  VALUES (?, ?, ?, ?, ?, 'RESERVED')"
                                 reservation-id (subs item-id 0 (.lastIndexOf ^String item-id "-L")) item-id sku qty])
            {:outcome :completed :payload {:reservation reservation-id}}))))))

(defn release-in!
  "Release everything the order holds and put the stock back, in the caller's
  transaction. Releasing what is already released is a no-op, so a retried
  release is harmless."
  [tx {:keys [order-id] :as _request}]
  (do
    (let [held (db/execute! tx ["SELECT reservation_id, sku, qty FROM example_reservation
                                 WHERE order_id = ? AND status = 'RESERVED' FOR UPDATE" order-id])]
      (doseq [{:keys [reservation_id sku qty]} held]
        (db/execute-one! tx ["UPDATE example_product SET stock = stock + ? WHERE sku = ?" qty sku])
        (db/execute-one! tx ["UPDATE example_reservation SET status = 'RELEASED' WHERE reservation_id = ?" reservation_id]))
      {:outcome :completed :payload {:released (count held)}})))

(defn- reserve! [ds request] (db/in-tx [tx ds] (reserve-in! tx request)))
(defn- release! [ds request] (db/in-tx [tx ds] (release-in! tx request)))

(defn warehouse-reserve [ds]
  (reify adapter/EffectAdapter
    (dispatch [_ effect] (reserve! ds (:request-payload effect)))
    (query-status [_ _] {:outcome :unknown})))

(defn warehouse-release [ds]
  (reify adapter/EffectAdapter
    (dispatch [_ effect] (release! ds (:request-payload effect)))
    (query-status [_ _] {:outcome :unknown})))

;; --- the payment provider ----------------------------------------------------------

(defn- payment-answer [{:keys [payment_ref status]}]
  (if (= "CHARGED" status)
    {:outcome :completed :payload {:payment-ref payment_ref}}
    {:outcome :failed :payload {:error "declined: credit limit exceeded" :payment-ref payment_ref}}))

(defn- charge!
  "Charge the customer, once per idempotency key: a key seen before gets the
  same answer again, which is what makes an uncertain charge safe to ask about.
  Declines, definitely, when the amount exceeds what is left of the credit limit."
  [ds idempotency-key {:keys [order-id customer amount attempt] :as _request}]
  (db/in-tx [tx ds]
    (if-let [seen (db/execute-one! tx ["SELECT payment_ref, status FROM example_payment WHERE idempotency_key = ?" idempotency-key])]
      (payment-answer seen)
      (let [limit (:credit_limit_cents (db/execute-one! tx ["SELECT credit_limit_cents FROM example_customer WHERE customer_id = ? FOR UPDATE" customer]))
            charged (:n (db/execute-one! tx ["SELECT COALESCE(SUM(amount_cents), 0) AS n FROM example_payment
                                              WHERE customer_id = ? AND status = 'CHARGED'" customer]))
            amount (long (or amount 0))
            status (if (and limit (<= (+ charged amount) limit)) "CHARGED" "DECLINED")
            payment-ref (format "PAY-%s-%s" order-id (or attempt 0))]
        (db/execute-one! tx ["INSERT INTO example_payment (payment_ref, idempotency_key, order_id, customer_id, amount_cents, status)
                              VALUES (?, ?, ?, ?, ?, ?)" payment-ref idempotency-key order-id (str customer) amount status])
        (payment-answer {:payment_ref payment-ref :status status})))))

(defn payment-provider [ds]
  (reify adapter/EffectAdapter
    (dispatch [_ effect] (charge! ds (:idempotency-key effect) (:request-payload effect)))
    (query-status [_ effect]
      (if-let [seen (db/execute-one! ds ["SELECT payment_ref, status FROM example_payment WHERE idempotency_key = ?" (:idempotency-key effect)])]
        (payment-answer seen)
        {:outcome :unknown}))))

;; --- the carrier ----------------------------------------------------------------------

(defn- ship!
  [ds {:keys [order-id items] :as _request}]
  (let [tracking (str "TRK-" order-id)
        customer (or (:customer _request) "")]
    (db/execute-one! ds ["INSERT INTO example_shipment (tracking, order_id, customer_id, line_count, status)
                          VALUES (?, ?, ?, ?, 'DISPATCHED') ON CONFLICT (order_id) DO NOTHING"
                         tracking order-id customer (count items)])
    {:outcome :completed :payload {:tracking tracking}}))

(defn carrier [ds]
  (reify adapter/EffectAdapter
    (dispatch [_ effect] (ship! ds (:request-payload effect)))
    (query-status [_ _] {:outcome :unknown})))

(defn adapters
  "The example's adapters, over the database that holds its tables."
  [ds]
  {:effect-adapters {"inventory.reserve" (warehouse-reserve ds)
                     "inventory.release" (warehouse-release ds)
                     "payment.charge" (payment-provider ds)
                     "shipping.dispatch" (carrier ds)}
   :store-adapters {}})
