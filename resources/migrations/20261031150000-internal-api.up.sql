-- The worker's whole access to the tables as functions (docs/design/kernel-procedures.md):
-- every read and write the kernel, the dispatcher and the runtime make. The functions are
-- SECURITY DEFINER, owned by the migrating role, so a worker role needs EXECUTE on them and
-- nothing on the tables; EXECUTE is revoked from PUBLIC, so every grant is explicit.
--
-- PL/pgSQL throughout: a SECURITY DEFINER function is never inlined, and a SQL-language
-- function that is not inlined is planned on every call. PL/pgSQL caches its plans.

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA voucha FROM PUBLIC;

--;;

ALTER DEFAULT PRIVILEGES IN SCHEMA voucha REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

--;;

-- The earlier kernel functions were invoker-rights; the worker role has no table rights.
ALTER FUNCTION voucha.commit_transition(TEXT, JSONB, JSONB, BOOLEAN, BOOLEAN, JSONB, JSONB, JSONB, JSONB, JSONB, JSONB)
    SECURITY DEFINER SET search_path = public, pg_temp;

--;;

-- An event as the kernel claims or locks it: the row, the tree's artifact and the
-- transaction's clock reading.
CREATE TYPE voucha.locked_event AS (
    event_id TEXT, tree_id TEXT, target_node_id TEXT, event_type TEXT, artifact_id TEXT,
    correlation_key TEXT, causation_id TEXT, payload JSONB, status TEXT, attempt_count INT,
    last_error TEXT, operator_id TEXT, operator_reason TEXT, received_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ, actor TEXT, claims JSONB, tree_artifact_id TEXT, now TIMESTAMPTZ);

--;;

DROP FUNCTION voucha.claim_event(TEXT[]);

--;;

CREATE FUNCTION voucha.claim_event(p_except TEXT[] DEFAULT '{}')
RETURNS SETOF voucha.locked_event
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
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

-- --- events ---------------------------------------------------------------------------

CREATE FUNCTION voucha.event_lock(p_event_id TEXT)
RETURNS SETOF voucha.locked_event
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT e.event_id, e.tree_id, e.target_node_id, e.event_type, e.artifact_id,
           e.correlation_key, e.causation_id, e.payload, e.status, e.attempt_count,
           e.last_error, e.operator_id, e.operator_reason, e.received_at,
           e.processed_at, e.actor, e.claims, t.artifact_id, now()
      FROM event_log e LEFT JOIN execution_tree t ON t.tree_id = e.tree_id
     WHERE e.event_id = p_event_id
     FOR UPDATE OF e;
END;
$$;

--;;

-- Up to p_limit received events in receipt order per node, locked, skipping what
-- another worker holds.
CREATE FUNCTION voucha.claim_events(p_limit BIGINT)
RETURNS SETOF event_log
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT e.* FROM event_log e
     WHERE e.status = 'RECEIVED'
       AND NOT EXISTS (SELECT 1 FROM event_log o
                        WHERE o.tree_id = e.tree_id
                          AND o.target_node_id = e.target_node_id
                          AND o.status = 'RECEIVED'
                          AND (o.received_at, o.event_id) < (e.received_at, e.event_id))
     ORDER BY e.received_at, e.event_id
     LIMIT p_limit
     FOR UPDATE SKIP LOCKED;
END;
$$;

--;;

CREATE FUNCTION voucha.event_get(p_event_id TEXT)
RETURNS SETOF event_log
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM event_log WHERE event_id = p_event_id;
END;
$$;

--;;

-- Idempotent on the event id. Returns the id.
CREATE FUNCTION voucha.event_insert(
    p_event_id TEXT, p_tree_id TEXT, p_target_node_id TEXT, p_event_type TEXT, p_artifact_id TEXT,
    p_correlation_key TEXT, p_causation_id TEXT, p_payload JSONB, p_status TEXT,
    p_operator_id TEXT, p_operator_reason TEXT, p_actor TEXT, p_claims JSONB)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO event_log
        (event_id, tree_id, target_node_id, event_type, artifact_id, correlation_key,
         causation_id, payload, status, operator_id, operator_reason, actor, claims)
    VALUES (p_event_id, p_tree_id, p_target_node_id, p_event_type, p_artifact_id, p_correlation_key,
            p_causation_id, COALESCE(p_payload, '{}'::jsonb), p_status, p_operator_id, p_operator_reason,
            p_actor, p_claims)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN p_event_id;
END;
$$;

--;;

-- Settle an event: processed, ignored, or rejected with its reason.
CREATE FUNCTION voucha.event_mark(p_event_id TEXT, p_status TEXT, p_error TEXT DEFAULT NULL)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE event_log
       SET status = p_status, last_error = COALESCE(p_error, last_error), processed_at = now()
     WHERE event_id = p_event_id;
END;
$$;

--;;

-- A processing failure; the event is parked once its attempts are used.
CREATE FUNCTION voucha.event_record_failure(p_event_id TEXT, p_error TEXT, p_max_attempts BIGINT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE event_log
       SET attempt_count = attempt_count + 1,
           last_error = p_error,
           status = CASE WHEN attempt_count + 1 >= p_max_attempts THEN 'PARKED' ELSE status END,
           processed_at = CASE WHEN attempt_count + 1 >= p_max_attempts THEN now() ELSE processed_at END
     WHERE event_id = p_event_id;
END;
$$;

--;;

CREATE FUNCTION voucha.event_parked(p_tree_id TEXT)
RETURNS SETOF event_log
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM event_log WHERE status = 'PARKED' AND tree_id = p_tree_id ORDER BY received_at;
END;
$$;

--;;

-- Is a kernel-raised decision request already waiting for the node, other than
-- the event being processed?
CREATE FUNCTION voucha.event_pending_auto(p_tree_id TEXT, p_node_id TEXT, p_except TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM event_log
                    WHERE tree_id = p_tree_id AND target_node_id = p_node_id AND status = 'RECEIVED'
                      AND event_id <> p_except AND (payload ->> 'auto') = 'true');
END;
$$;

--;;

-- --- trees, nodes, artifacts ---------------------------------------------------------------

CREATE FUNCTION voucha.tree_insert(
    p_tree_id TEXT, p_artifact_id TEXT, p_profile_name TEXT, p_profile_version TEXT,
    p_root_node_id TEXT, p_status TEXT)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO execution_tree (tree_id, artifact_id, profile_name, profile_version, root_node_id, status)
    VALUES (p_tree_id, p_artifact_id, p_profile_name, p_profile_version, p_root_node_id, p_status);
    RETURN p_tree_id;
END;
$$;

--;;

CREATE FUNCTION voucha.tree_get(p_tree_id TEXT)
RETURNS SETOF execution_tree
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM execution_tree WHERE tree_id = p_tree_id;
END;
$$;

--;;

CREATE FUNCTION voucha.tree_set_status(p_tree_id TEXT, p_status TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE execution_tree SET status = p_status, updated_at = now() WHERE tree_id = p_tree_id;
END;
$$;

--;;

CREATE FUNCTION voucha.node_insert(
    p_tree_id TEXT, p_node_id TEXT, p_parent_node_id TEXT, p_node_type TEXT,
    p_local_state TEXT, p_local_data JSONB, p_status TEXT)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO state_node (tree_id, node_id, parent_node_id, node_type, local_state, local_data, status)
    VALUES (p_tree_id, p_node_id, p_parent_node_id, p_node_type, p_local_state,
            COALESCE(p_local_data, '{}'::jsonb), p_status);
    RETURN p_node_id;
END;
$$;

--;;

CREATE FUNCTION voucha.node_get(p_tree_id TEXT, p_node_id TEXT)
RETURNS SETOF state_node
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM state_node WHERE tree_id = p_tree_id AND node_id = p_node_id;
END;
$$;

--;;

-- The node under a row lock: 'share' admits readers and blocks writers until
-- this transaction ends, 'update' excludes readers too (design 54).
CREATE FUNCTION voucha.node_lock(p_tree_id TEXT, p_node_id TEXT, p_mode TEXT)
RETURNS SETOF state_node
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF p_mode = 'share' THEN
        RETURN QUERY SELECT * FROM state_node WHERE tree_id = p_tree_id AND node_id = p_node_id FOR SHARE;
    ELSIF p_mode = 'update' THEN
        RETURN QUERY SELECT * FROM state_node WHERE tree_id = p_tree_id AND node_id = p_node_id FOR UPDATE;
    ELSE
        RAISE EXCEPTION 'voucha: unknown row lock mode %', p_mode USING ERRCODE = 'invalid_parameter_value';
    END IF;
END;
$$;

--;;

CREATE FUNCTION voucha.node_children(p_tree_id TEXT, p_parent_node_id TEXT)
RETURNS SETOF state_node
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM state_node WHERE tree_id = p_tree_id AND parent_node_id = p_parent_node_id ORDER BY node_id;
END;
$$;

--;;

-- Ancestors, nearest parent first.
CREATE FUNCTION voucha.node_ancestors(p_tree_id TEXT, p_node_id TEXT)
RETURNS SETOF state_node
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    WITH RECURSIVE up AS (
        SELECT n.*, 0 AS depth FROM state_node n WHERE n.tree_id = p_tree_id AND n.node_id = p_node_id
        UNION ALL
        SELECT p.*, up.depth + 1 FROM state_node p
          JOIN up ON p.node_id = up.parent_node_id AND p.tree_id = up.tree_id)
    SELECT up.tree_id, up.node_id, up.parent_node_id, up.node_type, up.local_state, up.local_data,
           up.lock_version, up.status, up.created_at, up.updated_at
      FROM up WHERE up.depth > 0 ORDER BY up.depth;
END;
$$;

--;;

CREATE FUNCTION voucha.node_child_state_counts(p_tree_id TEXT, p_parent_node_id TEXT)
RETURNS TABLE (local_state TEXT, n BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT s.local_state, COUNT(*) FROM state_node s
     WHERE s.tree_id = p_tree_id AND s.parent_node_id = p_parent_node_id
     GROUP BY s.local_state;
END;
$$;

--;;

-- Compare-and-swap on the lock version. Returns the new version, or null when
-- the row had moved: the caller decides whether that aborts its transaction, so
-- the transaction stays usable, unlike `commit_transition`, which raises.
CREATE FUNCTION voucha.node_update(
    p_tree_id TEXT, p_node_id TEXT, p_local_state TEXT, p_local_data JSONB, p_status TEXT, p_lock_version BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_version BIGINT;
BEGIN
    UPDATE state_node
       SET local_state = p_local_state, local_data = p_local_data, status = p_status,
           lock_version = lock_version + 1, updated_at = now()
     WHERE tree_id = p_tree_id AND node_id = p_node_id AND lock_version = p_lock_version
    RETURNING lock_version INTO v_version;
    RETURN v_version;
END;
$$;

--;;

-- Idempotent on the content-addressed id; an artifact stored before the catalog
-- column existed receives its catalog.
CREATE FUNCTION voucha.artifact_put(
    p_artifact_id TEXT, p_profile_name TEXT, p_profile_version TEXT, p_specs TEXT, p_catalog JSONB)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO execution_artifact (artifact_id, profile_name, profile_version, specs, catalog)
    VALUES (p_artifact_id, p_profile_name, p_profile_version, p_specs, p_catalog)
    ON CONFLICT (artifact_id) DO UPDATE SET catalog = EXCLUDED.catalog
    WHERE execution_artifact.catalog IS NULL;
    RETURN p_artifact_id;
END;
$$;

--;;

CREATE FUNCTION voucha.artifact_get(p_artifact_id TEXT)
RETURNS SETOF execution_artifact
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM execution_artifact WHERE artifact_id = p_artifact_id;
END;
$$;

--;;

CREATE FUNCTION voucha.artifact_list()
RETURNS SETOF execution_artifact
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM execution_artifact ORDER BY created_at;
END;
$$;

--;;

-- --- transitions and records ------------------------------------------------------------------

-- One checkpoint row, in the record shape `commit_transition` takes.
CREATE FUNCTION voucha.transition_append(p_row JSONB)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
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
      FROM jsonb_to_record(p_row)
           AS k(transition_id TEXT, tree_id TEXT, node_id TEXT, artifact_id TEXT, triggering_event_id TEXT,
                from_state TEXT, to_state TEXT, handler_name TEXT, hierarchical_context_hash TEXT,
                context_read_set JSONB, operation_inputs_hash TEXT, operation_outputs JSONB, state_patch TEXT,
                emitted_events JSONB, created_effects JSONB, created_store_effects JSONB, debug_provenance JSONB);
END;
$$;

--;;

CREATE FUNCTION voucha.transition_history(p_tree_id TEXT, p_node_id TEXT DEFAULT NULL)
RETURNS SETOF node_transition_log
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM node_transition_log
     WHERE tree_id = p_tree_id AND (p_node_id IS NULL OR node_id = p_node_id)
     ORDER BY created_at, transition_id;
END;
$$;

--;;

CREATE FUNCTION voucha.record_put(
    p_tree_id TEXT, p_node_id TEXT, p_record_type TEXT, p_record_key TEXT, p_record_version TEXT,
    p_payload JSONB, p_payload_hash TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO runtime_record (tree_id, node_id, record_type, record_key, record_version, payload, payload_hash)
    VALUES (p_tree_id, p_node_id, p_record_type, p_record_key, p_record_version, p_payload, p_payload_hash)
    ON CONFLICT (tree_id, node_id, record_type, record_key, record_version) DO NOTHING;
END;
$$;

--;;

CREATE FUNCTION voucha.record_get(
    p_tree_id TEXT, p_node_id TEXT, p_record_type TEXT, p_record_key TEXT DEFAULT NULL, p_record_version TEXT DEFAULT NULL)
RETURNS SETOF runtime_record
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM runtime_record
     WHERE tree_id = p_tree_id AND node_id = p_node_id AND record_type = p_record_type
       AND (p_record_key IS NULL OR record_key = p_record_key)
       AND (p_record_version IS NULL OR record_version = p_record_version)
     ORDER BY record_key, record_version;
END;
$$;

--;;

-- --- effects -------------------------------------------------------------------------------------

-- A durable effect intent. Idempotent under (name, version, key): returns the
-- existing instance's id when the intent already exists.
CREATE FUNCTION voucha.effect_insert(
    p_effect_instance_id TEXT, p_tree_id TEXT, p_owner_node_id TEXT, p_effect_kind TEXT, p_effect_name TEXT,
    p_effect_version TEXT, p_artifact_id TEXT, p_uncertainty_policy TEXT, p_participant TEXT,
    p_idempotency_key TEXT, p_correlation_key TEXT, p_request_payload JSONB, p_request_payload_hash TEXT,
    p_timeout_at TIMESTAMPTZ)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_id TEXT;
BEGIN
    INSERT INTO effect_instance
        (effect_instance_id, tree_id, owner_node_id, effect_kind, effect_name, effect_version,
         artifact_id, status, uncertainty_policy, participant, idempotency_key, correlation_key,
         request_payload, request_payload_hash, timeout_at)
    VALUES (p_effect_instance_id, p_tree_id, p_owner_node_id, p_effect_kind, p_effect_name, p_effect_version,
            p_artifact_id, 'CREATED', p_uncertainty_policy, p_participant, p_idempotency_key, p_correlation_key,
            COALESCE(p_request_payload, '{}'::jsonb), p_request_payload_hash, p_timeout_at)
    ON CONFLICT (effect_name, effect_version, idempotency_key) DO NOTHING
    RETURNING effect_instance_id INTO v_id;
    IF v_id IS NULL THEN
        SELECT effect_instance_id INTO v_id FROM effect_instance
         WHERE effect_name = p_effect_name AND effect_version = p_effect_version AND idempotency_key = p_idempotency_key;
    END IF;
    RETURN v_id;
END;
$$;

--;;

CREATE FUNCTION voucha.effect_get(p_effect_instance_id TEXT)
RETURNS SETOF effect_instance
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM effect_instance WHERE effect_instance_id = p_effect_instance_id;
END;
$$;

--;;

CREATE FUNCTION voucha.effect_by_external_request_id(p_external_request_id TEXT)
RETURNS SETOF effect_instance
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM effect_instance WHERE external_request_id = p_external_request_id;
END;
$$;

--;;

CREATE FUNCTION voucha.effect_by_correlation_key(p_tree_id TEXT, p_correlation_key TEXT)
RETURNS SETOF effect_instance
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM effect_instance
     WHERE tree_id = p_tree_id AND correlation_key = p_correlation_key ORDER BY created_at;
END;
$$;

--;;

CREATE FUNCTION voucha.effect_by_owner(p_tree_id TEXT, p_owner_node_id TEXT)
RETURNS SETOF effect_instance
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM effect_instance
     WHERE tree_id = p_tree_id AND owner_node_id = p_owner_node_id ORDER BY created_at;
END;
$$;

--;;

-- Effects a node, or with p_subtree its whole subtree, still owes.
CREATE FUNCTION voucha.effect_outstanding_count(p_tree_id TEXT, p_node_id TEXT, p_subtree BOOLEAN DEFAULT FALSE)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_n BIGINT;
BEGIN
    IF p_subtree THEN
        WITH RECURSIVE down AS (
            SELECT node_id FROM state_node WHERE tree_id = p_tree_id AND node_id = p_node_id
            UNION ALL
            SELECT c.node_id FROM state_node c JOIN down ON c.parent_node_id = down.node_id
             WHERE c.tree_id = p_tree_id)
        SELECT COUNT(*) INTO v_n FROM effect_instance e JOIN down ON e.owner_node_id = down.node_id
         WHERE e.tree_id = p_tree_id AND e.status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED');
    ELSE
        SELECT COUNT(*) INTO v_n FROM effect_instance
         WHERE tree_id = p_tree_id AND owner_node_id = p_node_id
           AND status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED');
    END IF;
    RETURN v_n;
END;
$$;

--;;

-- The dispatcher's claims, by mode, each skipping what another dispatcher holds:
--   dispatchable  CREATED, or DISPATCH_FAILED past its backoff, moved to DISPATCHING;
--                 never an effect with a participant, which waits for claim_effects
--   reconcilable  DISPATCHED under the query policy, past its backoff; read only
--   stranded      DISPATCHING past the lease (p_at is the cutoff), lease renewed
--   timed_out     DISPATCHED or ACKNOWLEDGED past timeout_at, moved to TIMED_OUT
CREATE FUNCTION voucha.claim_effects_for_dispatch(p_mode TEXT, p_at TIMESTAMPTZ, p_limit BIGINT)
RETURNS SETOF effect_instance
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF p_mode = 'dispatchable' THEN
        RETURN QUERY
        UPDATE effect_instance e SET status = 'DISPATCHING', updated_at = now()
         WHERE e.effect_instance_id IN (
               SELECT c.effect_instance_id FROM effect_instance c
                WHERE c.participant IS NULL
                  AND (c.status = 'CREATED'
                       OR (c.status = 'DISPATCH_FAILED' AND c.next_attempt_at IS NOT NULL AND c.next_attempt_at <= p_at))
                ORDER BY c.created_at LIMIT p_limit FOR UPDATE SKIP LOCKED)
        RETURNING e.*;
    ELSIF p_mode = 'reconcilable' THEN
        RETURN QUERY
        SELECT * FROM effect_instance c
         WHERE c.status = 'DISPATCHED' AND c.uncertainty_policy = 'query'
           AND c.next_attempt_at IS NOT NULL AND c.next_attempt_at <= p_at
         ORDER BY c.next_attempt_at LIMIT p_limit FOR UPDATE SKIP LOCKED;
    ELSIF p_mode = 'stranded' THEN
        RETURN QUERY
        UPDATE effect_instance e SET updated_at = now()
         WHERE e.effect_instance_id IN (
               SELECT c.effect_instance_id FROM effect_instance c
                WHERE c.status = 'DISPATCHING' AND c.updated_at < p_at
                ORDER BY c.updated_at LIMIT p_limit FOR UPDATE SKIP LOCKED)
        RETURNING e.*;
    ELSIF p_mode = 'timed_out' THEN
        RETURN QUERY
        UPDATE effect_instance e
           SET status = 'TIMED_OUT', next_attempt_at = NULL, updated_at = now(),
               failure_payload = jsonb_build_object('error', 'completion timeout elapsed', 'prior-status', e.status)
         WHERE e.effect_instance_id IN (
               SELECT c.effect_instance_id FROM effect_instance c
                WHERE c.status IN ('DISPATCHED', 'ACKNOWLEDGED')
                  AND c.timeout_at IS NOT NULL AND c.timeout_at <= p_at
                ORDER BY c.timeout_at LIMIT p_limit FOR UPDATE SKIP LOCKED)
        RETURNING e.*;
    ELSE
        RAISE EXCEPTION 'voucha: unknown claim mode %', p_mode USING ERRCODE = 'invalid_parameter_value';
    END IF;
END;
$$;

--;;

-- Move a live effect. Returns the rows changed: zero means a terminal effect
-- refused the write, which is how a late outcome is kept from reviving it.
CREATE FUNCTION voucha.effect_set_status(
    p_effect_instance_id TEXT, p_status TEXT, p_external_request_id TEXT,
    p_ack_payload JSONB, p_completion_payload JSONB, p_failure_payload JSONB)
RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_n INT;
BEGIN
    UPDATE effect_instance
       SET status = p_status,
           external_request_id = COALESCE(p_external_request_id, external_request_id),
           ack_payload = COALESCE(p_ack_payload, ack_payload),
           completion_payload = COALESCE(p_completion_payload, completion_payload),
           failure_payload = COALESCE(p_failure_payload, failure_payload),
           updated_at = now()
     WHERE effect_instance_id = p_effect_instance_id
       AND status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED');
    GET DIAGNOSTICS v_n = ROW_COUNT;
    RETURN v_n;
END;
$$;

--;;

-- A dispatch failure with a backoff ('retry'), an unknown outcome left to the
-- query path ('reconcile'), or an unknown outcome parked for an operator ('park').
-- Live effects only; returns the rows changed.
CREATE FUNCTION voucha.effect_defer(p_effect_instance_id TEXT, p_how TEXT, p_next_attempt_at TIMESTAMPTZ, p_error TEXT)
RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_n INT;
BEGIN
    IF p_how = 'retry' THEN
        UPDATE effect_instance
           SET status = 'DISPATCH_FAILED', retry_count = retry_count + 1, next_attempt_at = p_next_attempt_at,
               failure_payload = jsonb_build_object('error', p_error), updated_at = now()
         WHERE effect_instance_id = p_effect_instance_id
           AND status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED');
    ELSIF p_how = 'reconcile' THEN
        UPDATE effect_instance
           SET status = 'DISPATCHED', retry_count = retry_count + 1, next_attempt_at = p_next_attempt_at,
               failure_payload = jsonb_build_object('error', p_error, 'uncertain', true), updated_at = now()
         WHERE effect_instance_id = p_effect_instance_id
           AND status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED');
    ELSIF p_how = 'park' THEN
        UPDATE effect_instance
           SET status = 'DISPATCH_FAILED', next_attempt_at = NULL,
               failure_payload = jsonb_build_object('error', p_error, 'uncertain', true, 'requires-operator', true),
               updated_at = now()
         WHERE effect_instance_id = p_effect_instance_id
           AND status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED');
    ELSE
        RAISE EXCEPTION 'voucha: unknown deferral %', p_how USING ERRCODE = 'invalid_parameter_value';
    END IF;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    RETURN v_n;
END;
$$;

--;;

-- An attempt with its number given (a dispatch), or, with p_attempt_no null,
-- the effect's next number computed here (a resolution: a cancellation, a
-- timeout), because an effect can be resolved while a dispatcher is mid-call
-- and about to claim that number for itself.
CREATE FUNCTION voucha.effect_record_attempt(
    p_attempt_id TEXT, p_effect_instance_id TEXT, p_attempt_no BIGINT, p_status TEXT,
    p_external_request_id TEXT, p_response_payload JSONB, p_error_detail TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO effect_attempt
        (attempt_id, effect_instance_id, attempt_no, status, external_request_id, response_payload, error_detail, finished_at)
    SELECT p_attempt_id, p_effect_instance_id,
           COALESCE(p_attempt_no, (SELECT COALESCE(MAX(a.attempt_no), 0) + 1 FROM effect_attempt a
                                    WHERE a.effect_instance_id = p_effect_instance_id)),
           p_status, p_external_request_id, p_response_payload, p_error_detail, now()
    ON CONFLICT (effect_instance_id, attempt_no) DO NOTHING;
END;
$$;

--;;

CREATE FUNCTION voucha.effect_attempts(p_effect_instance_id TEXT)
RETURNS SETOF effect_attempt
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM effect_attempt WHERE effect_instance_id = p_effect_instance_id ORDER BY attempt_no;
END;
$$;

--;;

-- A store-effect write, once per (effect, target, record key).
CREATE FUNCTION voucha.effect_write_logged(
    p_write_id TEXT, p_effect_instance_id TEXT, p_target TEXT, p_record_key TEXT, p_payload_hash TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO effect_write_log (write_id, effect_instance_id, target, record_key, payload_hash)
    VALUES (p_write_id, p_effect_instance_id, p_target, p_record_key, p_payload_hash)
    ON CONFLICT (effect_instance_id, target, record_key) DO NOTHING;
END;
$$;

--;;

CREATE FUNCTION voucha.effect_writes(p_effect_instance_id TEXT)
RETURNS SETOF effect_write_log
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM effect_write_log WHERE effect_instance_id = p_effect_instance_id;
END;
$$;

--;;

-- Pending participant reports, oldest first, locked for this dispatcher.
CREATE FUNCTION voucha.report_claim(p_limit BIGINT)
RETURNS SETOF effect_report
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM effect_report WHERE status = 'PENDING'
     ORDER BY reported_at, report_id LIMIT p_limit FOR UPDATE SKIP LOCKED;
END;
$$;

--;;

CREATE FUNCTION voucha.report_settle(p_report_id BIGINT, p_status TEXT, p_note TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE effect_report SET status = p_status, note = p_note, applied_at = now() WHERE report_id = p_report_id;
END;
$$;

--;;

-- --- outbox and timeouts ----------------------------------------------------------------------

CREATE FUNCTION voucha.outbox_enqueue(p_outbox_id TEXT, p_tree_id TEXT, p_node_id TEXT, p_type TEXT, p_payload JSONB)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO outbox (outbox_id, tree_id, node_id, type, payload, status)
    VALUES (p_outbox_id, p_tree_id, p_node_id, p_type, COALESCE(p_payload, '{}'::jsonb), 'PENDING');
END;
$$;

--;;

CREATE FUNCTION voucha.outbox_claim(p_limit BIGINT)
RETURNS SETOF outbox
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at LIMIT p_limit FOR UPDATE SKIP LOCKED;
END;
$$;

--;;

CREATE FUNCTION voucha.outbox_mark_dispatched(p_outbox_id TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE outbox SET status = 'DISPATCHED', dispatched_at = now() WHERE outbox_id = p_outbox_id;
END;
$$;

--;;

CREATE FUNCTION voucha.timeout_schedule(
    p_timeout_id TEXT, p_tree_id TEXT, p_node_id TEXT, p_effect_instance_id TEXT, p_timeout_kind TEXT,
    p_event_type TEXT, p_payload JSONB, p_fire_at TIMESTAMPTZ)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO timeout_schedule
        (timeout_id, tree_id, node_id, effect_instance_id, timeout_kind, event_type, payload, fire_at, status)
    VALUES (p_timeout_id, p_tree_id, p_node_id, p_effect_instance_id, p_timeout_kind, p_event_type,
            COALESCE(p_payload, '{}'::jsonb), p_fire_at, 'PENDING');
END;
$$;

--;;

CREATE FUNCTION voucha.timeout_claim_due(p_at TIMESTAMPTZ, p_limit BIGINT)
RETURNS SETOF timeout_schedule
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM timeout_schedule WHERE status = 'PENDING' AND fire_at <= p_at
     ORDER BY fire_at LIMIT p_limit FOR UPDATE SKIP LOCKED;
END;
$$;

--;;

CREATE FUNCTION voucha.timeout_mark_fired(p_timeout_id TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE timeout_schedule SET status = 'FIRED', fired_at = now() WHERE timeout_id = p_timeout_id;
END;
$$;

--;;

CREATE FUNCTION voucha.timeout_cancel_for_node(p_tree_id TEXT, p_node_id TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE timeout_schedule SET status = 'CANCELLED'
     WHERE tree_id = p_tree_id AND node_id = p_node_id AND status = 'PENDING';
END;
$$;

--;;

-- --- the worker's grant ------------------------------------------------------------------------

-- Grant a role the worker's surface: every function in the schema the kernel,
-- the dispatcher and the runtime call, and nothing on the tables. The
-- application's and participant's functions are granted separately.
CREATE FUNCTION voucha.grant_worker(p_role TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    r RECORD;
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA voucha TO %I', p_role);
    FOR r IN SELECT p.oid::regprocedure AS sig
               FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'voucha'
                AND p.proname NOT IN ('create_tree', 'intervene', 'claim_effects', 'report_outcome',
                                      'extend_lease', 'grant_worker')
    LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO %I', r.sig, p_role);
    END LOOP;
END;
$$;

--;;

-- --- operator reads (metrics and inspection) -----------------------------------------------------

CREATE FUNCTION voucha.tree_nodes(p_tree_id TEXT)
RETURNS SETOF state_node
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM state_node WHERE tree_id = p_tree_id ORDER BY node_id;
END;
$$;

--;;

-- Outstanding effects by name and status, the oldest of each.
CREATE FUNCTION voucha.metrics_effect_backlog()
RETURNS TABLE (effect_name TEXT, status TEXT, n BIGINT, oldest TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT e.effect_name, e.status, COUNT(*), MIN(e.created_at) FROM effect_instance e
     WHERE e.status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
     GROUP BY e.effect_name, e.status ORDER BY COUNT(*) DESC;
END;
$$;

--;;

-- Effects flagged for an operator: 'uncertain' ones parked live with an unknown
-- outcome, 'unresolved' ones closed while the outside world may still hold them.
CREATE FUNCTION voucha.metrics_operator_effects(p_which TEXT)
RETURNS SETOF effect_instance
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF p_which = 'uncertain' THEN
        RETURN QUERY SELECT * FROM effect_instance
         WHERE status = 'DISPATCH_FAILED' AND next_attempt_at IS NULL
           AND failure_payload ->> 'requires-operator' = 'true';
    ELSIF p_which = 'unresolved' THEN
        RETURN QUERY SELECT * FROM effect_instance
         WHERE failure_payload ->> 'requires-operator' = 'true'
           AND status IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
         ORDER BY updated_at;
    ELSE
        RAISE EXCEPTION 'voucha: unknown operator effect set %', p_which USING ERRCODE = 'invalid_parameter_value';
    END IF;
END;
$$;

--;;

CREATE FUNCTION voucha.metrics_parked_events()
RETURNS SETOF event_log
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT * FROM event_log WHERE status = 'PARKED' ORDER BY received_at;
END;
$$;

--;;

-- Received events waiting, and timeouts due.
CREATE FUNCTION voucha.metrics_counts()
RETURNS TABLE (event_backlog BIGINT, due_timeouts BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT (SELECT COUNT(*) FROM event_log WHERE status = 'RECEIVED'),
           (SELECT COUNT(*) FROM timeout_schedule WHERE status = 'PENDING' AND fire_at <= now());
END;
$$;

--;;

-- Non-terminal nodes idle longer than p_minutes, with what they still owe.
CREATE FUNCTION voucha.metrics_stalled_nodes(p_minutes NUMERIC)
RETURNS TABLE (tree_id TEXT, node_id TEXT, node_type TEXT, local_state TEXT, idle_minutes NUMERIC, outstanding BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    SELECT n.tree_id, n.node_id, n.node_type, n.local_state,
           EXTRACT(EPOCH FROM (now() - n.updated_at)) / 60,
           (SELECT COUNT(*) FROM effect_instance e
             WHERE e.tree_id = n.tree_id AND e.owner_node_id = n.node_id
               AND e.status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'))
      FROM state_node n
     WHERE n.status = 'ACTIVE' AND n.updated_at < now() - (p_minutes * INTERVAL '1 minute')
     ORDER BY n.updated_at;
END;
$$;
