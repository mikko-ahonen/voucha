(ns voucha.travel-example-test
  "The travel booking example, end to end: the client's answers arrive as
  callbacks, the 24-hour limits as effect timeouts, and the bookings are
  reversed and retried as OMG's scenario says."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.travel :as travel]
            [voucha.fake :as fake]
            [voucha.invariants :as inv]
            [voucha.kernel :as kernel]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]])
  (:import (java.sql Timestamp)
           (java.time Duration Instant)))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private tree-id "TRIP")

(defn- ok [] {:outcome :completed})

(defn- rt
  "Every provider answers at once and well; the client answers later, by a
  callback. `overrides` replaces adapters by effect name."
  ([] (rt {}))
  ([overrides]
   (runtime/runtime
    {:handlers travel/handlers
     :effect-adapters (merge {"inventory.search"    (fake/effect-adapter [(ok)])
                              "client.offer"        (fake/effect-adapter [{:outcome :dispatched}])
                              "client.card-request" (fake/effect-adapter [{:outcome :dispatched}])
                              "reservation.book"    (fake/effect-adapter [{:outcome :completed :payload {:ref "BK-1"}}])
                              "reservation.cancel"  (fake/effect-adapter [(ok)])
                              "card.charge"         (fake/effect-adapter [{:outcome :completed :payload {:ref "CH-1"}}])
                              "client.notify"       (fake/effect-adapter [(ok)])}
                             overrides)
     :store-adapters {"record.update" (fake/store-adapter)}
     :config {:dispatcher {:batch-size 25 :backoff-ms 20 :max-attempts 3} :event {:max-attempts 3}}})))

(def ^:private flight-and-hotel
  [{:reservation-id "T1-F" :kind "flight"} {:reservation-id "T1-H" :kind "hotel"}])

(defn- request! [reservations]
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition travel/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "T1" :root-node-type "Trip"
                               :profile-name travel/profile-name :profile-version travel/profile-version})
      (kernel/submit-event! tx {:event-id "requested" :tree-id tree-id :target-node-id "T1"
                                :event-type "ReservationRequested"
                                :payload {:client "C1" :reservations reservations}}))))

(defn- state-of [nid] (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id nid))))
(defn- data-of [nid] (:local-data (db/in-tx [tx *ds*] (node/fetch tx tree-id nid))))

(defn- answer!
  "The client answers every outstanding request of that kind: the callback the
  agency's front end would deliver."
  [effect-name outcome payload]
  (let [rows (db/in-tx [tx *ds*]
               (db/execute! tx ["SELECT effect_instance_id FROM effect_instance WHERE effect_name = ? AND status = 'DISPATCHED'"
                                effect-name]))]
    (doseq [row rows]
      (dispatcher/deliver-callback! *ds* {:effect-instance-id (:effect_instance_id row) :outcome outcome :payload payload}))
    (count rows)))

(defn- a-day-passes! []
  (dispatcher/fire-effect-timeouts! *ds* (Timestamp/from (.plus (Instant/now) (Duration/ofHours 25)))))

(defn- submit! [event-type payload]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:tree-id tree-id :target-node-id "T1" :event-type event-type :payload payload})))

(defn- calls [r effect-name] (fake/calls (get-in r [:effect-adapters effect-name])))

(defn- oracle-ok? [] (:ok? (db/in-tx [tx *ds*] (inv/check-all tx :handlers travel/handlers))))

(deftest the-definition-compiles-with-its-invariants-provable
  (let [a (compiler/compile-definition travel/definition)]
    (is (empty? (compiler/validate travel/definition)))
    (is (= [:provable] (distinct (map :class (vals (get-in a [:specs :invariants]))))))
    (is (= ["CardUpdated" "ReservationRequested"] (compiler/external-events (:specs a)))
        "the client's answers are callbacks; only the request and a card update come from outside")))

(deftest a-trip-is-offered-booked-charged-and-confirmed
  (request! flight-and-hotel)
  (let [r (rt)]
    (runtime/settle! *ds* r)
    (is (= "TRIP_OFFERED" (state-of "T1")) "the alternatives are with the client")
    (is (= 1 (answer! "client.offer" :completed {:selection "alt-2"})))
    (runtime/settle! *ds* r)
    (is (= "TRIP_AWAITING_CARD" (state-of "T1")))
    (is (= 1 (answer! "client.card-request" :completed {:card "4111"})))
    (runtime/settle! *ds* r)
    (is (= "TRIP_CONFIRMED" (state-of "T1")))
    (is (= ["RES_CONFIRMED" "RES_CONFIRMED"] [(state-of "T1-F") (state-of "T1-H")]))
    (is (= {:selection "alt-2" :card "4111"} (select-keys (data-of "T1") [:selection :card])))
    (is (= 2 (count (calls r "reservation.book"))))
    (is (= 1 (count (calls r "card.charge"))))
    (is (empty? (calls r "reservation.cancel")))
    (is (oracle-ok?))))

(deftest a-client-who-does-not-answer-within-24-hours-is-cancelled
  (request! flight-and-hotel)
  (let [r (rt)]
    (runtime/settle! *ds* r)
    (is (= "TRIP_OFFERED" (state-of "T1")))
    (is (= 1 (a-day-passes!)) "the offer timed out")
    (runtime/settle! *ds* r)
    (is (= "TRIP_CANCELLED" (state-of "T1")))
    (is (= ["RES_CLOSED" "RES_CLOSED"] [(state-of "T1-F") (state-of "T1-H")]))
    (is (= "no selection within 24 hours" (:error (data-of "T1"))))
    (is (= ["start again"] (map #(get-in % [:request :message]) (calls r "client.notify"))))
    (is (empty? (calls r "reservation.book")))
    (is (oracle-ok?))))

(deftest a-cancelled-card-request-is-cancelled-the-same-way
  (request! flight-and-hotel)
  (let [r (rt)]
    (runtime/settle! *ds* r)
    (answer! "client.offer" :completed {:selection "alt-1"})
    (runtime/settle! *ds* r)
    (is (= 1 (answer! "client.card-request" :failed {})))
    (runtime/settle! *ds* r)
    (is (= "TRIP_CANCELLED" (state-of "T1")))
    (is (= "request cancelled" (:error (data-of "T1"))))
    (is (oracle-ok?))))

(defn- to-booking!
  "Through the offer and the card, into the booking."
  [r]
  (runtime/settle! *ds* r)
  (answer! "client.offer" :completed {:selection "alt-1"})
  (runtime/settle! *ds* r)
  (answer! "client.card-request" :completed {:card "4111"})
  (runtime/settle! *ds* r))

(deftest a-rejected-booking-reverses-the-other-and-retries
  (request! flight-and-hotel)
  (let [r (rt {"reservation.book" (fake/effect-adapter [{:outcome :completed :payload {:ref "BK-1"}}
                                                        {:outcome :failed :payload {:reason "sold out"}}
                                                        (ok)])})]
    (to-booking! r)
    (is (= "TRIP_CONFIRMED" (state-of "T1")) "the second cycle booked both")
    (is (= 1 (:booking-retries (data-of "T1"))))
    (is (= 4 (count (calls r "reservation.book"))) "two bookings per cycle, two cycles")
    (is (= 1 (count (calls r "reservation.cancel"))) "the booking that succeeded was reversed; the rejected one had nothing to reverse")
    (is (= 1 (count (calls r "card.charge"))) "charged once, after the cycle that booked both")
    (is (= [0 0 1 1] (sort (map #(get-in % [:request :attempt]) (calls r "reservation.book"))))
        "each cycle books under a new key")
    (is (oracle-ok?))))

(deftest a-declined-card-reverses-the-bookings-and-asks-for-the-card-again
  (request! flight-and-hotel)
  (let [r (rt {"card.charge" (fake/effect-adapter [{:outcome :failed :payload {:reason "declined"}}
                                                   {:outcome :completed :payload {:ref "CH-2"}}])})]
    (to-booking! r)
    (is (= "TRIP_AWAITING_CARD" (state-of "T1")) "reversed, recorded, and asked for the card again")
    (is (= ["RES_CANCELLED" "RES_CANCELLED"] [(state-of "T1-F") (state-of "T1-H")]))
    (is (= 2 (count (calls r "reservation.cancel"))) "both bookings reversed")
    (is (= 1 (answer! "client.card-request" :completed {:card "5500"})))
    (runtime/settle! *ds* r)
    (is (= "TRIP_CONFIRMED" (state-of "T1")))
    (is (= "5500" (:card (data-of "T1"))))
    (is (= 1 (:payment-retries (data-of "T1"))))
    (is (= [0 1] (map #(get-in % [:request :attempt]) (calls r "card.charge"))) "a new charge key per payment attempt")
    (is (= 4 (count (calls r "reservation.book"))) "booked again after the reversal")
    (is (oracle-ok?))))

(deftest bookings-that-keep-failing-give-up-at-the-limit-and-notify
  (request! flight-and-hotel)
  (let [r (rt {"reservation.book" (fake/effect-adapter [{:outcome :failed :payload {:reason "sold out"}}])})]
    (to-booking! r)
    (is (= "TRIP_FAILED" (state-of "T1")))
    (is (= ["RES_CLOSED" "RES_CLOSED"] [(state-of "T1-F") (state-of "T1-H")]))
    (is (= 2 (:booking-retries (data-of "T1"))) "the limit")
    (is (= 6 (count (calls r "reservation.book"))) "three cycles of two")
    (is (empty? (calls r "reservation.cancel")) "nothing was ever booked, nothing to reverse")
    (is (empty? (calls r "card.charge")))
    (is (= ["booking retry limit exceeded"] (map #(get-in % [:request :message]) (calls r "client.notify"))))
    (is (oracle-ok?))))

(deftest a-card-update-while-booking-is-saved
  (request! flight-and-hotel)
  (let [r (rt {"reservation.book" (fake/effect-adapter [{:outcome :dispatched}])})]
    (to-booking! r)
    (is (= "TRIP_BOOKING" (state-of "T1")) "the bookings are with the providers")
    (submit! "CardUpdated" {:card "4999"})
    (runtime/settle! *ds* r)
    (is (= "TRIP_BOOKING" (state-of "T1")))
    (is (= "4999" (:card (data-of "T1"))) "saved")
    (is (= 2 (answer! "reservation.book" :completed {:ref "BK"})))
    (runtime/settle! *ds* r)
    (is (= "TRIP_CONFIRMED" (state-of "T1")))
    (is (= "4999" (get-in (first (calls r "card.charge")) [:request :card])) "charged to the updated card")
    (is (oracle-ok?))))

(deftest an-uncertain-charge-is-asked-about-never-charged-twice
  (request! flight-and-hotel)
  (let [charge (fake/effect-adapter [{:throw "gateway timeout"}] {:outcome :completed :payload {:ref "CH-LATE"}})
        r (rt {"card.charge" charge})]
    (to-booking! r)
    (is (= "TRIP_CONFIRMED" (state-of "T1")))
    (is (= [:dispatch :query-status] (mapv :op (fake/calls charge))) "one charge, then a status query")
    (is (oracle-ok?))))

(deftest an-empty-request-is-cancelled-before-anything-is-searched
  (request! [])
  (let [r (rt)]
    (runtime/settle! *ds* r)
    (is (= "TRIP_CANCELLED" (state-of "T1")))
    (is (empty? (calls r "inventory.search")))
    (is (= ["start again"] (map #(get-in % [:request :message]) (calls r "client.notify"))))
    (is (oracle-ok?))))
