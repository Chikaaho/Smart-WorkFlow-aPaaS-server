-- ===================================================================
-- Smart-WorkFlow :: BPM 数据范围测试追加 Schema（H2）
-- 仅用于 BpmInstanceDataScopeTest：sw_bpm_instance 无 dept_id 列，
-- 等效条件（initiator_id IN (SELECT id FROM sys_user WHERE dept_id IN (...))）
-- 需要 sys_user 表支撑子查询；最小列集 + 租户列（租户拦截器自动追加条件）。
-- ===================================================================

create table sys_user (
    id          bigint          not null primary key,
    username    varchar(50)     not null,
    password    varchar(200)    not null,
    status      smallint        not null default 0,
    dept_id     bigint,
    create_by   bigint,
    tenant_id   bigint          not null default 0,
    deleted     smallint        not null default 0,
    must_change_password smallint   not null default 0
);
create unique index uk_sys_user_username on sys_user (username);
