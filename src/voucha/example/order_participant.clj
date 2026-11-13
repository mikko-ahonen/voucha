(ns voucha.example.order-participant
  "The order example's warehouse as a SQL participant (docs/design/sql-interface.md).

  Instead of an adapter the worker calls, the warehouse claims its effects
  through `voucha.claim_effects`, reserves or releases stock in its own tables,
  and reports with `voucha.report_outcome`, all in one transaction: the
  reservation rows and the report commit together or not at all. Any process
  with a database connection could be this participant; this one is Clojure
  because the tests are.

  `definition` is the order definition with the two inventory effects handed
  to the participant; the payment provider and the carrier stay adapters."
  (:require [voucha.codec :as codec]
            [voucha.db :as db]
            [voucha.example.order :as order]
            [voucha.example.order-adapters :as adapters]))

(def definition
  (-> order/definition
      (assoc-in [:effects "inventory.reserve" :participant] :sql)
      (assoc-in [:effects "inventory.release" :participant] :sql)))

(def participant "warehouse")

(def ^:private work
  {"inventory.reserve" adapters/reserve-in!
   "inventory.release" adapters/release-in!})

(defn- take-one!
  "Claim one effect of a name, do its work and report, in one transaction.
  Returns true when there was one."
  [ds effect-name]
  (db/in-tx [tx ds]
    (when-let [claimed (db/execute-one! tx ["SELECT * FROM voucha.claim_effects(?, ?, 1)" effect-name participant])]
      (let [{:keys [outcome payload]} ((work effect-name) tx (codec/<-jsonb (:request_payload claimed)))]
        (db/execute-one! tx ["SELECT voucha.report_outcome(?, ?, ?, ?)"
                             (:effect_instance_id claimed) participant (name outcome) (codec/->jsonb (or payload {}))])
        true))))

(defn run-once!
  "Carry out every effect waiting for the warehouse. Returns how many."
  [ds]
  (reduce + (for [effect-name (keys work)]
              (loop [n 0]
                (if (take-one! ds effect-name) (recur (inc n)) n)))))
