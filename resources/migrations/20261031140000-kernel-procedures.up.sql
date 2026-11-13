-- The kernel's protocol-bearing writes as functions (docs/design/kernel-procedures.md).
-- Invoker rights: the worker owns the tables.

-- The receipt-order claim, locked for the calling transaction. `p_except` names
-- events this pass has already failed and leaves for the next.
CREATE FUNCTION voucha.claim_event(p_except TEXT[] DEFAULT '{}')
RETURNS TABLE (
    event_id TEXT, tree_id TEXT, target_node_id TEXT, event_type TEXT, artifact_id TEXT,
    correlation_key TEXT, causation_id TEXT, payload JSONB, status TEXT, attempt_count INT,
    last_error TEXT, operator_id TEXT, operator_reason TEXT, received_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ, actor TEXT, claims JSONB, tree_artifact_id TEXT, now TIMESTAMPTZ)
LANGUAGE plpgsql AS $$
BEGIN
    -- PL/pgSQL, not SQL: a SQL-language function that cannot be inlined (FOR
    -- UPDATE forbids it) is planned on every call; this body's plan is cached.
    RETURN QUERY
    SELECT e.event_id, e.tree_id, e.target_node_id, e.event_type, e.artifact_id,
           e.correlation_key, e.causation_id, e.payload, e.status, e.attempt_count,
           e.last_error, e.operator_id, e.operator_reason, e.received_at,
           e.processed_at, e.actor, e.claims, t.artifact_id, now()
      FROM event_log e LEFT JOIN execution_tree t ON t.tree_id = e.tree_id
     WHERE e.status = 'RECEIVED'
       AND NOT (e.event_id = ANY (COALESCE(p_except, '{}')))
       AND NOT EXISTS (SELECT 1 FROM event_log o
                        WHERE o.tree_id = e.tree_id
                          AND o.target_node_id = e.target_node_id
                          AND o.status = 'RECEIVED'
                          AND (o.received_at, o.event_id) < (e.received_at, e.event_id))
     ORDER BY e.received_at, e.event_id
     LIMIT 1
     FOR UPDATE OF e SKIP LOCKED;
END;
$$;

--;;

-- The persist step of one transition, in the kernel's order. Every parameter but
-- the first two is a JSON array of flat records, or null. Raises VC001 when the
-- node's or the parent's lock version has moved. Returns the number of effects
-- cancelled because the node went terminal.
CREATE FUNCTION voucha.commit_transition(
    p_event_id TEXT,
    p_node JSONB,
    p_children JSONB,
    p_cancel_timeouts BOOLEAN,
    p_cancel_effects BOOLEAN,
    p_effects JSONB,
    p_outbox JSONB,
    p_timeouts JSONB,
    p_events JSONB,
    p_parent JSONB,
    p_checkpoints JSONB)
RETURNS INT
LANGUAGE plpgsql AS $$
DECLARE
    v_n INT;
    v_cancelled INT := 0;
    v_tree TEXT := p_node ->> 'tree_id';
    v_node TEXT := p_node ->> 'node_id';
BEGIN
    -- 1. runtime-owned node state, under optimistic lock
    UPDATE state_node
       SET local_state = p_node ->> 'local_state', local_data = p_node -> 'local_data',
           status = p_node ->> 'status', lock_version = lock_version + 1, updated_at = now()
     WHERE tree_id = v_tree AND node_id = v_node
       AND lock_version = (p_node ->> 'lock_version')::bigint;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n = 0 THEN
        RAISE EXCEPTION 'voucha: stale node %/%: concurrent modification', v_tree, v_node USING ERRCODE = 'VC001';
    END IF;

    -- 2. children this transition decided to create
    INSERT INTO state_node (tree_id, node_id, parent_node_id, node_type, local_state, local_data, status)
    SELECT c.tree_id, c.node_id, c.parent_node_id, c.node_type, c.local_state, c.local_data, c.status
      FROM jsonb_to_recordset(COALESCE(p_children, '[]'::jsonb))
           AS c(tree_id TEXT, node_id TEXT, parent_node_id TEXT, node_type TEXT,
                local_state TEXT, local_data JSONB, status TEXT);

    -- 3. leaving a state disarms the timeouts that state armed
    IF p_cancel_timeouts THEN
        UPDATE timeout_schedule SET status = 'CANCELLED'
         WHERE tree_id = v_tree AND node_id = v_node AND status = 'PENDING';
    END IF;

    -- 3b. a terminal node owes nothing: its outstanding effects are cancelled,
    --     flagged for an operator when they may have reached the outside, and
    --     each cancellation is recorded as the effect's next attempt
    IF p_cancel_effects THEN
        WITH c AS (
            UPDATE effect_instance
               SET status = 'CANCELLED', next_attempt_at = NULL, timeout_at = NULL,
                   failure_payload = jsonb_build_object('cancelled-by', 'owner node reached a terminal state',
                                                        'prior-status', status)
                                     || CASE WHEN status IN ('DISPATCHING', 'DISPATCHED', 'ACKNOWLEDGED')
                                             THEN '{"requires-operator": true}'::jsonb ELSE '{}'::jsonb END,
                   updated_at = now()
             WHERE tree_id = v_tree AND owner_node_id = v_node
               AND status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
            RETURNING effect_instance_id, failure_payload ->> 'prior-status' AS prior)
        INSERT INTO effect_attempt (attempt_id, effect_instance_id, attempt_no, status, error_detail, finished_at)
        SELECT encode(sha256(convert_to(c.effect_instance_id || ':cancelled', 'UTF8')), 'hex'),
               c.effect_instance_id,
               COALESCE((SELECT MAX(a.attempt_no) FROM effect_attempt a WHERE a.effect_instance_id = c.effect_instance_id), 0) + 1,
               'CANCELLED', 'cancelled from ' || c.prior || ': owner node reached a terminal state', now()
          FROM c
        ON CONFLICT (effect_instance_id, attempt_no) DO NOTHING;
        GET DIAGNOSTICS v_cancelled = ROW_COUNT;
    END IF;

    -- 4. durable effect intents, idempotent under their key, and their outbox rows
    INSERT INTO effect_instance
        (effect_instance_id, tree_id, owner_node_id, effect_kind, effect_name, effect_version,
         artifact_id, status, uncertainty_policy, participant, idempotency_key, correlation_key,
         request_payload, request_payload_hash, timeout_at)
    SELECT e.effect_instance_id, e.tree_id, e.owner_node_id, e.effect_kind, e.effect_name, e.effect_version,
           e.artifact_id, 'CREATED', e.uncertainty_policy, e.participant, e.idempotency_key, e.correlation_key,
           COALESCE(e.request_payload, '{}'::jsonb), e.request_payload_hash, e.timeout_at
      FROM jsonb_to_recordset(COALESCE(p_effects, '[]'::jsonb))
           AS e(effect_instance_id TEXT, tree_id TEXT, owner_node_id TEXT, effect_kind TEXT, effect_name TEXT,
                effect_version TEXT, artifact_id TEXT, uncertainty_policy TEXT, participant TEXT,
                idempotency_key TEXT, correlation_key TEXT, request_payload JSONB, request_payload_hash TEXT,
                timeout_at TIMESTAMPTZ)
    ON CONFLICT (effect_name, effect_version, idempotency_key) DO NOTHING;

    INSERT INTO outbox (outbox_id, tree_id, node_id, type, payload, status)
    SELECT o.outbox_id, o.tree_id, o.node_id, o.type, COALESCE(o.payload, '{}'::jsonb), 'PENDING'
      FROM jsonb_to_recordset(COALESCE(p_outbox, '[]'::jsonb))
           AS o(outbox_id TEXT, tree_id TEXT, node_id TEXT, type TEXT, payload JSONB);

    -- 5. timeout policy
    INSERT INTO timeout_schedule
        (timeout_id, tree_id, node_id, effect_instance_id, timeout_kind, event_type, payload, fire_at, status)
    SELECT t.timeout_id, t.tree_id, t.node_id, t.effect_instance_id, t.timeout_kind, t.event_type,
           COALESCE(t.payload, '{}'::jsonb), t.fire_at, 'PENDING'
      FROM jsonb_to_recordset(COALESCE(p_timeouts, '[]'::jsonb))
           AS t(timeout_id TEXT, tree_id TEXT, node_id TEXT, effect_instance_id TEXT, timeout_kind TEXT,
                event_type TEXT, payload JSONB, fire_at TIMESTAMPTZ);

    -- 6. internal events
    INSERT INTO event_log
        (event_id, tree_id, target_node_id, event_type, artifact_id, correlation_key, causation_id, payload, status)
    SELECT v.event_id, v.tree_id, v.target_node_id, v.event_type, v.artifact_id, v.correlation_key,
           v.causation_id, COALESCE(v.payload, '{}'::jsonb), 'RECEIVED'
      FROM jsonb_to_recordset(COALESCE(p_events, '[]'::jsonb))
           AS v(event_id TEXT, tree_id TEXT, target_node_id TEXT, event_type TEXT, artifact_id TEXT,
                correlation_key TEXT, causation_id TEXT, payload JSONB)
    ON CONFLICT (event_id) DO NOTHING;

    -- 7. the parent's aggregate view of this child, under its own lock version
    IF p_parent IS NOT NULL THEN
        UPDATE state_node
           SET local_state = p_parent ->> 'local_state', local_data = p_parent -> 'local_data',
               status = p_parent ->> 'status', lock_version = lock_version + 1, updated_at = now()
         WHERE tree_id = p_parent ->> 'tree_id' AND node_id = p_parent ->> 'node_id'
           AND lock_version = (p_parent ->> 'lock_version')::bigint;
        GET DIAGNOSTICS v_n = ROW_COUNT;
        IF v_n = 0 THEN
            RAISE EXCEPTION 'voucha: stale node %/%: concurrent modification',
                p_parent ->> 'tree_id', p_parent ->> 'node_id' USING ERRCODE = 'VC001';
        END IF;
    END IF;

    -- 8. the checkpoints: the parent's aggregate first, then the transition's
    INSERT INTO node_transition_log
        (transition_id, tree_id, node_id, artifact_id, triggering_event_id, from_state, to_state,
         handler_name, hierarchical_context_hash, context_read_set, operation_inputs_hash,
         operation_outputs, state_patch, emitted_events, created_effects, created_store_effects,
         debug_provenance)
    SELECT k.transition_id, k.tree_id, k.node_id, k.artifact_id, k.triggering_event_id, k.from_state, k.to_state,
           k.handler_name, k.hierarchical_context_hash, k.context_read_set, k.operation_inputs_hash,
           k.operation_outputs, k.state_patch, COALESCE(k.emitted_events, '[]'::jsonb),
           COALESCE(k.created_effects, '[]'::jsonb), COALESCE(k.created_store_effects, '[]'::jsonb),
           k.debug_provenance
      FROM jsonb_to_recordset(COALESCE(p_checkpoints, '[]'::jsonb))
           AS k(transition_id TEXT, tree_id TEXT, node_id TEXT, artifact_id TEXT, triggering_event_id TEXT,
                from_state TEXT, to_state TEXT, handler_name TEXT, hierarchical_context_hash TEXT,
                context_read_set JSONB, operation_inputs_hash TEXT, operation_outputs JSONB, state_patch TEXT,
                emitted_events JSONB, created_effects JSONB, created_store_effects JSONB, debug_provenance JSONB);

    -- 9. the event that drove it
    UPDATE event_log SET status = 'PROCESSED', processed_at = now() WHERE event_id = p_event_id;

    RETURN v_cancelled;
END;
$$;
