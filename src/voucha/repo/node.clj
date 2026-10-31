(ns voucha.repo.node
  "Persistence for state_node.

  Nodes are the physically independent units of one logical hierarchical
  machine (design 13). Each is guarded by optimistic locking on lock_version
  (design 54), which lets siblings advance in parallel while making a parent the
  serialised single writer of its own aggregate counters (decision D6)."
  (:refer-clojure :exclude [ancestors])
  (:require [next.jdbc :as jdbc]
            [voucha.codec :as codec]
            [voucha.db :as db]))

(def status-active "ACTIVE")
(def status-terminal "TERMINAL")

(defn- ->node
  [row]
  (when row
    {:tree-id (:tree_id row)
     :node-id (:node_id row)
     :parent-node-id (:parent_node_id row)
     :node-type (:node_type row)
     :local-state (:local_state row)
     :local-data (codec/<-jsonb (:local_data row))
     :lock-version (:lock_version row)
     :status (:status row)}))

(defn insert!
  [conn {:keys [tree-id node-id parent-node-id node-type local-state local-data status]
         :or {local-data {} status status-active}}]
  (db/execute-one!
   conn
   ["INSERT INTO state_node
      (tree_id, node_id, parent_node_id, node_type, local_state, local_data, status)
     VALUES (?, ?, ?, ?, ?, ?, ?)"
    tree-id node-id parent-node-id node-type local-state (codec/->jsonb local-data) status])
  node-id)

(defn fetch
  [conn tree-id node-id]
  (->node (db/execute-one!
           conn
           ["SELECT * FROM state_node WHERE tree_id = ? AND node_id = ?"
            tree-id node-id])))

(defn children
  [conn tree-id parent-node-id]
  (mapv ->node
        (db/execute! conn
                     ["SELECT * FROM state_node
                       WHERE tree_id = ? AND parent_node_id = ?
                       ORDER BY node_id" tree-id parent-node-id])))

(defn ancestors
  "Ancestors of `node-id`, nearest parent first. Used for hierarchical context
  loading (design 53)."
  [conn tree-id node-id]
  (mapv ->node
        (db/execute!
         conn
         ["WITH RECURSIVE up AS (
             SELECT n.*, 0 AS depth FROM state_node n
              WHERE n.tree_id = ? AND n.node_id = ?
             UNION ALL
             SELECT p.*, up.depth + 1 FROM state_node p
               JOIN up ON p.node_id = up.parent_node_id AND p.tree_id = up.tree_id
           )
           SELECT * FROM up WHERE depth > 0 ORDER BY depth"
          tree-id node-id])))

(def ^:private lock-clauses {:share "FOR SHARE" :update "FOR UPDATE"})

(defn lock!
  "Re-read a node row under a row lock, returning it as it stands now (design 54).

  `:share` admits concurrent readers but blocks writers of that row until this
  transaction commits. That is exactly what a hierarchical constraint needs: the
  parent state a child validated against must not move before the child commits,
  yet siblings that merely read the same parent must still advance in parallel.

  `:update` excludes readers too, and is taken by a transition that will go on to
  write the row itself."
  [conn tree-id node-id mode]
  (let [clause (or (lock-clauses mode)
                   (throw (ex-info "Unknown row lock mode"
                                   {:type ::unknown-lock-mode :mode mode})))]
    (->node (db/execute-one!
             conn
             [(str "SELECT * FROM state_node WHERE tree_id = ? AND node_id = ? " clause)
              tree-id node-id]))))

(defn child-state-counts
  "Counts of child nodes grouped by local_state. Used only for verification and
  operator views -- the hot path uses parent-held counters (decision D6)."
  [conn tree-id parent-node-id]
  (into {}
        (map (juxt :local_state :n))
        (db/execute! conn
                     ["SELECT local_state, COUNT(*) AS n FROM state_node
                       WHERE tree_id = ? AND parent_node_id = ?
                       GROUP BY local_state" tree-id parent-node-id])))

(defn update-state!
  "Compare-and-swap on lock_version. Returns the new lock_version.

  Throws ::stale-node if the node was concurrently modified; the caller must
  abort the kernel transaction rather than retry blindly, because the
  hierarchical context it validated against may also have changed."
  [conn {:keys [tree-id node-id local-state local-data status lock-version]}]
  (let [res (db/execute-one!
             conn
             ["UPDATE state_node
                 SET local_state = ?, local_data = ?, status = ?,
                     lock_version = lock_version + 1, updated_at = now()
               WHERE tree_id = ? AND node_id = ? AND lock_version = ?"
              local-state (codec/->jsonb local-data) (or status status-active)
              tree-id node-id lock-version])
        n (:next.jdbc/update-count res)]
    (when (zero? n)
      (throw (ex-info "Stale node: concurrent modification"
                      {:type ::stale-node
                       :tree-id tree-id
                       :node-id node-id
                       :expected-lock-version lock-version})))
    (inc lock-version)))
