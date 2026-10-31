-- The SQL interface (docs/design/sql-interface.md): a schema `voucha` of functions
-- and views an application calls from inside its own transactions, and the
-- columns and table the participant protocol needs.

-- What the functions need to know of an artifact, in a form SQL can read:
-- event names, node types' initial states, effects' participants and outcome
-- events. Written by the artifact store; an artifact stored before this column
-- existed has none.
ALTER TABLE execution_artifact ADD COLUMN catalog JSONB NULL;

--;;

-- An effect whose definition names a participant is not dispatched by the
-- worker's adapters: it waits in CREATED for `voucha.claim_effects`.
ALTER TABLE effect_instance ADD COLUMN participant TEXT NULL;

--;;

-- Who holds the DISPATCHING lease on a participant effect (`updated_at` is when).
ALTER TABLE effect_instance ADD COLUMN claimed_by TEXT NULL;

--;;

CREATE INDEX ix_effect_instance_participant
    ON effect_instance (effect_name, created_at)
    WHERE participant IS NOT NULL AND status IN ('CREATED', 'DISPATCH_FAILED');

--;;

-- A participant's report, committed in the participant's own transaction with
-- the work it describes. The worker's dispatcher applies pending reports through
-- the same path a callback takes, so the lifecycle has one implementation.
CREATE TABLE effect_report (
    report_id BIGSERIAL PRIMARY KEY,
    effect_instance_id TEXT NOT NULL REFERENCES effect_instance(effect_instance_id),
    participant TEXT NOT NULL,
    outcome TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    external_request_id TEXT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    note TEXT NULL,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at TIMESTAMPTZ NULL,
    CONSTRAINT ck_effect_report_outcome CHECK (outcome IN ('completed', 'failed')),
    CONSTRAINT ck_effect_report_status CHECK (status IN ('PENDING', 'APPLIED', 'SUPERSEDED'))
);

--;;

CREATE INDEX ix_effect_report_pending ON effect_report (reported_at) WHERE status = 'PENDING';

--;;

CREATE SCHEMA voucha;

--;;

-- --- the application ---------------------------------------------------------------

CREATE FUNCTION voucha.create_tree(
    p_tree_id TEXT,
    p_artifact_id TEXT,
    p_root_node_id TEXT,
    p_root_node_type TEXT)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_catalog JSONB;
    v_initial TEXT;
BEGIN
    SELECT catalog INTO v_catalog FROM execution_artifact WHERE artifact_id = p_artifact_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'voucha: unknown artifact %', p_artifact_id USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF v_catalog IS NULL THEN
        RAISE EXCEPTION 'voucha: artifact % has no catalog; store it again with this version', p_artifact_id
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    v_initial := v_catalog -> 'node-types' -> p_root_node_type ->> 'initial-state';
    IF v_initial IS NULL THEN
        RAISE EXCEPTION 'voucha: artifact % has no node type %', p_artifact_id, p_root_node_type
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    INSERT INTO execution_tree (tree_id, artifact_id, root_node_id, status)
    VALUES (p_tree_id, p_artifact_id, p_root_node_id, 'ACTIVE');
    INSERT INTO state_node (tree_id, node_id, node_type, local_state, local_data, status)
    VALUES (p_tree_id, p_root_node_id, p_root_node_type, v_initial,
            '{"child-count": 0, "counters": {}}'::jsonb, 'ACTIVE');
    RETURN p_tree_id;
END;
$$;

--;;

-- Returns the event id. Absent, it is derived from the content, so the same
-- submission twice is one event. Raises on an unknown tree or, when the artifact
-- carries a catalog, an event type the definition does not declare.
CREATE FUNCTION voucha.submit_event(
    p_tree_id TEXT,
    p_target_node_id TEXT,
    p_event_type TEXT,
    p_payload JSONB DEFAULT '{}'::jsonb,
    p_event_id TEXT DEFAULT NULL,
    p_operator_id TEXT DEFAULT NULL,
    p_operator_reason TEXT DEFAULT NULL)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_artifact TEXT;
    v_catalog JSONB;
    v_event_id TEXT;
    v_payload JSONB := COALESCE(p_payload, '{}'::jsonb);
BEGIN
    SELECT t.artifact_id, a.catalog INTO v_artifact, v_catalog
      FROM execution_tree t JOIN execution_artifact a ON a.artifact_id = t.artifact_id
     WHERE t.tree_id = p_tree_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'voucha: unknown tree %', p_tree_id USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF v_catalog IS NOT NULL AND NOT (v_catalog -> 'events' ? p_event_type) THEN
        RAISE EXCEPTION 'voucha: event type % is not declared by the definition of tree %', p_event_type, p_tree_id
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM state_node WHERE tree_id = p_tree_id AND node_id = p_target_node_id) THEN
        RAISE EXCEPTION 'voucha: tree % has no node %', p_tree_id, p_target_node_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    v_event_id := COALESCE(p_event_id,
                           encode(sha256(convert_to(p_tree_id || E'\n' || p_target_node_id || E'\n'
                                                    || p_event_type || E'\n' || v_payload::text, 'UTF8')), 'hex'));
    INSERT INTO event_log (event_id, tree_id, target_node_id, event_type, artifact_id, payload, status,
                           operator_id, operator_reason)
    VALUES (v_event_id, p_tree_id, p_target_node_id, p_event_type, v_artifact, v_payload, 'RECEIVED',
            p_operator_id, p_operator_reason)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN v_event_id;
END;
$$;

--;;

-- An operator's event: the same submission, with the operator and the reason
-- required, so the log says who and why.
CREATE FUNCTION voucha.intervene(
    p_tree_id TEXT,
    p_target_node_id TEXT,
    p_event_type TEXT,
    p_payload JSONB,
    p_operator_id TEXT,
    p_reason TEXT)
RETURNS TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF p_operator_id IS NULL OR p_reason IS NULL THEN
        RAISE EXCEPTION 'voucha: an intervention names its operator and its reason'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    RETURN voucha.submit_event(p_tree_id, p_target_node_id, p_event_type, p_payload, NULL, p_operator_id, p_reason);
END;
$$;

--;;

-- --- the participant protocol ---------------------------------------------------------

-- Take up to p_limit effects of one name that wait for a participant, oldest
-- first, skipping what another participant holds. The claim is the dispatcher's
-- own: DISPATCHING, `updated_at` the lease time, and the claimant recorded.
CREATE FUNCTION voucha.claim_effects(
    p_effect_name TEXT,
    p_participant TEXT,
    p_limit INT DEFAULT 1)
RETURNS TABLE (
    effect_instance_id TEXT,
    tree_id TEXT,
    owner_node_id TEXT,
    request_payload JSONB,
    idempotency_key TEXT,
    uncertainty_policy TEXT,
    attempt INT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    UPDATE effect_instance e
       SET status = 'DISPATCHING', claimed_by = p_participant, updated_at = now()
     WHERE e.effect_instance_id IN (
           SELECT c.effect_instance_id FROM effect_instance c
            WHERE c.effect_name = p_effect_name
              AND c.participant IS NOT NULL
              AND (c.status = 'CREATED'
                   OR (c.status = 'DISPATCH_FAILED' AND c.next_attempt_at IS NOT NULL AND c.next_attempt_at <= now()))
            ORDER BY c.created_at
            LIMIT p_limit
            FOR UPDATE SKIP LOCKED)
    RETURNING e.effect_instance_id, e.tree_id, e.owner_node_id, e.request_payload, e.idempotency_key,
              e.uncertainty_policy, e.retry_count + 1;
END;
$$;

--;;

-- Record the outcome of a claimed effect. Call it in the transaction that
-- does the work: both commit or neither. Refuses an effect this participant
-- does not hold, which is what a claim reclaimed past its lease looks like,
-- so the late work rolls back with the refused report and the retry redoes it
-- under the idempotency key. Returns the report id.
CREATE FUNCTION voucha.report_outcome(
    p_effect_instance_id TEXT,
    p_participant TEXT,
    p_outcome TEXT,
    p_payload JSONB DEFAULT '{}'::jsonb,
    p_external_request_id TEXT DEFAULT NULL)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_report BIGINT;
BEGIN
    IF p_outcome NOT IN ('completed', 'failed') THEN
        RAISE EXCEPTION 'voucha: outcome is completed or failed, not %', p_outcome
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    PERFORM 1 FROM effect_instance
      WHERE effect_instance_id = p_effect_instance_id
        AND status = 'DISPATCHING' AND claimed_by = p_participant
      FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'voucha: effect % is not held by participant %', p_effect_instance_id, p_participant
            USING ERRCODE = 'serialization_failure';
    END IF;
    IF EXISTS (SELECT 1 FROM effect_report WHERE effect_instance_id = p_effect_instance_id AND status = 'PENDING') THEN
        RAISE EXCEPTION 'voucha: effect % already has a report waiting', p_effect_instance_id
            USING ERRCODE = 'unique_violation';
    END IF;
    INSERT INTO effect_report (effect_instance_id, participant, outcome, payload, external_request_id)
    VALUES (p_effect_instance_id, p_participant, p_outcome, COALESCE(p_payload, '{}'::jsonb), p_external_request_id)
    RETURNING report_id INTO v_report;
    RETURN v_report;
END;
$$;

--;;

-- Renew the lease on an effect this participant holds. Raises when it holds
-- none: the lease ran out and the effect went back by its policy.
CREATE FUNCTION voucha.extend_lease(
    p_effect_instance_id TEXT,
    p_participant TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE effect_instance SET updated_at = now()
     WHERE effect_instance_id = p_effect_instance_id
       AND status = 'DISPATCHING' AND claimed_by = p_participant;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'voucha: effect % is not held by participant %', p_effect_instance_id, p_participant
            USING ERRCODE = 'serialization_failure';
    END IF;
END;
$$;

--;;

-- --- the views --------------------------------------------------------------------------

CREATE VIEW voucha.trees AS
    SELECT tree_id, artifact_id, profile_name, profile_version, root_node_id, status, created_at, updated_at
      FROM execution_tree;

--;;

CREATE VIEW voucha.nodes AS
    SELECT tree_id, node_id, parent_node_id, node_type, local_state AS state, local_data AS data,
           status, lock_version, created_at, updated_at
      FROM state_node;

--;;

CREATE VIEW voucha.events AS
    SELECT event_id, tree_id, target_node_id, event_type, payload, status, attempt_count, last_error,
           operator_id, operator_reason, causation_id, received_at, processed_at
      FROM event_log;

--;;

CREATE VIEW voucha.effects AS
    SELECT e.effect_instance_id, e.tree_id, e.owner_node_id, e.effect_kind, e.effect_name, e.effect_version,
           e.status, e.participant, e.claimed_by, e.uncertainty_policy, e.idempotency_key, e.correlation_key,
           e.external_request_id, e.request_payload, e.completion_payload, e.failure_payload,
           e.retry_count, e.next_attempt_at, e.timeout_at, e.created_at, e.updated_at,
           (SELECT r.status FROM effect_report r WHERE r.effect_instance_id = e.effect_instance_id
             ORDER BY r.report_id DESC LIMIT 1) AS last_report
      FROM effect_instance e;

--;;

CREATE VIEW voucha.transitions AS
    SELECT * FROM node_transition_log;
