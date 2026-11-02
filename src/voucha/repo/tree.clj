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
  (db/execute-one! conn ["SELECT voucha.tree_insert(?, ?, ?, ?, ?, ?)"
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
  (->tree (db/execute-one! conn ["SELECT * FROM voucha.tree_get(?)" tree-id])))

(defn set-status!
  [conn tree-id status]
  (db/execute-one! conn ["SELECT voucha.tree_set_status(?, ?)" tree-id status]))
