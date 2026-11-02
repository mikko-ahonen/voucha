(ns voucha.example.travel
  "The travel booking example: chapter 9 of OMG's BPMN 2.0 by Example (2010),
  transcribed as a definition. The process is theirs; nothing here was designed
  for voucha, which is the point: it is the third example, written by someone
  else and of a different shape, the saga.

      Trip -> Reservation (a flight, a hotel room)

  Their scenario, in their words: the agency receives a flight and hotel room
  reservation request; after research and evaluation of availability, selected
  alternatives are offered to the client, who has 24 hours to select one or
  cancel. A cancellation, or the delay, updates the client record and notifies
  the client. On a selection the client is asked for credit card information,
  again with 24 hours. Then the booking activities: the flight and the hotel
  room are booked, with measures to reverse the reservations if problems occur;
  the client may send credit card modifications before the booking completes,
  and they are saved. An error during booking reverses the reservations,
  updates the client record and retries the booking while the booking retry
  limit is not exceeded. After a successful booking the reservations are
  charged to the card and the process stops on confirmation; an error here
  reverses the reservations, asks the client for the card again and retries the
  booking while the payment retry limit is not exceeded. When a retry limit is
  exceeded, the client is notified and the process stops.

  How their diagram maps onto this definition, task by task:

    * Search Flights / Hotel Rooms, Evaluate within Customer Criteria, Package
      for Customer Review: one effect, `inventory.search`, whose completion
      carries the packaged alternatives.
    * Present Alternatives to Customer, 24 hours, Customer make Selection /
      Cancel Request: the effect `client.offer`, answered by a callback (the
      selection or the cancellation) or by its 24-hour completion timeout.
    * Request Credit Card Information, 24 Hours, Request Cancelled: the effect
      `client.card-request`, the same shape.
    * Update Customer Record: the store effect `record.update`; Notify Customer
      (to Start Again, Failed Booking, Invalid Credit Card): `client.notify`.
    * The Booking sub-process with Book Flight and Book Hotel and their
      compensations Cancel Flight and Cancel Hotel: the two Reservation
      children, each booking through `reservation.book` and reversing through
      `reservation.cancel` when the trip commands it. Handle Booking Error is
      the trip's reversing state, entered once every booking has settled, since
      a booking in flight cannot be interrupted, only awaited.
    * Update Credit Card Information (the event sub-process): the outside
      event `CardUpdated`, accepted while booking and saved.
    * Charge Credit Card: `card.charge`. Retry Limit Exceeded?: the plan on the
      record update after a reversal, which emits the retry or the give-up.
    * Reservation Completed / Booking Not Completed: before a trip ends, its
      reservations are confirmed or closed, so a finished trip holds only
      finished reservations; the diagram leaves this implicit.

  Two readings are ours. An empty request is cancelled at once rather than
  booked. And both uncertain operations, a booking and the charge, use the
  query policy: a booking whose outcome is unknown is asked about, never booked
  again, which is what reversal-on-error assumes.")

(def profile-name "travel-booking")
(def profile-version "v1")

(def ^:private booking-retry-limit 2)
(def ^:private payment-retry-limit 2)
(def ^:private twenty-four-hours (* 24 60 60 1000))

(def events
  #{;; --- Trip ---------------------------------------------------------------
    "ReservationRequested" "NothingToBook" "AlternativesPackaged" "SearchFailed"
    "SelectionMade" "OfferCancelled" "OfferExpired"
    "CardProvided" "CardRequestCancelled" "CardRequestExpired" "CardUpdated"
    "BookCommanded" "CancelCommanded" "ConfirmCommanded" "CloseCommanded"
    "ReservationBooked" "ReservationFailed" "ReservationReleased" "ReservationSettled"
    "AllReservationsBooked" "AllReservationsSettled" "AllReservationsReleased"
    "AllReservationsConfirmed" "ClosedAfterFailure" "ClosedAfterCancellation"
    "CardCharged" "CardDeclined" "RecordUpdated" "RecordUpdateFailed" "ClientNotified" "NotifyFailed"
    "RetryBooking" "RetryPayment" "BookingGaveUp" "PaymentGaveUp"
    ;; --- Reservation --------------------------------------------------------
    "ReservationConfirmed" "ReservationRejected"
    "ReservationCancelConfirmed" "ReservationCancelFailed"})

(def effects
  {;; Search flights and hotel rooms, evaluate them within the client's criteria,
   ;; package the alternatives: the agency's own systems, repeatable.
   "inventory.search"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "AlternativesPackaged"
    :failure-event "SearchFailed"}

   ;; Present the alternatives. The client answers later, by a callback: a
   ;; selection, or a cancellation; 24 hours without one expires the offer.
   "client.offer"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "SelectionMade"
    :failure-event "OfferCancelled"
    :completion-timeout-ms twenty-four-hours :timeout-event "OfferExpired"}

   ;; Ask for the credit card information, once per payment attempt; the same
   ;; shape: an answer, a cancellation, or 24 hours.
   "client.card-request"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id :attempt] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "CardProvided"
    :failure-event "CardRequestCancelled"
    :completion-timeout-ms twenty-four-hours :timeout-event "CardRequestExpired"}

   ;; Book a flight or a hotel room, once per booking cycle. A booking whose
   ;; outcome is unknown is asked about, never booked again.
   "reservation.book"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id :attempt] :correlation-key :node-id
    :uncertainty-policy :query :status-query "reservation.status"
    :completion-event "ReservationConfirmed"
    :failure-event "ReservationRejected"}

   ;; Reverse a booking. Idempotent, so safe to retry.
   "reservation.cancel"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id :attempt] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "ReservationCancelConfirmed"
    :failure-event "ReservationCancelFailed"}

   ;; Charge the card, once per payment attempt; an unknown outcome is queried.
   "card.charge"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id :attempt] :correlation-key :node-id
    :uncertainty-policy :query :status-query "card.status"
    :completion-event "CardCharged"
    :failure-event "CardDeclined"}

   ;; The client record, and the client's notifications. Neither has a failure
   ;; path in the scenario: one that fails after its retries is recorded, and
   ;; the process goes on.
   "record.update"
   {:version "v1" :kind :store :adapter "crm"
    :idempotency-key [:tree-id :node-id :status :cycle] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "RecordUpdated"
    :failure-event "RecordUpdateFailed"}

   "client.notify"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id :message] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "ClientNotified"
    :failure-event "NotifyFailed"}})

(def ^:private reservation-record {:reservation-id :str :kind :str})

(def payloads
  {"ReservationRequested" {:client :str :reservations [:set reservation-record]}
   "SelectionMade" {:selection :str}
   "CardProvided" {:card :str}
   "CardUpdated" {:card :str}
   "BookCommanded" {:kind :str :selection :str :cycle :int}
   "SearchFailed" {:error :str}
   "ReservationRejected" {:reason :str}
   "ReservationCancelFailed" {:error :str}
   "CardDeclined" {:reason :str}})

;; A reservation moves only on its trip's command, and only while the trip is in
;; the state that issues it: a late command finds its rule failed and is ignored.
(def ^:private under-booking-trip {:constraint :parent-state :allowed ["TRIP_BOOKING" "TRIP_BOOKING_FAILED"]})
(def ^:private under-reversing-trip {:constraint :parent-state :allowed ["TRIP_REVERSING"]})
(def ^:private under-confirming-trip {:constraint :parent-state :allowed ["TRIP_CONFIRMING"]})
(def ^:private under-closing-trip {:constraint :parent-state :allowed ["TRIP_CLOSING_FAILED" "TRIP_CLOSING_CANCELLED"]})

(def node-types
  {"Trip"
   {:may-contain ["Reservation"]
    :initial-state "TRIP_NEW"
    :data {:client :str :reservations [:set reservation-record] :children [:set :str]
           :selection :str :card :str :booking-retries :int :payment-retries :int
           :failed-phase :str :error :str}
    :states ["TRIP_NEW" "TRIP_SEARCHING" "TRIP_OFFERED" "TRIP_AWAITING_CARD"
             "TRIP_BOOKING" "TRIP_BOOKING_FAILED" "TRIP_CHARGING" "TRIP_CONFIRMING" "TRIP_CONFIRMED"
             "TRIP_REVERSING" "TRIP_RECORDING_FAILURE" "TRIP_RETRY_WAIT"
             "TRIP_CLOSING_FAILED" "TRIP_NOTIFYING_FAILURE" "TRIP_FAILED"
             "TRIP_CANCELLING" "TRIP_CLOSING_CANCELLED" "TRIP_NOTIFYING_CANCELLATION" "TRIP_CANCELLED"]
    :terminal-states ["TRIP_CONFIRMED" "TRIP_FAILED" "TRIP_CANCELLED"]
    :failure-state "TRIP_FAILED"
    :may-emit-effects ["inventory.search" "client.offer" "client.card-request" "card.charge"
                       "record.update" "client.notify"]
    :hosts {"Reservation" "TRIP_BOOKING"}
    :counters {:booked    {:child-type "Reservation" :when-child-enters ["RES_BOOKED"]}
               :failed    {:child-type "Reservation" :when-child-enters ["RES_FAILED"]}
               :cancelled {:child-type "Reservation" :when-child-enters ["RES_CANCELLED"]}
               :confirmed {:child-type "Reservation" :when-child-enters ["RES_CONFIRMED"]}
               :closed    {:child-type "Reservation" :when-child-enters ["RES_CLOSED"]}}
    ;; The card is charged only while every reservation is booked; a finished
    ;; trip holds only finished reservations.
    :child-states-allowed {"TRIP_CHARGING"  {"Reservation" ["RES_BOOKED"]}
                           "TRIP_CONFIRMED" {"Reservation" ["RES_CONFIRMED"]}
                           "TRIP_FAILED"    {"Reservation" ["RES_CLOSED"]}
                           "TRIP_CANCELLED" {"Reservation" ["RES_CLOSED"]}}
    ;; Credit card modifications are taken while booking; elsewhere they are late.
    :ignores {"TRIP_SEARCHING" ["CardUpdated"] "TRIP_OFFERED" ["CardUpdated"]
              "TRIP_AWAITING_CARD" ["CardUpdated"] "TRIP_CHARGING" ["CardUpdated"]
              "TRIP_CONFIRMING" ["CardUpdated"] "TRIP_REVERSING" ["CardUpdated"]
              "TRIP_RECORDING_FAILURE" ["CardUpdated"] "TRIP_RETRY_WAIT" ["CardUpdated"]
              "TRIP_CLOSING_FAILED" ["CardUpdated"] "TRIP_NOTIFYING_FAILURE" ["CardUpdated"]
              "TRIP_CANCELLING" ["CardUpdated"] "TRIP_CLOSING_CANCELLED" ["CardUpdated"]
              "TRIP_NOTIFYING_CANCELLATION" ["CardUpdated"]}}

   "Reservation"
   {:may-contain []
    :initial-state "RES_NEW"
    :data {:kind :str :selection :str :cycle :int :error :str :reversal-pending :bool}
    :states ["RES_NEW" "RES_BOOKING" "RES_BOOKED" "RES_FAILED" "RES_CANCELLING" "RES_CANCELLED"
             "RES_CONFIRMED" "RES_CLOSED"]
    :terminal-states ["RES_CONFIRMED" "RES_CLOSED"]
    :failure-state "RES_CLOSED"
    :may-emit-effects ["reservation.book" "reservation.cancel"]
    ;; A reversal reaches every reservation; one never commanded has nothing to reverse.
    :ignores {"RES_NEW" ["CancelCommanded"] "RES_CANCELLED" ["CancelCommanded"]}}})

(def ^:private cycle
  "The booking cycle: one more each time the booking is retried, for either reason."
  [:+ [:or [:data :booking-retries] 0] [:or [:data :payment-retries] 0]])

(def ^:private book-each
  [:each :r [:data :reservations]
   [:emit-to [:get [:var :r] :reservation-id] "BookCommanded"
    {:kind [:get [:var :r] :kind] :selection [:data :selection] :cycle cycle}]])

(def ^:private command-each
  (fn [event] [:each :id [:data :children] [:emit-to [:var :id] event]]))

(def ^:private decide-retry
  "Retry Limit Exceeded? -- for the phase that failed."
  [[:if [:= [:data :failed-phase] "booking"]
    [[:if [:< [:or [:data :booking-retries] 0] booking-retry-limit]
      [[:inc [:booking-retries] 1] [:emit-to :self "RetryBooking"]]
      [[:emit-to :self "BookingGaveUp"]]]]
    [[:if [:< [:or [:data :payment-retries] 0] payment-retry-limit]
      [[:inc [:payment-retries] 1] [:emit-to :self "RetryPayment"]]
      [[:emit-to :self "PaymentGaveUp"]]]]]])

(def ^:private cancel-request
  "Update Customer Record (Request Cancelled)."
  [[:store "record.update" {:client [:data :client] :status "cancelled" :cycle cycle}]
   [:await "RecordUpdated"]])

(def transitions
  [;; ============================ Trip ==================================
   ;; Receive Customer Flight and Hotel Room Reservation Request; search and
   ;; evaluate flights and hotel rooms, package the alternatives.
   {:node-type "Trip" :from "TRIP_NEW" :on "ReservationRequested" :to "TRIP_SEARCHING"
    :handler 'trip/received
    :plan [[:set [:client] [:payload :client]]
           [:set [:reservations] [:payload :reservations]]
           [:set [:children] [:map [:payload :reservations] :reservation-id]]
           [:each :r [:payload :reservations] [:child [:get [:var :r] :reservation-id] "Reservation"]]
           [:if [:> [:count [:payload :reservations]] 0]
            [[:call "inventory.search" {:client [:payload :client]}]
             [:await "AlternativesPackaged"]]
            [[:emit-to :self "NothingToBook"]]]]}
   {:node-type "Trip" :from "TRIP_SEARCHING" :on "NothingToBook" :to "TRIP_CANCELLING"
    :handler 'trip/cancel-request
    :plan (into [[:set [:error] "nothing to book"]] cancel-request)}
   {:node-type "Trip" :from "TRIP_SEARCHING" :on "SearchFailed" :to "TRIP_CANCELLING"
    :handler 'trip/cancel-request
    :plan (into [[:set [:error] [:or [:payload :error] "no alternatives"]]] cancel-request)}
   ;; Present Flights and Hotel Rooms Alternatives to Customer; 24 hours.
   {:node-type "Trip" :from "TRIP_SEARCHING" :on "AlternativesPackaged" :to "TRIP_OFFERED"
    :handler 'trip/offer
    :plan [[:call "client.offer" {:client [:data :client]}]
           [:await "SelectionMade"]]}
   {:node-type "Trip" :from "TRIP_OFFERED" :on "OfferCancelled" :to "TRIP_CANCELLING"
    :handler 'trip/cancel-request
    :plan (into [[:set [:error] "request cancelled"]] cancel-request)}
   {:node-type "Trip" :from "TRIP_OFFERED" :on "OfferExpired" :to "TRIP_CANCELLING"
    :handler 'trip/cancel-request
    :plan (into [[:set [:error] "no selection within 24 hours"]] cancel-request)}
   ;; Customer make Selection; Request Credit Card Information from Customer; 24 hours.
   {:node-type "Trip" :from "TRIP_OFFERED" :on "SelectionMade" :to "TRIP_AWAITING_CARD"
    :handler 'trip/request-card
    :plan [[:set [:selection] [:payload :selection]]
           [:call "client.card-request" {:client [:data :client] :attempt [:or [:data :payment-retries] 0]}]
           [:await "CardProvided"]]}
   {:node-type "Trip" :from "TRIP_AWAITING_CARD" :on "CardRequestCancelled" :to "TRIP_CANCELLING"
    :handler 'trip/cancel-request
    :plan (into [[:set [:error] "request cancelled"]] cancel-request)}
   {:node-type "Trip" :from "TRIP_AWAITING_CARD" :on "CardRequestExpired" :to "TRIP_CANCELLING"
    :handler 'trip/cancel-request
    :plan (into [[:set [:error] "no credit card information within 24 hours"]] cancel-request)}
   ;; Booking: Book Flight, Book Hotel.
   {:node-type "Trip" :from "TRIP_AWAITING_CARD" :on "CardProvided" :to "TRIP_BOOKING"
    :handler 'trip/book
    :plan [[:set [:card] [:payload :card]] book-each]}
   ;; Update Credit Card Information, before the booking is completed.
   {:node-type "Trip" :from "TRIP_BOOKING" :on "CardUpdated" :to "TRIP_BOOKING"
    :handler 'trip/update-card
    :plan [[:set [:card] [:payload :card]]]}
   {:node-type "Trip" :from "TRIP_BOOKING_FAILED" :on "CardUpdated" :to "TRIP_BOOKING_FAILED"
    :handler 'trip/update-card
    :plan [[:set [:card] [:payload :card]]]}
   ;; Each booking's result brings the trip to decide again: every reservation
   ;; booked, charge the card. A failed booking waits for the others to settle,
   ;; then reverses what was booked (Handle Booking Error).
   {:node-type "Trip" :from "TRIP_BOOKING" :on "ReservationBooked" :to "TRIP_BOOKING"}
   {:node-type "Trip" :from "TRIP_BOOKING" :on "AllReservationsBooked" :to "TRIP_CHARGING"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:booked]}]
    :handler 'trip/charge
    :plan [[:call "card.charge" {:card [:data :card] :attempt [:or [:data :payment-retries] 0]}]
           [:await "CardCharged"]]}
   {:node-type "Trip" :from "TRIP_BOOKING" :on "ReservationFailed" :to "TRIP_BOOKING_FAILED"
    :handler 'trip/booking-failed
    :plan [[:set [:failed-phase] "booking"]]}
   {:node-type "Trip" :from "TRIP_BOOKING_FAILED" :on "ReservationBooked" :to "TRIP_BOOKING_FAILED"}
   {:node-type "Trip" :from "TRIP_BOOKING_FAILED" :on "ReservationFailed" :to "TRIP_BOOKING_FAILED"}
   {:node-type "Trip" :from "TRIP_BOOKING_FAILED" :on "AllReservationsSettled" :to "TRIP_REVERSING"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:booked :failed]}]
    :handler 'trip/reverse
    :plan [(command-each "CancelCommanded")]}
   ;; Charge Credit Card: confirmed, or declined, which reverses the bookings.
   {:node-type "Trip" :from "TRIP_CHARGING" :on "CardCharged" :to "TRIP_CONFIRMING"
    :handler 'trip/confirm
    :plan [(command-each "ConfirmCommanded")]}
   {:node-type "Trip" :from "TRIP_CHARGING" :on "CardDeclined" :to "TRIP_REVERSING"
    :handler 'trip/reverse
    :plan [[:set [:failed-phase] "payment"]
           [:set [:error] [:or [:payload :reason] "card declined"]]
           (command-each "CancelCommanded")]}
   {:node-type "Trip" :from "TRIP_CONFIRMING" :on "ReservationSettled" :to "TRIP_CONFIRMING"}
   {:node-type "Trip" :from "TRIP_CONFIRMING" :on "AllReservationsConfirmed" :to "TRIP_CONFIRMED"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:confirmed]}]}
   ;; Handle Compensation: every reservation released, then Update Customer
   ;; Record, then Retry Limit Exceeded?
   {:node-type "Trip" :from "TRIP_REVERSING" :on "ReservationReleased" :to "TRIP_REVERSING"}
   {:node-type "Trip" :from "TRIP_REVERSING" :on "AllReservationsReleased" :to "TRIP_RECORDING_FAILURE"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:cancelled]}]
    :handler 'trip/record-failure
    :plan [[:store "record.update" {:client [:data :client] :status [:data :failed-phase] :cycle cycle}]
           [:await "RecordUpdated"]]}
   {:node-type "Trip" :from "TRIP_RECORDING_FAILURE" :on "RecordUpdated" :to "TRIP_RETRY_WAIT"
    :handler 'trip/decide-retry
    :plan decide-retry}
   {:node-type "Trip" :from "TRIP_RECORDING_FAILURE" :on "RecordUpdateFailed" :to "TRIP_RETRY_WAIT"
    :handler 'trip/decide-retry
    :plan decide-retry}
   ;; The booking is tried again; or the client is asked for the card again
   ;; and then the booking is tried again.
   {:node-type "Trip" :from "TRIP_RETRY_WAIT" :on "RetryBooking" :to "TRIP_BOOKING"
    :handler 'trip/book
    :plan [book-each]}
   {:node-type "Trip" :from "TRIP_RETRY_WAIT" :on "RetryPayment" :to "TRIP_AWAITING_CARD"
    :handler 'trip/request-card
    :plan [[:call "client.card-request" {:client [:data :client] :attempt [:or [:data :payment-retries] 0]}]
           [:await "CardProvided"]]}
   ;; Notify Customer Failed Booking; Notify Customer Invalid Credit Card.
   {:node-type "Trip" :from "TRIP_RETRY_WAIT" :on "BookingGaveUp" :to "TRIP_CLOSING_FAILED"
    :handler 'trip/give-up
    :plan [[:set [:error] "booking retry limit exceeded"] (command-each "CloseCommanded")]}
   {:node-type "Trip" :from "TRIP_RETRY_WAIT" :on "PaymentGaveUp" :to "TRIP_CLOSING_FAILED"
    :handler 'trip/give-up
    :plan [[:set [:error] "invalid credit card"] (command-each "CloseCommanded")]}
   {:node-type "Trip" :from "TRIP_CLOSING_FAILED" :on "ReservationSettled" :to "TRIP_CLOSING_FAILED"}
   {:node-type "Trip" :from "TRIP_CLOSING_FAILED" :on "ClosedAfterFailure" :to "TRIP_NOTIFYING_FAILURE"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:closed]}]
    :handler 'trip/notify
    :plan [[:call "client.notify" {:client [:data :client] :message [:data :error]}]
           [:await "ClientNotified"]]}
   {:node-type "Trip" :from "TRIP_NOTIFYING_FAILURE" :on "ClientNotified" :to "TRIP_FAILED"}
   {:node-type "Trip" :from "TRIP_NOTIFYING_FAILURE" :on "NotifyFailed" :to "TRIP_FAILED"}
   ;; The request is cancelled: the record updated, then Notify Customer to Start Again.
   {:node-type "Trip" :from "TRIP_CANCELLING" :on "RecordUpdated" :to "TRIP_CLOSING_CANCELLED"
    :handler 'trip/close
    :plan [(command-each "CloseCommanded")]}
   {:node-type "Trip" :from "TRIP_CANCELLING" :on "RecordUpdateFailed" :to "TRIP_CLOSING_CANCELLED"
    :handler 'trip/close
    :plan [(command-each "CloseCommanded")]}
   {:node-type "Trip" :from "TRIP_CLOSING_CANCELLED" :on "ReservationSettled" :to "TRIP_CLOSING_CANCELLED"}
   {:node-type "Trip" :from "TRIP_CLOSING_CANCELLED" :on "ClosedAfterCancellation" :to "TRIP_NOTIFYING_CANCELLATION"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:closed]}]
    :handler 'trip/notify
    :plan [[:call "client.notify" {:client [:data :client] :message "start again"}]
           [:await "ClientNotified"]]}
   {:node-type "Trip" :from "TRIP_NOTIFYING_CANCELLATION" :on "ClientNotified" :to "TRIP_CANCELLED"}
   {:node-type "Trip" :from "TRIP_NOTIFYING_CANCELLATION" :on "NotifyFailed" :to "TRIP_CANCELLED"}

   ;; ========================= Reservation ==============================
   ;; Book Flight / Book Hotel, once per cycle of the trip's booking.
   {:node-type "Reservation" :from "RES_NEW" :on "BookCommanded" :to "RES_BOOKING"
    :handler 'reservation/book
    :requires [under-booking-trip]
    :plan [[:set [:kind] [:payload :kind]]
           [:set [:selection] [:payload :selection]]
           [:set [:cycle] [:payload :cycle]]
           [:call "reservation.book" {:kind [:payload :kind] :selection [:payload :selection] :attempt [:payload :cycle]}]
           [:await "ReservationConfirmed"]]}
   {:node-type "Reservation" :from "RES_FAILED" :on "BookCommanded" :to "RES_BOOKING"
    :handler 'reservation/book
    :requires [under-booking-trip]
    :plan [[:set [:kind] [:payload :kind]]
           [:set [:selection] [:payload :selection]]
           [:set [:cycle] [:payload :cycle]]
           [:call "reservation.book" {:kind [:payload :kind] :selection [:payload :selection] :attempt [:payload :cycle]}]
           [:await "ReservationConfirmed"]]}
   {:node-type "Reservation" :from "RES_CANCELLED" :on "BookCommanded" :to "RES_BOOKING"
    :handler 'reservation/book
    :requires [under-booking-trip]
    :plan [[:set [:kind] [:payload :kind]]
           [:set [:selection] [:payload :selection]]
           [:set [:cycle] [:payload :cycle]]
           [:call "reservation.book" {:kind [:payload :kind] :selection [:payload :selection] :attempt [:payload :cycle]}]
           [:await "ReservationConfirmed"]]}
   {:node-type "Reservation" :from "RES_BOOKING" :on "ReservationConfirmed" :to "RES_BOOKED"
    :handler 'reservation/booked
    :plan [[:emit "ReservationBooked"]]}
   {:node-type "Reservation" :from "RES_BOOKING" :on "ReservationRejected" :to "RES_FAILED"
    :handler 'reservation/failed
    :plan [[:set [:error] [:or [:payload :reason] "booking rejected"]]
           [:emit "ReservationFailed"]]}
   ;; Cancel Flight / Cancel Hotel: the compensation, when the trip reverses.
   {:node-type "Reservation" :from "RES_BOOKED" :on "CancelCommanded" :to "RES_CANCELLING"
    :handler 'reservation/cancel
    :requires [under-reversing-trip]
    :plan [[:call "reservation.cancel" {:kind [:data :kind] :attempt [:data :cycle]}]
           [:await "ReservationCancelConfirmed"]]}
   {:node-type "Reservation" :from "RES_CANCELLING" :on "ReservationCancelConfirmed" :to "RES_CANCELLED"
    :handler 'reservation/released
    :plan [[:emit "ReservationReleased"]]}
   ;; A rejected booking has nothing to reverse, but the reversal takes it along, so
   ;; that every reservation starts the next cycle from a state the decisions do not
   ;; count: a failure left from the last cycle must not pass for this one's.
   {:node-type "Reservation" :from "RES_FAILED" :on "CancelCommanded" :to "RES_CANCELLED"
    :handler 'reservation/released
    :requires [under-reversing-trip]
    :plan [[:emit "ReservationReleased"]]}
   ;; A reversal that fails is recorded for reconciliation out of band; the trip goes on.
   {:node-type "Reservation" :from "RES_CANCELLING" :on "ReservationCancelFailed" :to "RES_CANCELLED"
    :handler 'reservation/released-unreversed
    :plan [[:set [:reversal-pending] true]
           [:emit "ReservationReleased"]]}
   ;; The trip's end: a booked reservation is confirmed, any other closed.
   {:node-type "Reservation" :from "RES_BOOKED" :on "ConfirmCommanded" :to "RES_CONFIRMED"
    :handler 'reservation/settle
    :requires [under-confirming-trip]
    :plan [[:emit "ReservationSettled"]]}
   {:node-type "Reservation" :from "RES_NEW" :on "CloseCommanded" :to "RES_CLOSED"
    :handler 'reservation/settle
    :requires [under-closing-trip]
    :plan [[:emit "ReservationSettled"]]}
   {:node-type "Reservation" :from "RES_FAILED" :on "CloseCommanded" :to "RES_CLOSED"
    :handler 'reservation/settle
    :requires [under-closing-trip]
    :plan [[:emit "ReservationSettled"]]}
   {:node-type "Reservation" :from "RES_CANCELLED" :on "CloseCommanded" :to "RES_CLOSED"
    :handler 'reservation/settle
    :requires [under-closing-trip]
    :plan [[:emit "ReservationSettled"]]}])

(def routing
  (for [event ["ReservationBooked" "ReservationFailed" "ReservationReleased" "ReservationSettled"]]
    {:event event :from-node-type "Reservation" :to-node-type "Trip"
     :scope :upward :on-unsatisfied :ignore}))

(def chain-views
  {"TripChain"
   {:node-type "Trip"
    :steps [{:name "Offer"
             :active-states ["TRIP_NEW" "TRIP_SEARCHING" "TRIP_OFFERED"]
             :complete-states ["TRIP_AWAITING_CARD" "TRIP_BOOKING" "TRIP_BOOKING_FAILED" "TRIP_CHARGING"
                               "TRIP_CONFIRMING" "TRIP_CONFIRMED" "TRIP_REVERSING" "TRIP_RECORDING_FAILURE"
                               "TRIP_RETRY_WAIT" "TRIP_CLOSING_FAILED" "TRIP_NOTIFYING_FAILURE" "TRIP_FAILED"]
             :failed-states ["TRIP_CANCELLING" "TRIP_CLOSING_CANCELLED" "TRIP_NOTIFYING_CANCELLATION" "TRIP_CANCELLED"]}
            {:name "Book"
             :active-states ["TRIP_AWAITING_CARD" "TRIP_BOOKING" "TRIP_BOOKING_FAILED" "TRIP_REVERSING"
                             "TRIP_RECORDING_FAILURE" "TRIP_RETRY_WAIT"]
             :complete-states ["TRIP_CHARGING" "TRIP_CONFIRMING" "TRIP_CONFIRMED"]
             :failed-states ["TRIP_CLOSING_FAILED" "TRIP_NOTIFYING_FAILURE" "TRIP_FAILED"]
             :progress-counters [:booked :failed :cancelled]}
            {:name "Charge"
             :active-states ["TRIP_CHARGING"]
             :complete-states ["TRIP_CONFIRMING" "TRIP_CONFIRMED"]
             :failed-states ["TRIP_CLOSING_FAILED" "TRIP_NOTIFYING_FAILURE" "TRIP_FAILED"]}]}})

(def invariants
  "What must hold of every trip beyond what the framework checks. All provable."
  {;; Charging twice is two requests reaching the provider: at most one charge the
   ;; provider may have acted on, as the order example states it.
   :one-charge-per-trip
   [:forall :t [:nodes "Trip"]
    [:at-most-one :e [:effects "card.charge" :owner [:var :t]]
     [:not [:or [:= [:status-of [:var :e]] "CANCELLED"] [:= [:status-of [:var :e]] "FAILED"]]]]]

   ;; A trip is confirmed on exactly one completed charge.
   :confirmed-means-charged
   [:forall :t [:nodes "Trip" :in ["TRIP_CONFIRMING" "TRIP_CONFIRMED"]]
    [:exactly-one :e [:effects "card.charge" :owner [:var :t] :status ["COMPLETED"]]]]

   ;; A confirmed trip's every reservation was booked.
   :confirmed-trip-holds-its-bookings
   [:forall :t [:nodes "Trip" :in ["TRIP_CONFIRMED"]]
    [:forall :r [:nodes "Reservation" :children-of [:var :t]]
     [:exists :b [:effects "reservation.book" :owner [:var :r] :status ["COMPLETED"]]]]]

   ;; Only a booked reservation is reversed with the provider.
   :reversal-only-after-booking
   [:forall :r [:nodes "Reservation"]
    [:implies [:exists :c [:effects "reservation.cancel" :owner [:var :r]]]
     [:exists :b [:effects "reservation.book" :owner [:var :r] :status ["COMPLETED"]]]]]

   ;; A failed trip holds no booking: every booking made under it was reversed.
   :failed-trip-reversed-its-bookings
   [:forall :t [:nodes "Trip" :in ["TRIP_FAILED"]]
    [:forall :r [:nodes "Reservation" :children-of [:var :t]]
     [:implies [:exists :b [:effects "reservation.book" :owner [:var :r] :status ["COMPLETED"]]]
      [:exists :c [:effects "reservation.cancel" :owner [:var :r]]]]]]})

(def definition
  {:profile {:name profile-name :version profile-version}
   :events events
   :effects effects
   :node-types node-types
   :payloads payloads
   :transitions transitions
   :routing (vec routing)
   :chain-views chain-views
   :invariants invariants})

(def handlers
  "Every handler is a plan on its transition; nothing in code."
  {})

(def app
  "`voucha dev voucha.example.travel/app`: every effect faked, so the agency's
  systems, the client and the booking providers need not exist."
  {:definition definition
   :handlers handlers
   :effect-adapters {}
   :store-adapters {}
   ;; what `voucha verify` checks by default: one trip with a flight and a hotel
   :verify {:root-type "Trip" :root-id "T1" :first-event "ReservationRequested"
            :payload {:client "C1" :reservations [{:reservation-id "T1-F" :kind "flight"}
                                                  {:reservation-id "T1-H" :kind "hotel"}]}
            :children {"Reservation" 2} :max-repeat 2}})
