DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT p.oid::regprocedure AS sig
               FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
              WHERE n.nspname = 'voucha'
                AND p.proname IN ('event_lock', 'claim_events', 'event_get', 'event_insert', 'event_mark',
                                  'event_record_failure', 'event_parked', 'event_pending_auto', 'tree_insert',
                                  'tree_get', 'tree_set_status', 'node_insert', 'node_get', 'node_lock',
                                  'node_children', 'node_ancestors', 'node_child_state_counts', 'node_update',
                                  'artifact_put', 'artifact_get', 'artifact_list', 'transition_append',
                                  'transition_history', 'record_put', 'record_get', 'effect_insert', 'effect_get',
                                  'effect_by_external_request_id', 'effect_by_correlation_key', 'effect_by_owner',
                                  'effect_outstanding_count', 'claim_effects_for_dispatch', 'effect_set_status',
                                  'effect_defer', 'effect_record_attempt', 'effect_attempts', 'effect_write_logged',
                                  'effect_writes', 'report_claim', 'report_settle', 'outbox_enqueue', 'outbox_claim',
                                  'outbox_mark_dispatched', 'timeout_schedule', 'timeout_claim_due',
                                  'timeout_mark_fired', 'timeout_cancel_for_node', 'grant_worker', 'claim_event')
    LOOP
        EXECUTE 'DROP FUNCTION ' || r.sig;
    END LOOP;
END $$;
--;;
DROP TYPE voucha.locked_event;
--;;
ALTER FUNCTION voucha.commit_transition(TEXT, JSONB, JSONB, BOOLEAN, BOOLEAN, JSONB, JSONB, JSONB, JSONB, JSONB, JSONB) SECURITY INVOKER;
--;;
ALTER DEFAULT PRIVILEGES IN SCHEMA voucha GRANT EXECUTE ON FUNCTIONS TO PUBLIC;
--;;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA voucha TO PUBLIC;
