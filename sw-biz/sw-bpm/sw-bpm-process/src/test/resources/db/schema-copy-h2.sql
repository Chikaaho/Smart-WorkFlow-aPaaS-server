create table sw_bpm_copy_record (
    id                   bigint          not null primary key,
    create_time          timestamp       not null default current_timestamp,
    create_by            bigint,
    update_time          timestamp       not null default current_timestamp,
    update_by            bigint,
    deleted              smallint        not null default 0,
    tenant_id            bigint          not null default 0,
    version              bigint          not null default 0,
    process_instance_id  varchar(64)     not null,
    node_key             varchar(200)    not null,
    task_id              varchar(64),
    recipient_id         varchar(64)     not null,
    delivery_status      varchar(30)     not null,
    failure_reason       varchar(500)
);

create index idx_sw_bpm_copy_instance
    on sw_bpm_copy_record (tenant_id, process_instance_id);
