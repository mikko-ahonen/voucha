-- The application functions carry what the Clojure side's submit and create carry
-- (the edge-auth seam's actor and claims, a correlation key, the profile), so the
-- kernel's own submit and create call them and the contract has one implementation.

DROP FUNCTION voucha.submit_event(TEXT, TEXT, TEXT, JSONB, TEXT, TEXT, TEXT);

--;;

-- Returns the event id. Absent, it is derived from the content, so the same
-- submission twice is one event. Raises on an unknown tree or node or, when the
-- artifact carries a catalog, an event type the definition does not declare. A
-- null target is an event that bubbles along the artifact's routes.
CREATE FUNCTION voucha.submit_event(
    p_tree_id TEXT,
    p_target_node_id TEXT,
    p_event_type TEXT,
    p_payload JSONB DEFAULT '{}'::jsonb,
    p_event_id TEXT DEFAULT NULL,
    p_operator_id TEXT DEFAULT NULL,
    p_operator_reason TEXT DEFAULT NULL,
    p_actor TEXT DEFAULT NULL,
    p_claims JSONB DEFAULT NULL,
    p_correlation_key TEXT DEFAULT NULL)
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
    IF p_target_node_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM state_node WHERE tree_id = p_tree_id AND node_id = p_target_node_id) THEN
        RAISE EXCEPTION 'voucha: tree % has no node %', p_tree_id, p_target_node_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    v_event_id := COALESCE(p_event_id,
                           encode(sha256(convert_to(p_tree_id || E'\n' || COALESCE(p_target_node_id, '') || E'\n'
                                                    || p_event_type || E'\n' || v_payload::text, 'UTF8')), 'hex'));
    INSERT INTO event_log (event_id, tree_id, target_node_id, event_type, artifact_id, correlation_key, payload,
                           status, operator_id, operator_reason, actor, claims)
    VALUES (v_event_id, p_tree_id, p_target_node_id, p_event_type, v_artifact, p_correlation_key, v_payload,
            'RECEIVED', p_operator_id, p_operator_reason, p_actor, p_claims)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN v_event_id;
END;
$$;

--;;

DROP FUNCTION voucha.create_tree(TEXT, TEXT, TEXT, TEXT);

--;;

CREATE FUNCTION voucha.create_tree(
    p_tree_id TEXT,
    p_artifact_id TEXT,
    p_root_node_id TEXT,
    p_root_node_type TEXT,
    p_profile_name TEXT DEFAULT NULL,
    p_profile_version TEXT DEFAULT NULL)
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
    INSERT INTO execution_tree (tree_id, artifact_id, profile_name, profile_version, root_node_id, status)
    VALUES (p_tree_id, p_artifact_id, p_profile_name, p_profile_version, p_root_node_id, 'ACTIVE');
    INSERT INTO state_node (tree_id, node_id, node_type, local_state, local_data, status)
    VALUES (p_tree_id, p_root_node_id, p_root_node_type, v_initial,
            '{"child-count": 0, "counters": {}}'::jsonb, 'ACTIVE');
    RETURN p_tree_id;
END;
$$;

--;;

-- The worker process hosts the admin API, which starts trees, submits and
-- intervenes on an operator's behalf, so its grant covers the application's
-- functions; only the participant's protocol and this grant are outside it.
CREATE OR REPLACE FUNCTION voucha.grant_worker(p_role TEXT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    r RECORD;
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA voucha TO %I', p_role);
    FOR r IN SELECT p.oid::regprocedure AS sig
               FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'voucha'
                AND p.proname NOT IN ('claim_effects', 'report_outcome', 'extend_lease', 'grant_worker')
    LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO %I', r.sig, p_role);
    END LOOP;
END;
$$;
