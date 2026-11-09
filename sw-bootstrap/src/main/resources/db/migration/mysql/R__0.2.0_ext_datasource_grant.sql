CREATE TABLE IF NOT EXISTS sw_bpm_ext_datasource_grant (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT,
    deleted SMALLINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    datasource_id BIGINT NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_key VARCHAR(64) NOT NULL
);

SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'CREATE UNIQUE INDEX uk_bpm_ext_ds_grant ON sw_bpm_ext_datasource_grant (tenant_id, datasource_id, subject_type, subject_key)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sw_bpm_ext_datasource_grant' AND index_name = 'uk_bpm_ext_ds_grant');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;
SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'CREATE INDEX idx_bpm_ext_ds_grant_subject ON sw_bpm_ext_datasource_grant (tenant_id, subject_type, subject_key)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sw_bpm_ext_datasource_grant' AND index_name = 'idx_bpm_ext_ds_grant_subject');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;
