(ns voucha.example.order-data
  "The order example's data: a catalogue, customers, and orders generated from
  them. `seed!` loads the catalogue and the customers into the example's own
  tables; `orders` makes reproducible orders over them; `place-order!` sends one
  through the kernel.

  The data is chosen so that a run of generated orders exercises every path:
  stock runs out of the scarce products, one customer's credit limit is low
  enough to decline a large order, and the rest complete and ship."
  (:require [voucha.db :as db]
            [voucha.kernel :as kernel])
  (:import (java.util Random)))

(def products
  [{:sku "SKU-1001" :name "Field notebook, A5, dotted" :price-cents 1250 :stock 40}
   {:sku "SKU-1002" :name "Fountain pen, fine nib" :price-cents 4900 :stock 25}
   {:sku "SKU-1003" :name "Ink, 50 ml, blue-black" :price-cents 1800 :stock 60}
   {:sku "SKU-1004" :name "Desk lamp, brass" :price-cents 12900 :stock 6}
   {:sku "SKU-1005" :name "Letter paper, 100 sheets" :price-cents 2200 :stock 30}
   {:sku "SKU-1006" :name "Envelopes, C6, 50" :price-cents 900 :stock 50}
   {:sku "SKU-1007" :name "Wax seal kit" :price-cents 3400 :stock 3}
   {:sku "SKU-1008" :name "Pencil set, 12" :price-cents 1500 :stock 45}])

(def customers
  [{:customer-id "C-100" :name "Aino Virtanen" :email "aino.virtanen@example.org" :credit-limit-cents 60000}
   {:customer-id "C-101" :name "Bram de Vries" :email "bram.devries@example.org" :credit-limit-cents 45000}
   {:customer-id "C-102" :name "Chiara Rossi" :email "chiara.rossi@example.org" :credit-limit-cents 9000}
   {:customer-id "C-103" :name "Dawid Nowak" :email "dawid.nowak@example.org" :credit-limit-cents 80000}
   {:customer-id "C-104" :name "Elin Berg" :email "elin.berg@example.org" :credit-limit-cents 30000}])

(defn seed!
  "Load the catalogue and the customers; a second call changes nothing."
  [ds]
  (db/in-tx [tx ds]
    (doseq [{:keys [sku name price-cents stock]} products]
      (db/execute-one! tx ["INSERT INTO example_product (sku, name, price_cents, stock) VALUES (?, ?, ?, ?)
                            ON CONFLICT (sku) DO NOTHING" sku name price-cents stock]))
    (doseq [{:keys [customer-id name email credit-limit-cents]} customers]
      (db/execute-one! tx ["INSERT INTO example_customer (customer_id, name, email, credit_limit_cents) VALUES (?, ?, ?, ?)
                            ON CONFLICT (customer_id) DO NOTHING" customer-id name email credit-limit-cents]))))

(defn- price-of [sku] (:price-cents (first (filter #(= sku (:sku %)) products))))

(defn- shuffle* [^Random r coll]
  (let [a (java.util.ArrayList. ^java.util.Collection (vec coll))]
    (java.util.Collections/shuffle a r)
    (vec a)))

(defn orders
  "`n` orders, the same for the same `seed`: a customer, one to three lines of
  distinct products, one to three of each. Each is the payload of `OrderPlaced`
  plus the ids the tree needs."
  [seed n]
  (let [r (Random. (long seed))]
    (vec (for [i (range 1 (inc n))
               :let [order-id (format "O-%03d" i)
                     customer (nth customers (.nextInt r (count customers)))
                     skus (take (inc (.nextInt r 3)) (shuffle* r (map :sku products)))]]
           {:tree-id order-id
            :order-id order-id
            :customer (:customer-id customer)
            :items (vec (map-indexed (fn [k sku]
                                       {:item-id (format "%s-L%d" order-id (inc k))
                                        :sku sku :qty (inc (.nextInt r 3)) :price (price-of sku)})
                                     skus))}))))

(defn place-order!
  "Create the order's tree and submit `OrderPlaced` with its lines."
  [ds artifact-id {:keys [tree-id order-id customer items]}]
  (db/in-tx [tx ds]
    (kernel/create-tree! tx {:tree-id tree-id :artifact-id artifact-id
                             :root-node-id order-id :root-node-type "Order"})
    (kernel/submit-event! tx {:event-id (str tree-id ":placed") :tree-id tree-id :target-node-id order-id
                              :event-type "OrderPlaced" :payload {:customer customer :items items}})))

(defn place-orders!
  "Seed the data and place `n` generated orders. Returns the orders placed."
  [ds artifact-id {:keys [n seed] :or {n 10 seed 1}}]
  (seed! ds)
  (let [os (orders seed n)]
    (doseq [o os] (place-order! ds artifact-id o))
    os))

(defn report
  "What the example's tables say, for a banner or a test: stock per product,
  and the reservations, payments and shipments by status."
  [ds]
  (let [count-by (fn [table] (into {} (map (juxt :status :n))
                                   (db/execute! ds [(str "SELECT status, COUNT(*) AS n FROM " table " GROUP BY status")])))]
    {:stock (into {} (map (juxt :sku :stock)) (db/execute! ds ["SELECT sku, stock FROM example_product ORDER BY sku"]))
     :reservations (count-by "example_reservation")
     :payments (count-by "example_payment")
     :shipments (count-by "example_shipment")}))
