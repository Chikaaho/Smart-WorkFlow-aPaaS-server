create table sw_bpm_ext_datasource_grant (
    id NUMBER(19) PRIMARY KEY,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by NUMBER(19),
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    datasource_id NUMBER(19) NOT NULL,
    subject_type VARCHAR2(16) NOT NULL,
    subject_key VARCHAR2(64) NOT NULL
);

create UNIQUE index uk_bpm_ext_ds_grant
    ON sw_bpm_ext_datasource_grant (tenant_id, datasource_id, subject_type, subject_key);
create index idx_bpm_ext_ds_grant_subject
    ON sw_bpm_ext_datasource_grant (tenant_id, subject_type, subject_key);
