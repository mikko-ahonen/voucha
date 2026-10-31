(ns voucha.patch
  "State patches as s-expressions (decision D3).

  A patch is a vector of operations, each a list whose head is one of a small
  closed vocabulary. The vocabulary is closed on purpose: patches must be
  serialisable, hashable, and replayable, so operations carry literal arguments
  only -- never functions.

      [(assoc-in [:mainframe-request-id] \"REQ-1\")
       (inc-in   [:completed-count] 1)
       (conj-in  [:errors] {:code \"E17\"})
       (dissoc-in [:scratch])]

  `apply-patch` is the single writer of node local_data. It is pure: the kernel
  applies the same patch during replay to reconstruct state."
  (:require [voucha.codec :as codec]))

(def ^:private ops #{'assoc-in 'dissoc-in 'inc-in 'conj-in})

(defn- dissoc-in*
  [m path]
  (let [ks (butlast path)
        k (last path)]
    (if (seq ks)
      (let [parent (get-in m ks)]
        (if (map? parent)
          (assoc-in m (vec ks) (dissoc parent k))
          m))
      (dissoc m k))))

(defn valid-op?
  [op]
  (and (seq? op)
       (contains? ops (first op))
       (vector? (second op))
       (seq (second op))
       (every? keyword? (second op))
       (case (first op)
         dissoc-in (= 2 (count op))
         inc-in (and (= 3 (count op)) (number? (nth op 2)))
         (= 3 (count op)))))

(defn valid-patch?
  [patch]
  (and (vector? patch) (every? valid-op? patch)))

(defn validate!
  [patch]
  (when-not (valid-patch? patch)
    (let [bad (when (vector? patch) (remove valid-op? patch))]
      (throw (ex-info "Invalid state patch"
                      {:type ::invalid-patch
                       :patch patch
                       :invalid-ops (vec bad)
                       :allowed-ops ops}))))
  patch)

(defn apply-op
  [data op]
  (let [[head path arg] op]
    (case head
      assoc-in (assoc-in data path arg)
      dissoc-in (dissoc-in* data path)
      inc-in (update-in data path (fnil + 0) arg)
      conj-in (update-in data path (fnil conj []) arg))))

(defn apply-patch
  "Apply a validated patch to `data`, returning new data. Pure."
  [data patch]
  (validate! patch)
  (reduce apply-op (or data {}) patch))

(defn encode
  "Canonical EDN text for durable storage in node_transition_log.state_patch."
  ^String [patch]
  (codec/canonical-edn (validate! patch)))

(defn decode
  [^String s]
  (validate! (codec/read-edn s)))
