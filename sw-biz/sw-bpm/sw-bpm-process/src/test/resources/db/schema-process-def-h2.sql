create table sw_bpm_process_def (
    id                    bigint          not null primary key,
    create_time           timestamp       not null default current_timestamp,
    create_by             bigint,
    update_time           timestamp       not null default current_timestamp,
    update_by             bigint,
    deleted               smallint        not null default 0,
    tenant_id             bigint          not null default 0,
    version               bigint          not null default 0,
    process_key           varchar(200)    not null,
    name                  varchar(200)    not null,
    form_key              varchar(200)    not null,
    def_version           int             not null default 1,
    status                varchar(20)     not null default 'DRAFT',
    deployment_id         varchar(64),
    process_definition_id varchar(64),
    graph_json            clob,
    category_id           bigint,
    iot_access_enabled    boolean not null default false,
    iot_device_action_json text,
    published_version     int,
    source_template_id    bigint,
    source_template_version int
);

create index idx_sw_bpm_proc_def_key on sw_bpm_process_def (process_key);
create index idx_sw_bpm_proc_def_form on sw_bpm_process_def (form_key);
