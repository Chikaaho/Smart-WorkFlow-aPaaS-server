-- P21 修正：统一命令端到端关联标识与 IoT 行为审计。
alter table sw_iot_command add column if not exists correlation_id varchar(128);
alter table sw_iot_event_record add column if not exists correlation_id varchar(128);
alter table sw_iot_script_exec add column if not exists correlation_id varchar(128);

create table if not exists sw_iot_audit_record (
    id               bigint          not null primary key,
    create_time      timestamp       not null default current_timestamp,
    create_by        bigint,
    update_time      timestamp       not null default current_timestamp,
    update_by        bigint,
    deleted          smallint        not null default 0,
    tenant_id        bigint          not null default 0,
    version          bigint          not null default 0,
    action           varchar(64)     not null,
    object_type      varchar(64)     not null,
    object_id        varchar(128)    not null,
    result           varchar(32)     not null,
    actor_id         bigint          not null,
    system_identity  varchar(128)    not null,
    action_time      timestamp       not null default current_timestamp,
    correlation_id   varchar(128)    not null,
    detail           varchar(1000)
);
create index if not exists idx_sw_iot_audit_lookup
    on sw_iot_audit_record (tenant_id, action, object_type, object_id, action_time);
create index if not exists idx_sw_iot_audit_correlation
    on sw_iot_audit_record (tenant_id, correlation_id);
create index if not exists idx_sw_iot_event_correlation
    on sw_iot_event_record (tenant_id, correlation_id);
create index if not exists idx_sw_iot_exec_correlation
    on sw_iot_script_exec (tenant_id, correlation_id);
