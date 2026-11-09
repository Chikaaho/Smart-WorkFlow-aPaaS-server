CREATE TABLE sw_agent_graph_execution_request (
    id NUMBER(19) NOT NULL PRIMARY KEY,
    graph_def_id NUMBER(19) NOT NULL,
    graph_def_version NUMBER(10) NOT NULL,
    idempotency_key VARCHAR2(100),
    request_fingerprint VARCHAR2(64) NOT NULL,
    status VARCHAR2(32) NOT NULL,
    input CLOB NOT NULL,
    result_execution_id NUMBER(19),
    error_category VARCHAR2(64),
    error_message VARCHAR2(1000),
    latency_ms NUMBER(19),
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    cancel_requested_at TIMESTAMP,
    create_time TIMESTAMP,
    create_by VARCHAR2(64),
    update_time TIMESTAMP,
    update_by VARCHAR2(64),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL
);
-- create_by / idempotency_key 均可空：任一为空时不参与唯一判重（与 PG/MySQL 空值语义一致）。
CREATE UNIQUE INDEX uk_sw_agent_exec_req_idem ON sw_agent_graph_execution_request (
    CASE WHEN create_by IS NOT NULL AND idempotency_key IS NOT NULL THEN tenant_id END,
    CASE WHEN create_by IS NOT NULL AND idempotency_key IS NOT NULL THEN create_by END,
    CASE WHEN create_by IS NOT NULL AND idempotency_key IS NOT NULL THEN idempotency_key END);
CREATE INDEX idx_sw_agent_exec_req_status ON sw_agent_graph_execution_request (tenant_id, status, create_time);

CREATE TABLE sw_agent_graph_execution_event (
    id NUMBER(19) NOT NULL PRIMARY KEY,
    execution_id NUMBER(19) NOT NULL,
    sequence_no NUMBER(19) NOT NULL,
    event_type VARCHAR2(32) NOT NULL,
    node_id VARCHAR2(100),
    payload CLOB,
    create_time TIMESTAMP,
    create_by VARCHAR2(64),
    update_time TIMESTAMP,
    update_by VARCHAR2(64),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL
);
CREATE UNIQUE INDEX uk_sw_agent_exec_event_seq ON sw_agent_graph_execution_event (tenant_id, execution_id, sequence_no);
CREATE INDEX idx_sw_agent_exec_event_replay ON sw_agent_graph_execution_event (tenant_id, execution_id, sequence_no, deleted);
