(ns voucha.example.order
  "A small example written in Java: an order and its line items.

      Order -> Item

  The definition is `example.OrderProfile` and the handlers are
  `example.OrderHandlers`, both under `java-src/`. This namespace assembles them
  into what `voucha dev`, `voucha compile` and the tests take, and adds the chain
  view, a presentation detail. The SEPA example in `voucha.example.batch` is the
  full-size one, in Clojure; this is the one to read first.

  Compile the Java sources with `clojure -T:build javac` before loading this."
  (:require [voucha.example.order-adapters :as adapters]
            [voucha.example.order-data :as order-data]
            [voucha.sdk.java :as jsdk])
  (:import (example OrderHandlers OrderProfile)))

(def chain-views
  {"OrderChain"
   {:node-type "Order"
    :steps [{:name "Validate" :active-states ["ORDER_NEW" "ORDER_VALIDATING"]
             :complete-states ["ORDER_RESERVE_ITEMS" "ORDER_RESERVING" "ORDER_CHARGING" "ORDER_CHARGE_RETRY_WAIT" "ORDER_CHARGED" "ORDER_SHIPPING"
                               "ORDER_RELEASING" "ORDER_COMPLETED" "ORDER_CANCELLED" "ORDER_SHIPPING_FAILED"]
             :failed-states ["ORDER_REJECTED"]}
            {:name "Reserve" :active-states ["ORDER_RESERVE_ITEMS" "ORDER_RESERVING"]
             :complete-states ["ORDER_CHARGING" "ORDER_CHARGE_RETRY_WAIT" "ORDER_CHARGED" "ORDER_SHIPPING" "ORDER_COMPLETED" "ORDER_SHIPPING_FAILED"]
             :failed-states ["ORDER_RELEASING" "ORDER_CANCELLED"]
             :progress-counters [:items-reserved :items-unavailable]}
            {:name "Charge" :active-states ["ORDER_CHARGING" "ORDER_CHARGE_RETRY_WAIT" "ORDER_CHARGED"]
             :complete-states ["ORDER_SHIPPING" "ORDER_COMPLETED" "ORDER_SHIPPING_FAILED"]
             :failed-states ["ORDER_RELEASING" "ORDER_CANCELLED"]}
            {:name "Ship" :active-states ["ORDER_SHIPPING"]
             :complete-states ["ORDER_COMPLETED"]
             :failed-states ["ORDER_SHIPPING_FAILED"]}]}})

(def ^:private assembled
  (jsdk/definition {:profile (OrderProfile.)
                    :handler-classes [OrderHandlers]
                    ;; the handlers' source: every method in the extractor's subset
                    ;; becomes a plan on its transition, which is what the kernel runs
                    :sources ["java-src/example/OrderHandlers.java"]
                    :chain-views chain-views}))

(def definition (:definition assembled))
(def handlers (:handlers assembled))

(def app
  "`voucha dev voucha.example.order/app` runs it against the example's own
  tables: a warehouse, a payment provider and a carrier, each an adapter over
  the same database (`voucha.example.order-adapters`), loaded with a catalogue
  and customers (`voucha.example.order-data`). `--scenario N` places N
  generated orders at start."
  {:definition definition
   :handlers handlers
   :migrations "example/order/migrations"
   :setup order-data/seed!
   :adapters adapters/adapters
   :scenario (fn [ds _rt {:keys [artifact-id n seed]}]
               (order-data/place-orders! ds artifact-id {:n n :seed (or seed 1)}))
   :report order-data/report
   :effect-adapters {}
   :store-adapters {}})
