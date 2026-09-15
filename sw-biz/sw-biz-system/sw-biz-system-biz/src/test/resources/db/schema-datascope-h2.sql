-- ===================================================================
-- Smart-WorkFlow :: 数据范围相关测试 Schema（H2）
-- 覆盖 sys_dept / sys_role / sys_user / sys_user_role / sys_role_menu /
-- sys_menu / sys_role_dept；角色相关表（sys_role/sys_user_role/sys_role_menu/
-- sys_role_dept）与生产 V1→V30 迁移链尾一致：sys_role 为 V5 后契约
-- （built_in boolean / remark varchar(255)、唯一索引 uk_sys_role_tenant_code
-- 及其 V13 加 deleted 列形态）。
-- ===================================================================

create table sys_dept (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    parent_id       bigint          not null default 0,
    name            varchar(100)    not null,
    code            varchar(50),
    sort            integer         not null default 0,
    status          smallint        not null default 0,
    description     clob,
    leader_id       bigint
);
create index idx_sys_dept_parent_id on sys_dept (parent_id);

create table sys_role (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    name            varchar(100)    not null,
    code            varchar(50)     not null,
    sort            integer         not null default 0,
    status          smallint        not null default 0,
    -- 测试用可空列：验证 UserDetailsProviderImpl 对 dataScope=null（历史脏数据/旧库）的兜底；
    -- 生产链尾（V5 起 data_scope 已改为可空、无默认值）与本定义一致
    data_scope      smallint,
    remark          varchar(255),
    built_in        boolean         not null default false
);
create unique index uk_sys_role_tenant_code on sys_role (tenant_id, code, deleted);

create table sys_user (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    username        varchar(50)     not null,
    password        varchar(200)    not null,
    real_name       varchar(50),
    email           varchar(100),
    phone           varchar(20),
    avatar          varchar(200),
    sex             smallint        not null default 0,
    status          smallint        not null default 0,
    dept_id         bigint,
    last_login_time timestamp,
    last_login_ip   varchar(50),
    remark          clob,
    is_admin        smallint        not null default 0
);
create unique index uk_sys_user_username on sys_user (username);
create index idx_sys_user_dept_id on sys_user (dept_id);

create table sys_user_role (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    user_id         bigint          not null,
    role_id         bigint          not null
);
create unique index uk_sys_user_role_tenant on sys_user_role (tenant_id, user_id, role_id, deleted);

create table sys_role_menu (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    role_id         bigint          not null,
    menu_id         bigint          not null
);
create unique index uk_sys_role_menu_tenant on sys_role_menu (tenant_id, role_id, menu_id, deleted);

-- 全局表（无 tenant_id 列，同 V1 定义）
create table sys_menu (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    version         bigint          not null default 0,
    parent_id       bigint          not null default 0,
    name            varchar(100)    not null,
    menu_type       smallint        not null default 0,
    path            varchar(200),
    component       varchar(200),
    permission      varchar(100),
    icon            varchar(100),
    sort            integer         not null default 0,
    status          smallint        not null default 0,
    visible         smallint        not null default 1,
    keep_alive      smallint        not null default 0,
    description     clob
);
create index idx_sys_menu_parent_id on sys_menu (parent_id);

-- V30：角色部门关联（CUSTOM 数据范围的可见部门集合）
create table sys_role_dept (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    role_id         bigint          not null,
    dept_id         bigint          not null
);

create table sys_post (
    id bigint not null primary key, create_time timestamp not null default current_timestamp, create_by bigint,
    update_time timestamp not null default current_timestamp, update_by bigint, deleted smallint not null default 0,
    tenant_id bigint not null default 0, version bigint not null default 0, code varchar(50) not null,
    name varchar(100) not null, sort integer not null default 0, status smallint not null default 0, description varchar(255)
);
create table sys_user_post (
    id bigint not null primary key, create_time timestamp not null default current_timestamp, create_by bigint,
    update_time timestamp not null default current_timestamp, update_by bigint, deleted smallint not null default 0,
    tenant_id bigint not null default 0, version bigint not null default 0, user_id bigint not null, post_id bigint not null,
    dept_id bigint not null default 0
);
create unique index uk_sys_user_post_tenant on sys_user_post (tenant_id, user_id, post_id, deleted);
create unique index uk_sys_role_dept on sys_role_dept (role_id, dept_id);

-- P28/I36（D112）：用户组表（与生产 V34 一致）
create table sys_user_group (
    id bigint not null,
    create_time timestamp,
    create_by bigint,
    update_time timestamp,
    update_by bigint,
    deleted smallint not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    group_code varchar(64) not null,
    group_name varchar(64) not null,
    status smallint not null default 0,
    remark varchar(255)
);
create unique index uk_sys_user_group_code on sys_user_group (tenant_id, group_code, deleted);
create table sys_user_group_member (
    id bigint not null,
    create_time timestamp,
    create_by bigint,
    update_time timestamp,
    update_by bigint,
    deleted smallint not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    group_id bigint not null,
    user_id bigint not null
);
create unique index uk_sys_user_group_member on sys_user_group_member (tenant_id, group_id, user_id, deleted);
