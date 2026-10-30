create table sys_tenant_status_audit (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null,
    version         bigint          not null default 0,
    action          varchar(20)     not null,
    old_status      smallint,
    new_status      smallint,
    expire_time     timestamp,
    reason          varchar(500)    not null,
    operator_id     bigint          not null,
    occurred_at     timestamp       not null,
    result          varchar(200)    not null
);

create index idx_sys_tenant_status_audit_tenant_time
    on sys_tenant_status_audit (tenant_id, occurred_at);

create unique index uk_sys_tenant_status_audit_expiry
    on sys_tenant_status_audit (tenant_id, action, expire_time);
