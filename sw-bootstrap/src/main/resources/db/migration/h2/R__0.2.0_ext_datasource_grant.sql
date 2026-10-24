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

CREATE UNIQUE INDEX IF NOT EXISTS uk_bpm_ext_ds_grant
    ON sw_bpm_ext_datasource_grant (tenant_id, datasource_id, subject_type, subject_key);
CREATE INDEX IF NOT EXISTS idx_bpm_ext_ds_grant_subject
    ON sw_bpm_ext_datasource_grant (tenant_id, subject_type, subject_key);
