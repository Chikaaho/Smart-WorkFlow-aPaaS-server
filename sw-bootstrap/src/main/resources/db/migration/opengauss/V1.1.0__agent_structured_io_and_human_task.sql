-- 1.1.0：图执行结构化入参/出参（G1-4）、父子执行关联（G3 子图节点）、人工节点任务表（G3）。
ALTER TABLE sw_agent_graph_execution_request ADD COLUMN inputs_json TEXT;
ALTER TABLE sw_agent_graph_execution_request ADD COLUMN outputs_json TEXT;
ALTER TABLE sw_agent_graph_execution_request ADD COLUMN parent_execution_id BIGINT;
CREATE INDEX idx_sw_agent_exec_req_parent ON sw_agent_graph_execution_request (parent_execution_id);

CREATE TABLE IF NOT EXISTS sw_agent_graph_human_task (
    id BIGINT NOT NULL PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    assignee_user_id BIGINT,
    assignee_role_id BIGINT,
    payload_json TEXT,
    result_json TEXT,
    submitted_by BIGINT,
    submitted_at TIMESTAMP,
    finished_at TIMESTAMP,
    create_time TIMESTAMP,
    create_by VARCHAR(64),
    update_time TIMESTAMP,
    update_by VARCHAR(64),
    deleted SMALLINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_sw_agent_human_exec ON sw_agent_graph_human_task (tenant_id, execution_id, status);
CREATE INDEX idx_sw_agent_human_assignee ON sw_agent_graph_human_task (tenant_id, assignee_user_id, status);
