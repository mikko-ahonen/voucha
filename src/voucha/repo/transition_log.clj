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
   ["SELECT voucha.transition_append(?)"
    (codec/->jsonb {:transition_id transition-id :tree_id tree-id :node_id node-id :artifact_id artifact-id
                    :triggering_event_id triggering-event-id :from_state from-state :to_state to-state
                    :handler_name handler-name
                    :hierarchical_context_hash (when context-read-set (codec/content-hash context-read-set))
                    :context_read_set context-read-set :operation_inputs_hash operation-inputs-hash
                    :operation_outputs operation-outputs :state_patch (patch/encode (or state-patch []))
                    :emitted_events emitted-events :created_effects created-effects
                    :created_store_effects created-store-effects :debug_provenance debug-provenance})])
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
  (mapv ->entry (db/execute! conn ["SELECT * FROM voucha.transition_history(?, ?)" tree-id node-id])))

(defn tree-history
  [conn tree-id]
  (mapv ->entry (db/execute! conn ["SELECT * FROM voucha.transition_history(?)" tree-id])))
