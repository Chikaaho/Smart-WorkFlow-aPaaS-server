CREATE TABLE IF NOT EXISTS sw_bpm_task_opinion_draft (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT,
    deleted SMALLINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    task_id VARCHAR(64) NOT NULL,
    process_instance_id VARCHAR(64) NOT NULL,
    node_key VARCHAR(128),
    round_no INTEGER NOT NULL DEFAULT 1,
    assignee_id BIGINT NOT NULL,
    opinion_form_id VARCHAR(64),
    opinion_form_version VARCHAR(64),
    comment VARCHAR(20000),
    opinion_data TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_bpm_opinion_draft_task_user
    ON sw_bpm_task_opinion_draft (tenant_id, task_id, assignee_id);
CREATE INDEX IF NOT EXISTS idx_bpm_opinion_draft_instance
    ON sw_bpm_task_opinion_draft (tenant_id, process_instance_id, assignee_id);
