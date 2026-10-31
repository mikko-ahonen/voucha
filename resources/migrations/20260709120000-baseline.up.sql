-- Baseline schema for the generic hierarchical state machine kernel.
-- No table here may assume a specific process profile (design 44, 65).
--
-- Encoding conventions (see docs/design/decisions.md):
--   D3  state_patch is canonical EDN text; local_data / payloads stay JSONB.
--   D4  transition logs carry a context hash plus the constraint read-set.
--   D5  external effects and store effects share effect_instance, split by effect_kind.

CREATE TABLE execution_artifact (
    artifact_id TEXT PRIMARY KEY,
    profile_name TEXT NULL,
    profile_version TEXT NULL,
    specs TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

--;;

CREATE TABLE execution_tree (
    tree_id TEXT PRIMARY KEY,
    artifact_id TEXT NOT NULL REFERENCES execution_artifact(artifact_id),
    profile_name TEXT NULL,
    profile_version TEXT NULL,
    root_node_id TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

--;;

CREATE TABLE state_node (
    tree_id TEXT NOT NULL REFERENCES execution_tree(tree_id),
    node_id TEXT NOT NULL,
    parent_node_id TEXT NULL,
    node_type TEXT NOT NULL,
    local_state TEXT NOT NULL,
    local_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    lock_version BIGINT NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tree_id, node_id)
);

--;;

CREATE INDEX ix_state_node_parent ON state_node(tree_id, parent_node_id);

--;;

CREATE INDEX ix_state_node_type_state ON state_node(node_type, local_state);

--;;

CREATE TABLE node_transition_log (
    transition_id TEXT PRIMARY KEY,
    tree_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    artifact_id TEXT NOT NULL,
    triggering_event_id TEXT NOT NULL,
    from_state TEXT NOT NULL,
    to_state TEXT NOT NULL,
    handler_name TEXT NOT NULL,
    hierarchical_context_hash TEXT NULL,
    context_read_set JSONB NULL,
    operation_inputs_hash TEXT NULL,
    operation_outputs JSONB NULL,
    state_patch TEXT NOT NULL,
    emitted_events JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_effects JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_store_effects JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

--;;

CREATE INDEX ix_node_transition_log_node
ON node_transition_log(tree_id, node_id, created_at);

--;;

CREATE TABLE event_log (
    event_id TEXT PRIMARY KEY,
    tree_id TEXT NULL,
    target_node_id TEXT NULL,
    event_type TEXT NOT NULL,
    artifact_id TEXT NULL,
    correlation_key TEXT NULL,
    causation_id TEXT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    operator_id TEXT NULL,
    operator_reason TEXT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ NULL
);

--;;

CREATE INDEX ix_event_log_target ON event_log(tree_id, target_node_id);

--;;

CREATE INDEX ix_event_log_correlation ON event_log(correlation_key);

--;;

CREATE INDEX ix_event_log_status ON event_log(status, received_at);

--;;

CREATE TABLE effect_instance (
    effect_instance_id TEXT PRIMARY KEY,
    tree_id TEXT NOT NULL,
    owner_node_id TEXT NOT NULL,
    effect_kind TEXT NOT NULL,
    effect_name TEXT NOT NULL,
    effect_version TEXT NOT NULL,
    artifact_id TEXT NOT NULL,
    status TEXT NOT NULL,
    uncertainty_policy TEXT NOT NULL DEFAULT 'manual',
    idempotency_key TEXT NOT NULL,
    correlation_key TEXT NOT NULL,
    external_request_id TEXT NULL,
    external_transaction_id TEXT NULL,
    external_correlation_id TEXT NULL,
    external_resource_id TEXT NULL,
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_payload_hash TEXT NOT NULL,
    ack_payload JSONB NULL,
    completion_payload JSONB NULL,
    failure_payload JSONB NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NULL,
    timeout_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_effect_kind CHECK (effect_kind IN ('external', 'store')),
    CONSTRAINT ck_uncertainty_policy
        CHECK (uncertainty_policy IN ('retry-safe', 'query', 'manual'))
);

--;;

CREATE UNIQUE INDEX ux_effect_idempotency
ON effect_instance(effect_name, effect_version, idempotency_key);

--;;

CREATE INDEX ix_effect_owner ON effect_instance(tree_id, owner_node_id);

--;;

CREATE INDEX ix_effect_status_next_attempt ON effect_instance(status, next_attempt_at);

--;;

CREATE INDEX ix_effect_external_request ON effect_instance(external_request_id);

--;;

CREATE TABLE effect_attempt (
    attempt_id TEXT PRIMARY KEY,
    effect_instance_id TEXT NOT NULL REFERENCES effect_instance(effect_instance_id),
    attempt_no INT NOT NULL,
    status TEXT NOT NULL,
    external_request_id TEXT NULL,
    response_payload JSONB NULL,
    error_detail TEXT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ NULL
);

--;;

CREATE UNIQUE INDEX ux_effect_attempt_no
ON effect_attempt(effect_instance_id, attempt_no);

--;;

CREATE TABLE outbox (
    outbox_id TEXT PRIMARY KEY,
    tree_id TEXT NULL,
    node_id TEXT NULL,
    type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at TIMESTAMPTZ NULL
);

--;;

CREATE INDEX ix_outbox_status_created ON outbox(status, created_at);

--;;

CREATE TABLE timeout_schedule (
    timeout_id TEXT PRIMARY KEY,
    tree_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    effect_instance_id TEXT NULL,
    timeout_kind TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    fire_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    fired_at TIMESTAMPTZ NULL
);

--;;

CREATE INDEX ix_timeout_status_fire_at ON timeout_schedule(status, fire_at);

--;;

CREATE TABLE runtime_record (
    tree_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    record_type TEXT NOT NULL,
    record_key TEXT NOT NULL,
    record_version TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tree_id, node_id, record_type, record_key, record_version)
);

--;;

CREATE TABLE effect_write_log (
    write_id TEXT PRIMARY KEY,
    effect_instance_id TEXT NOT NULL REFERENCES effect_instance(effect_instance_id),
    target TEXT NOT NULL,
    record_key TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    written_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

--;;

CREATE UNIQUE INDEX ux_effect_write_log_idem
ON effect_write_log(effect_instance_id, target, record_key);
