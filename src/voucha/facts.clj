(ns voucha.facts
  "The only sanctioned source of non-determinism for transition handlers
  (design 58).

  A handler may not call `Instant/now` or `random-uuid`. It asks the kernel,
  which derives both from values fixed at commit time: the transaction clock is
  read once from the database, and identifiers are derived from the triggering
  event id. Replaying the same event therefore mints the same identifiers."
  (:require [voucha.codec :as codec]))

(defprotocol Facts
  (transaction-time [this] "The single clock reading for this kernel transaction.")
  (next-id [this] "A fresh identifier, deterministic in the triggering event."))

(deftype DeterministicFacts [tx-time event-id counter]
  Facts
  (transaction-time [_] tx-time)
  (next-id [_]
    (subs (codec/sha256 (str event-id ":" (swap! counter inc))) 0 32)))

(defn facts
  [tx-time event-id]
  (->DeterministicFacts tx-time event-id (atom 0)))
