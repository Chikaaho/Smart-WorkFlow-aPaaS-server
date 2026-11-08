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
    opinion_comment longtext,
    opinion_data longtext
);

SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'CREATE UNIQUE INDEX uk_bpm_opinion_draft_task_user ON sw_bpm_task_opinion_draft (tenant_id, task_id, assignee_id)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sw_bpm_task_opinion_draft' AND index_name = 'uk_bpm_opinion_draft_task_user');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;
SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'CREATE INDEX idx_bpm_opinion_draft_instance ON sw_bpm_task_opinion_draft (tenant_id, process_instance_id, assignee_id)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sw_bpm_task_opinion_draft' AND index_name = 'idx_bpm_opinion_draft_instance');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;
