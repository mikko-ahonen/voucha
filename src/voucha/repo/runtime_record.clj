(ns voucha.repo.runtime-record
  "Generic extension table for profile- and domain-specific records (design 50).

  Deliberately generic: `record_type` carries what a narrower design would have
  made a table name (batch_validation_result, batch_enrichment_result), which
  would have leaked a process profile into the kernel schema."
  (:require [voucha.codec :as codec]
            [voucha.db :as db]))

(defn put!
  "Insert a runtime record. Versioned and content-hashed, so re-writing the same
  record is idempotent."
  [conn {:keys [tree-id node-id record-type record-key record-version payload]}]
  (db/execute-one! conn ["SELECT voucha.record_put(?, ?, ?, ?, ?, ?, ?)"
                         tree-id node-id record-type record-key record-version
                         (codec/->jsonb payload) (codec/content-hash payload)])
  nil)

(defn- ->record
  [row]
  (when row
    {:tree-id (:tree_id row)
     :node-id (:node_id row)
     :record-type (:record_type row)
     :record-key (:record_key row)
     :record-version (:record_version row)
     :payload (codec/<-jsonb (:payload row))
     :payload-hash (:payload_hash row)}))

(defn fetch
  [conn {:keys [tree-id node-id record-type record-key record-version]}]
  (->record (db/execute-one! conn ["SELECT * FROM voucha.record_get(?, ?, ?, ?, ?)"
                                   tree-id node-id record-type record-key record-version])))

(defn by-type
  [conn tree-id node-id record-type]
  (mapv ->record (db/execute! conn ["SELECT * FROM voucha.record_get(?, ?, ?)" tree-id node-id record-type])))
