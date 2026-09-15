-- G13a 隔离测试 schema：仅节点函数注册表与调用审计表（取自 V72/V75 H2 版本建表语句）
create table if not exists sw_bpm_node_function (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    func_key          varchar(128)    not null,
    func_version      int             not null,
    func_type         varchar(32)     not null,
    impl_bean         varchar(128)    not null,
    config            clob,
    allowed_nodes     clob,
    timeout_ms        bigint          not null default 5000,
    failure_strategy  varchar(20)     not null default 'BLOCK',
    enabled           smallint        not null default 1
);

create table if not exists sw_bpm_node_function_audit (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    func_key varchar(128) not null,
    func_version int not null,
    func_type varchar(32) not null,
    process_instance_id varchar(64),
    node_key varchar(128),
    idempotent_key varchar(128),
    actor_id bigint,
    outcome varchar(32) not null,
    error_code int,
    summary varchar(2000),
    duration_ms bigint
);
create index if not exists idx_g13a_fn_audit on sw_bpm_node_function_audit (process_instance_id);
