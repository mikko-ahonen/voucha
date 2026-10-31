(ns voucha.profile
  "Process profiles: reusable packages of hierarchical execution semantics
  (design 10).

  A profile is data. The compiler snapshots it into the domain artifact
  (decision D12), so a compiled artifact is self-contained and the runtime never
  resolves profiles. The kernel does not interpret profiles as special cases --
  once compiled, a profile's rules are ordinary artifact rules."
  (:require [clojure.set :as set]))

(defprotocol ProcessProfile
  (definition [this] "Return the profile's definition fragment as data."))

(defn- merge-node-type
  "Maps (counters, child-states-allowed) merge; vectors (states, terminal states,
  containment, effect permissions) extend, so a domain adds a state rather than
  restating the profile's list; anything else is overridden."
  [base overlay]
  (merge-with (fn [a b]
                (cond (and (map? a) (map? b)) (merge a b)
                      (and (vector? a) (vector? b)) (vec (distinct (concat a b)))
                      :else b))
              base overlay))

(defn derive-definition
  "Layer a domain definition on top of a profile definition (design 7: profiles
  are layer 3, domain implementation is layer 4).

  Node types merge key-by-key so a domain may add states, counters, transitions
  and effect permissions without restating the profile's lifecycle."
  [profile-def domain-def]
  (-> profile-def
      (merge (dissoc domain-def :node-types :transitions :effects :events :routing :chain-views))
      (assoc :node-types (merge-with merge-node-type
                                     (:node-types profile-def)
                                     (:node-types domain-def)))
      (assoc :events (set/union (set (:events profile-def)) (set (:events domain-def))))
      (assoc :effects (merge (:effects profile-def) (:effects domain-def)))
      (assoc :transitions (into (vec (:transitions profile-def)) (:transitions domain-def)))
      (assoc :routing (into (vec (:routing profile-def)) (:routing domain-def)))
      (assoc :chain-views (merge (:chain-views profile-def) (:chain-views domain-def)))))
