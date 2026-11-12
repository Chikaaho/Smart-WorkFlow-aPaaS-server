-- 1.1.0：图执行结构化入参/出参（G1-4）、父子执行关联（G3 子图节点）、人工节点任务表（G3）。
ALTER TABLE sw_agent_graph_execution_request ADD (
    inputs_json CLOB,
    outputs_json CLOB,
    pause_state_json CLOB,
    parent_execution_id NUMBER(19)
);
CREATE INDEX idx_sw_agent_exec_req_parent ON sw_agent_graph_execution_request (parent_execution_id);

ALTER TABLE sw_agent_graph_execution ADD (parent_execution_id NUMBER(19));
ALTER TABLE sw_agent_graph_execution_node ADD (child_execution_id NUMBER(19));

create table sw_agent_graph_human_task (
    id NUMBER(19) NOT NULL PRIMARY KEY,
    execution_id NUMBER(19) NOT NULL,
    node_id VARCHAR2(100 CHAR) NOT NULL,
    status VARCHAR2(32 CHAR) NOT NULL,
    assignee_user_id NUMBER(19),
    assignee_role_code VARCHAR2(64 CHAR),
    payload_json CLOB,
    result_json CLOB,
    submitted_by NUMBER(19),
    submitted_at TIMESTAMP,
    finished_at TIMESTAMP,
    create_time TIMESTAMP,
    create_by VARCHAR2(64 CHAR),
    update_time TIMESTAMP,
    update_by VARCHAR2(64 CHAR),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL
);
CREATE INDEX idx_sw_agent_human_exec ON sw_agent_graph_human_task (tenant_id, execution_id, status);
CREATE INDEX idx_sw_agent_human_assignee ON sw_agent_graph_human_task (tenant_id, assignee_user_id, status);
