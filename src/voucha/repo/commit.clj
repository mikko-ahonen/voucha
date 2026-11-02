(ns voucha.repo.commit
  "The persist step of a transition as one call to `voucha.commit_transition`
  (docs/design/kernel-procedures.md): the records the kernel computed, one JSON
  array per table, in the kernel's order, inside the kernel transaction."
  (:require [voucha.codec :as codec]
            [voucha.db :as db]
            [voucha.patch :as patch])
  (:import (java.sql Timestamp)
           (org.postgresql.util PSQLException)))

(defn- iso [^Timestamp ts] (some-> ts .toInstant str))

(defn- node-row
  [{:keys [tree-id node-id parent-node-id node-type local-state local-data status lock-version]}]
  (cond-> {:tree_id tree-id :node_id node-id :local_state local-state :local_data local-data
           :status status}
    node-type (assoc :node_type node-type :parent_node_id parent-node-id)
    lock-version (assoc :lock_version lock-version)))

(defn- effect-row
  [{:keys [effect-instance-id tree-id owner-node-id effect-kind effect-name effect-version
           artifact-id idempotency-key correlation-key uncertainty-policy participant
           request-payload timeout-at]
    :or {request-payload {} uncertainty-policy "manual"}}]
  {:effect_instance_id effect-instance-id :tree_id tree-id :owner_node_id owner-node-id
   :effect_kind (name effect-kind) :effect_name effect-name :effect_version effect-version
   :artifact_id artifact-id :uncertainty_policy (name uncertainty-policy) :participant participant
   :idempotency_key idempotency-key :correlation_key correlation-key
   :request_payload request-payload :request_payload_hash (codec/content-hash request-payload)
   :timeout_at (iso timeout-at)})

(defn- outbox-row
  [{:keys [outbox-id tree-id node-id type payload]}]
  {:outbox_id outbox-id :tree_id tree-id :node_id node-id :type type :payload payload})

(defn- timeout-row
  [{:keys [timeout-id tree-id node-id effect-instance-id timeout-kind event-type payload fire-at]}]
  {:timeout_id timeout-id :tree_id tree-id :node_id node-id :effect_instance_id effect-instance-id
   :timeout_kind timeout-kind :event_type event-type :payload (or payload {}) :fire_at (iso fire-at)})

(defn- event-row
  [{:keys [event-id tree-id target-node-id event-type artifact-id correlation-key causation-id payload]}]
  {:event_id event-id :tree_id tree-id :target_node_id target-node-id :event_type event-type
   :artifact_id artifact-id :correlation_key correlation-key :causation_id causation-id
   :payload (or payload {})})

(defn- checkpoint-row
  [{:keys [transition-id tree-id node-id artifact-id triggering-event-id from-state to-state
           handler-name context-read-set operation-inputs-hash operation-outputs state-patch
           emitted-events created-effects created-store-effects debug-provenance]}]
  {:transition_id transition-id :tree_id tree-id :node_id node-id :artifact_id artifact-id
   :triggering_event_id triggering-event-id :from_state from-state :to_state to-state
   :handler_name handler-name
   :hierarchical_context_hash (when context-read-set (codec/content-hash context-read-set))
   :context_read_set context-read-set :operation_inputs_hash operation-inputs-hash
   :operation_outputs operation-outputs :state_patch (patch/encode (or state-patch []))
   :emitted_events (or emitted-events []) :created_effects (or created-effects [])
   :created_store_effects (or created-store-effects []) :debug_provenance debug-provenance})

(defn- rows [f xs] (codec/->jsonb (mapv f xs)))

(defn commit!
  "Write a transition. `node` and `parent` carry the lock version they were read
  at; a moved one raises `:voucha.repo.node/stale-node`, as the single-row update
  does, and the caller aborts the kernel transaction. Returns the number of
  effects cancelled because the node went terminal."
  [conn {:keys [event-id node children cancel-timeouts? cancel-effects? effects outbox timeouts
                events parent checkpoints]}]
  (try
    (:n (db/execute-one!
         conn
         ["SELECT voucha.commit_transition(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS n"
          event-id
          (codec/->jsonb (node-row node))
          (rows node-row children)
          (boolean cancel-timeouts?)
          (boolean cancel-effects?)
          (rows effect-row effects)
          (rows outbox-row outbox)
          (rows timeout-row timeouts)
          (rows event-row events)
          (some-> parent node-row codec/->jsonb)
          (rows checkpoint-row checkpoints)]))
    (catch PSQLException e
      (if (= "VC001" (.getSQLState e))
        (throw (ex-info "Stale node: concurrent modification"
                        {:type :voucha.repo.node/stale-node
                         :tree-id (:tree-id node)
                         :node-id (:node-id node)
                         :detail (ex-message e)}
                        e))
        (throw e)))))
