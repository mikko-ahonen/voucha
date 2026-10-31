(ns voucha.repo.tree
  "Persistence for execution_tree: one logical hierarchical machine instance,
  pinned to one artifact for its whole life (design 33, decision D10)."
  (:require [voucha.db :as db]))

(def status-active "ACTIVE")
(def status-completed "COMPLETED")
(def status-failed "FAILED")
(def status-cancelled "CANCELLED")

(defn insert!
  [conn {:keys [tree-id artifact-id profile-name profile-version root-node-id status]
         :or {status status-active}}]
  (db/execute-one!
   conn
   ["INSERT INTO execution_tree
      (tree_id, artifact_id, profile_name, profile_version, root_node_id, status)
     VALUES (?, ?, ?, ?, ?, ?)"
    tree-id artifact-id profile-name profile-version root-node-id status])
  tree-id)

(defn- ->tree
  [row]
  (when row
    {:tree-id (:tree_id row)
     :artifact-id (:artifact_id row)
     :profile-name (:profile_name row)
     :profile-version (:profile_version row)
     :root-node-id (:root_node_id row)
     :status (:status row)}))

(defn fetch
  [conn tree-id]
  (->tree (db/execute-one!
           conn
           ["SELECT tree_id, artifact_id, profile_name, profile_version, root_node_id, status
             FROM execution_tree WHERE tree_id = ?" tree-id])))

(defn set-status!
  [conn tree-id status]
  (db/execute-one!
   conn
   ["UPDATE execution_tree SET status = ?, updated_at = now() WHERE tree_id = ?"
    status tree-id]))
