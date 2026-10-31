(ns voucha.codec
  "Canonical encoding and hashing.

  Two representations coexist by decision D3:
    * canonical EDN text  -- artifacts, state patches, anything that is hashed
    * JSONB               -- local_data and payloads, which must stay queryable

  Canonical EDN sorts map keys so that logically equal values always produce
  identical text, and therefore identical hashes."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn])
  (:import (java.security MessageDigest)
           (org.postgresql.util PGobject)))

(def ^:private by-printed-form
  (fn [a b] (compare (pr-str a) (pr-str b))))

(defn- canonical
  "Recursively re-sort unordered collections so that printing is stable
  regardless of insertion order.

  Sets become sorted sets rather than vectors: canonical encoding must be
  lossless, because artifacts are stored as canonical EDN and read back."
  [x]
  (cond
    (map? x) (into (sorted-map-by by-printed-form)
                   (map (fn [[k v]] [k (canonical v)])) x)
    (set? x) (into (sorted-set-by by-printed-form) (map canonical) x)
    (vector? x) (mapv canonical x)
    (seq? x) (map canonical x)
    :else x))

(defn canonical-edn
  "Deterministic EDN text for `x`. Equal values always yield equal strings."
  ^String [x]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (canonical x))))

(defn read-edn
  [^String s]
  (edn/read-string s))

(defn sha256
  "Lowercase hex SHA-256 of a string."
  ^String [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn content-hash
  "Content address of any Clojure value, via its canonical EDN text."
  ^String [x]
  (sha256 (canonical-edn x)))

;; --- JSONB ------------------------------------------------------------------

(defn ->jsonb
  "Wrap a Clojure value as a PGobject suitable for a JSONB column."
  ^PGobject [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str x))))

(defn <-jsonb
  "Read a JSONB column value back into Clojure data with keyword keys."
  [v]
  (cond
    (nil? v) nil
    (instance? PGobject v) (some-> (.getValue ^PGobject v) (json/read-str :key-fn keyword))
    (string? v) (json/read-str v :key-fn keyword)
    :else v))
