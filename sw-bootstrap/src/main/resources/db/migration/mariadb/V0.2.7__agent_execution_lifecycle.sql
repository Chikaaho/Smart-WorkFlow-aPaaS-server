CREATE TABLE sw_agent_graph_execution_request (
    id BIGINT NOT NULL PRIMARY KEY,
    graph_def_id BIGINT NOT NULL,
    graph_def_version INT NOT NULL,
    idempotency_key VARCHAR(100),
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input longtext NOT NULL,
    result_execution_id BIGINT,
    error_category VARCHAR(64),
    error_message VARCHAR(1000),
    latency_ms BIGINT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    cancel_requested_at TIMESTAMP,
    create_time TIMESTAMP,
    create_by VARCHAR(64),
    update_time TIMESTAMP,
    update_by VARCHAR(64),
    deleted SMALLINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_sw_agent_exec_req_idem ON sw_agent_graph_execution_request (tenant_id, create_by, idempotency_key);
CREATE INDEX idx_sw_agent_exec_req_status ON sw_agent_graph_execution_request (tenant_id, status, create_time);

CREATE TABLE sw_agent_graph_execution_event (
    id BIGINT NOT NULL PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    node_id VARCHAR(100),
    payload longtext,
    create_time TIMESTAMP,
    create_by VARCHAR(64),
    update_time TIMESTAMP,
    update_by VARCHAR(64),
    deleted SMALLINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_sw_agent_exec_event_seq ON sw_agent_graph_execution_event (tenant_id, execution_id, sequence_no);
CREATE INDEX idx_sw_agent_exec_event_replay ON sw_agent_graph_execution_event (tenant_id, execution_id, sequence_no, deleted);
