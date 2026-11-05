CREATE TABLE sw_agent_graph_execution_request (
    id BIGINT NOT NULL PRIMARY KEY,
    graph_def_id BIGINT NOT NULL,
    graph_def_version INT NOT NULL,
    idempotency_key VARCHAR(100),
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input CLOB NOT NULL,
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
