(ns voucha.repo.transition-log
  "Append-only checkpoint log. Every committed node transition is a checkpoint
  (design 20); no runtime-owned state change may occur without one (design 65).

  The row carries the hierarchical context hash plus the read-set the
  transition's constraints actually examined (decision D4), which is what makes
  investigation replay -- 'why was this valid?' -- possible without snapshotting
  whole subtrees."
  (:require [voucha.codec :as codec]
            [voucha.db :as db]
            [voucha.patch :as patch]))

(defn append!
  [conn {:keys [transition-id tree-id node-id artifact-id triggering-event-id
                from-state to-state handler-name context-read-set
                operation-inputs-hash operation-outputs state-patch
                emitted-events created-effects created-store-effects debug-provenance]
         :or {emitted-events [] created-effects [] created-store-effects []}}]
  (db/execute-one!
   conn
   ["INSERT INTO node_transition_log
      (transition_id, tree_id, node_id, artifact_id, triggering_event_id,
       from_state, to_state, handler_name, hierarchical_context_hash,
       context_read_set, operation_inputs_hash, operation_outputs, state_patch,
       emitted_events, created_effects, created_store_effects, debug_provenance)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    transition-id tree-id node-id artifact-id triggering-event-id
    from-state to-state handler-name
    (when context-read-set (codec/content-hash context-read-set))
    (codec/->jsonb context-read-set)
    operation-inputs-hash
    (when operation-outputs (codec/->jsonb operation-outputs))
    (patch/encode (or state-patch []))
    (codec/->jsonb emitted-events)
    (codec/->jsonb created-effects)
    (codec/->jsonb created-store-effects)
    (some-> debug-provenance codec/->jsonb)])
  transition-id)

(defn- ->entry
  [row]
  (when row
    {:transition-id (:transition_id row)
     :tree-id (:tree_id row)
     :node-id (:node_id row)
     :artifact-id (:artifact_id row)
     :triggering-event-id (:triggering_event_id row)
     :from-state (:from_state row)
     :to-state (:to_state row)
     :handler-name (:handler_name row)
     :hierarchical-context-hash (:hierarchical_context_hash row)
     :context-read-set (codec/<-jsonb (:context_read_set row))
     :operation-inputs-hash (:operation_inputs_hash row)
     :operation-outputs (codec/<-jsonb (:operation_outputs row))
     :state-patch (patch/decode (:state_patch row))
     :emitted-events (codec/<-jsonb (:emitted_events row))
     :created-effects (codec/<-jsonb (:created_effects row))
     :created-store-effects (codec/<-jsonb (:created_store_effects row))
     :debug-provenance (codec/<-jsonb (:debug_provenance row))
     :created-at (:created_at row)}))

(defn history
  "Transition history for one node, oldest first."
  [conn tree-id node-id]
  (mapv ->entry
        (db/execute! conn
                     ["SELECT * FROM node_transition_log
                       WHERE tree_id = ? AND node_id = ?
                       ORDER BY created_at, transition_id" tree-id node-id])))

(defn tree-history
  [conn tree-id]
  (mapv ->entry
        (db/execute! conn
                     ["SELECT * FROM node_transition_log
                       WHERE tree_id = ?
                       ORDER BY created_at, transition_id" tree-id])))
