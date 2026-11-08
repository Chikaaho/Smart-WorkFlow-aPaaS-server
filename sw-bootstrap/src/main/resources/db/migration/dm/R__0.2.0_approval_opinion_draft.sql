create table sw_bpm_task_opinion_draft (
    id NUMBER(19) PRIMARY KEY,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by NUMBER(19),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    task_id VARCHAR2(64) NOT NULL,
    process_instance_id VARCHAR2(64) NOT NULL,
    node_key VARCHAR2(128),
    round_no NUMBER(10) DEFAULT 1 NOT NULL,
    assignee_id NUMBER(19) NOT NULL,
    opinion_form_id VARCHAR2(64),
    opinion_form_version VARCHAR2(64),
    opinion_comment CLOB,
    opinion_data CLOB
);

create UNIQUE index uk_bpm_opinion_draft_task_user
    ON sw_bpm_task_opinion_draft (tenant_id, task_id, assignee_id);
create index idx_bpm_opinion_draft_instance
    ON sw_bpm_task_opinion_draft (tenant_id, process_instance_id, assignee_id);
