-- V0.1.0 baseline seed：102 条 versioned 迁移（V1—V104）合并基线。
-- 由 0.1.3 发版批次的种子合并生成：内容为原迁移链按版本序的逐字节拼接，
-- 仅去除重复副本；全新建库终态与原链等价（等价性证据见 0.1.3 发布回执）。
-- 0.1.3 起不支持从 ≤0.1.2 库原地升级（历史链已移除），仅支持全新建库。

-- ======== V1（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow 初始化建表脚本 (PostgreSQL)
-- ===================================================================

-- -------------------- 租户 --------------------
create table sys_tenant (
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
    status          smallint        not null default 0,
    description     text,
    contact_name    varchar(50),
    contact_phone   varchar(20),
    contact_email   varchar(100),
    expire_time     timestamp,
    domain_name     varchar(100)
);
create unique index uk_sys_tenant_code on sys_tenant (code);

-- -------------------- 部门 --------------------
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
    description     text,
    leader_id       bigint
);
create index idx_sys_dept_parent_id on sys_dept (parent_id);

-- -------------------- 岗位 --------------------
create table sys_post (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    code            varchar(50)     not null,
    name            varchar(100)    not null,
    sort            integer         not null default 0,
    status          smallint        not null default 0,
    description     text
);

-- -------------------- 用户 --------------------
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
    remark          text,
    is_admin        smallint        not null default 0
);
create unique index uk_sys_user_username on sys_user (username);
create index idx_sys_user_dept_id on sys_user (dept_id);

-- -------------------- 角色 --------------------
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
    data_scope      smallint        not null default 0,
    description     text,
    is_builtin      smallint        not null default 0
);
create unique index uk_sys_role_code on sys_role (code);

-- -------------------- 菜单 --------------------
create table sys_menu (
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
    menu_type       smallint        not null default 0,
    path            varchar(200),
    component       varchar(200),
    permission      varchar(100),
    icon            varchar(100),
    sort            integer         not null default 0,
    status          smallint        not null default 0,
    visible         smallint        not null default 1,
    keep_alive      smallint        not null default 0,
    description     text
);
create index idx_sys_menu_parent_id on sys_menu (parent_id);

-- -------------------- 用户角色关联 --------------------
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
create unique index uk_sys_user_role on sys_user_role (user_id, role_id);

-- -------------------- 角色菜单关联 --------------------
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
create unique index uk_sys_role_menu on sys_role_menu (role_id, menu_id);

-- -------------------- 字典类型 --------------------
create table sys_dict_type (
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
    status          smallint        not null default 0,
    description     text
);
create unique index uk_sys_dict_type_code on sys_dict_type (code);

-- -------------------- 字典数据 --------------------
create table sys_dict_data (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    dict_code       varchar(50)     not null,
    label           varchar(100)    not null,
    dict_value      varchar(100)    not null,
    sort            integer         not null default 0,
    status          smallint        not null default 0,
    is_default      smallint        not null default 0,
    css_class       varchar(50),
    list_class      varchar(50),
    description     text
);
create index idx_sys_dict_data_dict_code on sys_dict_data (dict_code);

-- ======== V2（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow 初始化基础数据 (PostgreSQL)
-- ===================================================================

-- -------------------- 内置租户 --------------------
insert into sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description, domain_name) values (1, current_timestamp, current_timestamp, 0, 0, 0, '默认租户', 'default', 0, '系统内置默认租户，不可删除', 'localhost');

-- ===================================================================
-- 字典种子数据（租户隔离模型）
-- 字典采用「每租户各自持有一份」的纯隔离模型，不做 tenant_id=0 全局兜底。
-- 以下为 super tenant（tenant_id=0）的字典种子，其内容（dict_code / dict_value / label）
-- 与其它租户的字典一致，仅 tenant_id 不同。
-- 各 INSERT 已显式指定 tenant_id=0，确保隔离边界清晰。
--
-- ⚠️ 已知待办：新增租户时，需在租户创建流程中复制一套基准字典到新租户。
--    本步不实现，仅在此标注。未来在租户创建 BizService 中实现字典模板复制。
-- ===================================================================

-- -------------------- 字典类型（tenant_id=0） --------------------
insert into sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description) values (1, current_timestamp, current_timestamp, 0, 0, 0, '通用状态', 'sys_common_status', 0, '0=正常 1=停用');
insert into sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description) values (2, current_timestamp, current_timestamp, 0, 0, 0, '是否', 'sys_yes_no', 0, '0=否 1=是');
insert into sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description) values (3, current_timestamp, current_timestamp, 0, 0, 0, '性别', 'sys_user_sex', 0, '用户性别');
insert into sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description) values (4, current_timestamp, current_timestamp, 0, 0, 0, '用户状态', 'sys_user_status', 0, '用户账号状态');
insert into sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description) values (5, current_timestamp, current_timestamp, 0, 0, 0, '菜单类型', 'sys_menu_type', 0, '菜单节点类型');
insert into sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description) values (6, current_timestamp, current_timestamp, 0, 0, 0, '数据范围', 'sys_data_scope', 0, '角色数据权限范围');

-- -------------------- 字典数据：通用状态（tenant_id=0） --------------------
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (1, current_timestamp, current_timestamp, 0, 0, 0, 'sys_common_status', '正常', '0', 0, 0, 1);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (2, current_timestamp, current_timestamp, 0, 0, 0, 'sys_common_status', '停用', '1', 1, 0, 0);

-- -------------------- 字典数据：是否（tenant_id=0） --------------------
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (3, current_timestamp, current_timestamp, 0, 0, 0, 'sys_yes_no', '否', '0', 0, 0, 1);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (4, current_timestamp, current_timestamp, 0, 0, 0, 'sys_yes_no', '是', '1', 1, 0, 0);

-- -------------------- 字典数据：性别（tenant_id=0） --------------------
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (5, current_timestamp, current_timestamp, 0, 0, 0, 'sys_user_sex', '未知', '0', 0, 0, 1);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (6, current_timestamp, current_timestamp, 0, 0, 0, 'sys_user_sex', '男', '1', 1, 0, 0);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (7, current_timestamp, current_timestamp, 0, 0, 0, 'sys_user_sex', '女', '2', 2, 0, 0);

-- -------------------- 字典数据：用户状态（tenant_id=0） --------------------
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (8, current_timestamp, current_timestamp, 0, 0, 0, 'sys_user_status', '正常', '0', 0, 0, 1);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (9, current_timestamp, current_timestamp, 0, 0, 0, 'sys_user_status', '停用', '1', 1, 0, 0);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (10, current_timestamp, current_timestamp, 0, 0, 0, 'sys_user_status', '锁定', '2', 2, 0, 0);

-- -------------------- 字典数据：菜单类型（tenant_id=0） --------------------
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (11, current_timestamp, current_timestamp, 0, 0, 0, 'sys_menu_type', '目录', '0', 0, 0, 1);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (12, current_timestamp, current_timestamp, 0, 0, 0, 'sys_menu_type', '菜单', '1', 1, 0, 0);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (13, current_timestamp, current_timestamp, 0, 0, 0, 'sys_menu_type', '按钮', '2', 2, 0, 0);

-- -------------------- 字典数据：数据范围（tenant_id=0） --------------------
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (14, current_timestamp, current_timestamp, 0, 0, 0, 'sys_data_scope', '全部数据', '0', 0, 0, 1);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (15, current_timestamp, current_timestamp, 0, 0, 0, 'sys_data_scope', '本部门及以下', '1', 1, 0, 0);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (16, current_timestamp, current_timestamp, 0, 0, 0, 'sys_data_scope', '本部门', '2', 2, 0, 0);
insert into sys_dict_data (id, create_time, update_time, deleted, tenant_id, version, dict_code, label, dict_value, sort, status, is_default) values (17, current_timestamp, current_timestamp, 0, 0, 0, 'sys_data_scope', '仅本人', '3', 3, 0, 0);

-- -------------------- 内置角色：超级管理员 --------------------
insert into sys_role (id, create_time, update_time, deleted, tenant_id, version, name, code, sort, status, data_scope, description, is_builtin) values (1, current_timestamp, current_timestamp, 0, 0, 0, '超级管理员', 'admin', 0, 0, 0, '系统内置超级管理员角色，拥有所有权限', 1);

-- -------------------- 内置菜单：系统管理 --------------------
-- 一级目录
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (1, current_timestamp, current_timestamp, 0, 0, 0, 0, '系统管理', 0, '/system', '', '', 'system', 100, 0, 1, 1, '系统管理目录');
-- 二级菜单
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (10, current_timestamp, current_timestamp, 0, 0, 0, 1, '用户管理', 1, 'user', 'system/user/index', 'system:user:list', 'user', 1, 0, 1, 1, '系统用户管理');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (11, current_timestamp, current_timestamp, 0, 0, 0, 1, '角色管理', 1, 'role', 'system/role/index', 'system:role:list', 'role', 2, 0, 1, 1, '系统角色管理');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (12, current_timestamp, current_timestamp, 0, 0, 0, 1, '菜单管理', 1, 'menu', 'system/menu/index', 'system:menu:list', 'menu', 3, 0, 1, 1, '系统菜单管理');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (13, current_timestamp, current_timestamp, 0, 0, 0, 1, '部门管理', 1, 'dept', 'system/dept/index', 'system:dept:list', 'dept', 4, 0, 1, 1, '机构部门管理');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (14, current_timestamp, current_timestamp, 0, 0, 0, 1, '岗位管理', 1, 'post', 'system/post/index', 'system:post:list', 'post', 5, 0, 1, 1, '岗位管理');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (15, current_timestamp, current_timestamp, 0, 0, 0, 1, '字典管理', 1, 'dict', 'system/dict/index', 'system:dict:list', 'dict', 6, 0, 1, 1, '数据字典管理');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (16, current_timestamp, current_timestamp, 0, 0, 0, 1, '租户管理', 1, 'tenant', 'system/tenant/index', 'system:tenant:list', 'tenant', 7, 0, 1, 1, '多租户管理');
-- 按钮权限：用户管理
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (100, current_timestamp, current_timestamp, 0, 0, 0, 10, '用户新增', 2, '', '', 'system:user:create', '', 1, 0, 1, 0, '');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (101, current_timestamp, current_timestamp, 0, 0, 0, 10, '用户修改', 2, '', '', 'system:user:update', '', 2, 0, 1, 0, '');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (102, current_timestamp, current_timestamp, 0, 0, 0, 10, '用户删除', 2, '', '', 'system:user:delete', '', 3, 0, 1, 0, '');
-- 按钮权限：角色管理
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (110, current_timestamp, current_timestamp, 0, 0, 0, 11, '角色新增', 2, '', '', 'system:role:create', '', 1, 0, 1, 0, '');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (111, current_timestamp, current_timestamp, 0, 0, 0, 11, '角色修改', 2, '', '', 'system:role:update', '', 2, 0, 1, 0, '');
insert into sys_menu (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, menu_type, path, component, permission, icon, sort, status, visible, keep_alive, description) values (112, current_timestamp, current_timestamp, 0, 0, 0, 11, '角色删除', 2, '', '', 'system:role:delete', '', 3, 0, 1, 0, '');

-- -------------------- 超管角色绑定所有菜单 --------------------
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (1, current_timestamp, current_timestamp, 0, 0, 0, 1, 1);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (2, current_timestamp, current_timestamp, 0, 0, 0, 1, 10);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (3, current_timestamp, current_timestamp, 0, 0, 0, 1, 11);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (4, current_timestamp, current_timestamp, 0, 0, 0, 1, 12);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (5, current_timestamp, current_timestamp, 0, 0, 0, 1, 13);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (6, current_timestamp, current_timestamp, 0, 0, 0, 1, 14);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (7, current_timestamp, current_timestamp, 0, 0, 0, 1, 15);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (8, current_timestamp, current_timestamp, 0, 0, 0, 1, 16);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (9, current_timestamp, current_timestamp, 0, 0, 0, 1, 100);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (10, current_timestamp, current_timestamp, 0, 0, 0, 1, 101);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (11, current_timestamp, current_timestamp, 0, 0, 0, 1, 102);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (12, current_timestamp, current_timestamp, 0, 0, 0, 1, 110);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (13, current_timestamp, current_timestamp, 0, 0, 0, 1, 111);
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) values (14, current_timestamp, current_timestamp, 0, 0, 0, 1, 112);

-- ======== V3（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V3: 外部数据源连接 + SQL 执行审计 (PostgreSQL)
-- ===================================================================

-- -------------------- 外部数据源连接信息 --------------------
create table sw_bpm_ext_datasource (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    name            varchar(100)    not null,
    type            varchar(50)     not null,
    jdbc_url        varchar(500)    not null,
    driver_class    varchar(200)    not null,
    username        varchar(100)    not null,
    password_cipher text            not null,
    read_only       smallint        not null default 1,
    enabled         smallint        not null default 1
);
create unique index uk_sw_bpm_ext_ds_name on sw_bpm_ext_datasource (name);
comment on table sw_bpm_ext_datasource is '外部数据源连接信息';
comment on column sw_bpm_ext_datasource.password_cipher is 'AES-256-GCM 加密密码密文';
comment on column sw_bpm_ext_datasource.read_only is '是否只读：1=是，0=否（建议配合只读账号）';
comment on column sw_bpm_ext_datasource.enabled is '是否启用：1=启用，0=禁用';

-- -------------------- SQL 执行审计日志 --------------------
create table sw_bpm_ext_sql_execution_audit (
    id                  bigint          not null primary key,
    create_time         timestamp       not null default current_timestamp,
    create_by           bigint,
    update_time         timestamp       not null default current_timestamp,
    update_by           bigint,
    deleted             smallint        not null default 0,
    tenant_id           bigint          not null default 0,
    version             bigint          not null default 0,
    datasource_id       bigint          not null,
    datasource_name     varchar(100)    not null,
    sql_text            text            not null,
    row_count           integer,
    execution_time_ms   bigint,
    success             smallint        not null default 0,
    error_message       text,
    operator_id         bigint          not null,
    operator_name       varchar(50)     not null
);
create index idx_sw_bpm_ext_audit_ds_id on sw_bpm_ext_sql_execution_audit (datasource_id);
create index idx_sw_bpm_ext_audit_operator_id on sw_bpm_ext_sql_execution_audit (operator_id);
create index idx_sw_bpm_ext_audit_create_time on sw_bpm_ext_sql_execution_audit (create_time);
comment on table sw_bpm_ext_sql_execution_audit is 'SQL 执行审计日志';
comment on column sw_bpm_ext_sql_execution_audit.sql_text is '执行的 SQL 原文';
comment on column sw_bpm_ext_sql_execution_audit.success is '执行结果：0=失败，1=成功';

-- ======== V4（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V4: 系统管理种子数据（根部门 + 管理员用户）
-- 依赖 V1__init_schema.sql 中已建立的 sys_dept / sys_user / sys_user_role 表
-- ===================================================================

-- -------------------- 根部门 --------------------
insert into sys_dept (id, create_time, update_time, deleted, tenant_id, version,
                      parent_id, name, code, sort, status, description)
values (1, current_timestamp, current_timestamp, 0, 0, 0,
        0, '根部门', 'root', 0, 0, '系统根部门');

-- -------------------- 管理员用户 --------------------
-- 明文密码: admin123（仅 dev 使用）
-- BCrypt 散列: strength=10, $2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK
insert into sys_user (id, create_time, update_time, deleted, tenant_id, version,
                      username, password, real_name, dept_id, status, is_admin)
values (1, current_timestamp, current_timestamp, 0, 0, 0,
        'admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK',
        '系统管理员', 1, 0, 1);

-- -------------------- 关联管理员用户到超管角色 --------------------
insert into sys_user_role (id, create_time, update_time, deleted, tenant_id, version,
                           user_id, role_id)
values (1, current_timestamp, current_timestamp, 0, 0, 0, 1, 1);

-- ======== V5（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V5: M-Seam RBAC — 表结构调整与种子数据对齐
-- 调整 sys_role / sys_menu / sys_user_role / sys_role_menu 至 M-Seam 规范：
--   · sys_menu → 移除租户列，增加 title/hidden；继承 BaseEntityNoTenant
--   · sys_role → is_builtin→built_in(boolean), description→remark, 增加唯一(tenant_id,code)
--   · sys_user_role → 唯一约束改为 (tenant_id, user_id, role_id)
--   · sys_role_menu → 唯一约束改为 (tenant_id, role_id, menu_id)
--   · 超管角色 code 对齐 superadmin，status 对齐 CommonStatusEnum.ENABLE(=1)
-- ===================================================================

-- ==================== 1. sys_menu ====================
-- 新增 title 列，从 name 填充
ALTER TABLE sys_menu ADD COLUMN title varchar(64) NOT NULL DEFAULT '';
UPDATE sys_menu SET title = name;
ALTER TABLE sys_menu ALTER COLUMN title DROP DEFAULT;

-- 新增 hidden 列（替换 visible 语义：visible=1 → hidden=false）
ALTER TABLE sys_menu ADD COLUMN hidden boolean NOT NULL DEFAULT false;
UPDATE sys_menu SET hidden = (visible = 0);
ALTER TABLE sys_menu ALTER COLUMN hidden DROP DEFAULT;

-- 移除旧列（tenant_id → 全局表不再需要；visible/keep_alive/status/description → 新规范中移除）
ALTER TABLE sys_menu DROP COLUMN tenant_id;
ALTER TABLE sys_menu DROP COLUMN visible;
ALTER TABLE sys_menu DROP COLUMN keep_alive;
ALTER TABLE sys_menu DROP COLUMN status;
ALTER TABLE sys_menu DROP COLUMN description;

-- 调整列类型与 M-Seam 规范一致（扩宽始终安全，不会丢数据）
ALTER TABLE sys_menu ALTER COLUMN name TYPE varchar(64);
ALTER TABLE sys_menu ALTER COLUMN path TYPE varchar(128);
ALTER TABLE sys_menu ALTER COLUMN component TYPE varchar(255);
ALTER TABLE sys_menu ALTER COLUMN permission TYPE varchar(128);
ALTER TABLE sys_menu ALTER COLUMN icon TYPE varchar(64);

-- ==================== 2. sys_role ====================
-- is_builtin → built_in（类型改为 boolean）
ALTER TABLE sys_role ADD COLUMN built_in boolean NOT NULL DEFAULT false;
UPDATE sys_role SET built_in = (is_builtin = 1);
ALTER TABLE sys_role DROP COLUMN is_builtin;

-- description → remark
ALTER TABLE sys_role ADD COLUMN remark varchar(255);
UPDATE sys_role SET remark = description;
ALTER TABLE sys_role DROP COLUMN description;

-- 调整列类型
ALTER TABLE sys_role ALTER COLUMN name TYPE varchar(64);
ALTER TABLE sys_role ALTER COLUMN code TYPE varchar(64);

-- data_scope 改为可空、无默认值（S7 预留，本环不生效）
ALTER TABLE sys_role ALTER COLUMN data_scope DROP NOT NULL;
ALTER TABLE sys_role ALTER COLUMN data_scope DROP DEFAULT;

-- 唯一索引：从 (code) 改为 (tenant_id, code)
DROP INDEX IF EXISTS uk_sys_role_code;
CREATE UNIQUE INDEX uk_sys_role_tenant_code ON sys_role (tenant_id, code);

-- ==================== 3. sys_user_role ====================
-- 唯一索引：从 (user_id, role_id) 改为 (tenant_id, user_id, role_id)
DROP INDEX IF EXISTS uk_sys_user_role;
CREATE UNIQUE INDEX uk_sys_user_role_tenant ON sys_user_role (tenant_id, user_id, role_id);

-- ==================== 4. sys_role_menu ====================
-- 唯一索引：从 (role_id, menu_id) 改为 (tenant_id, role_id, menu_id)
DROP INDEX IF EXISTS uk_sys_role_menu;
CREATE UNIQUE INDEX uk_sys_role_menu_tenant ON sys_role_menu (tenant_id, role_id, menu_id);

-- ==================== 5. 种子数据对齐 ====================
-- 超管角色：code 从 admin → superadmin，status 对齐 CommonStatusEnum.ENABLE(=1)，标记内置
UPDATE sys_role SET code = 'superadmin', data_scope = NULL, status = 1 WHERE id = 1;
-- 确保超管角色标记内置（V4 插入时 is_builtin=1，V5 迁移后 built_in 为 true，此句为幂等保障）
UPDATE sys_role SET built_in = true WHERE id = 1;

-- ======== V6（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V6: M-Seam 导航菜单树 —— 替换 V2 旧占位菜单，按前端白名单 seed
--
-- 执行顺序：
--   1. 清理由 V2 旧菜单关联的 sys_role_menu 行
--   2. 删除 V2 旧菜单（id=10~16, 100~102, 110~112）
--   3. 更新 id=1（原为目录，改为菜单 + 对齐新字段）
--   4. 插入新菜单（id=2~9，共 9 节点树）
--
-- 约束：
--   · 不 seed sys_role_menu（超管旁路，本环既定）
--   · hidden 列类型为 boolean，使用 true/false 字面量
--   · component = NULL 表示目录/按钮（前端据此判定）
--   · permission 部分行为 NULL（目录/无权限标识的菜单）
-- ===================================================================

-- ==================== 1. 清理旧角色-菜单关联 ====================
DELETE FROM sys_role_menu WHERE menu_id IN (10, 11, 12, 13, 14, 15, 16, 100, 101, 102, 110, 111, 112);

-- ==================== 2. 删除 V2 旧菜单 ====================
DELETE FROM sys_menu WHERE id IN (10, 11, 12, 13, 14, 15, 16, 100, 101, 102, 110, 111, 112);

-- ==================== 3. 更新 id=1 为 System 菜单 ====================
-- 原 V2 占位为目录（menu_type=0, path='/system', component='', sort=100），
-- 改为菜单（menu_type=1）并对齐新字段规范。
UPDATE sys_menu SET
    name = 'System',
    path = 'system',
    component = 'system/views/SystemHome',
    permission = 'system:view',
    icon = 'Setting',
    sort = 10,
    menu_type = 1,
    hidden = false
WHERE id = 1;

-- ==================== 4. 插入新菜单 ====================
-- 低代码目录
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (2, current_timestamp, current_timestamp, 0, 0, 0, 'Lowcode', '低代码', false, 0, 'lowcode', NULL, NULL, 'Grid', 20);

-- 低代码 → 低代码概览
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (3, current_timestamp, current_timestamp, 0, 0, 2, 'LowcodeHome', '低代码概览', false, 1, 'overview', 'lowcode/views/LowcodeHome', 'lowcode:view', 'Document', 10);

-- 低代码 → 表单设计
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (4, current_timestamp, current_timestamp, 0, 0, 2, 'LowcodeForm', '表单设计', false, 1, 'form', 'lowcode/views/LowcodeForm', 'lowcode:form:design', 'EditPen', 20);

-- 流程引擎
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (5, current_timestamp, current_timestamp, 0, 0, 0, 'Workflow', '流程引擎', false, 1, 'workflow', 'workflow/views/WorkflowHome', 'workflow:view', 'Share', 30);

-- 通知
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (6, current_timestamp, current_timestamp, 0, 0, 0, 'Notify', '通知', false, 1, 'notify', 'notify/views/NotifyHome', 'notify:view', 'Bell', 40);

-- 智能体
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (7, current_timestamp, current_timestamp, 0, 0, 0, 'Agent', '智能体', false, 1, 'agent', 'agent/views/AgentHome', 'agent:view', 'MagicStick', 50);

-- 物联网
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (8, current_timestamp, current_timestamp, 0, 0, 0, 'Iot', '物联网', false, 1, 'iot', 'iot/views/IotHome', 'iot:view', 'Cpu', 60);

-- 开放接口
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (9, current_timestamp, current_timestamp, 0, 0, 0, 'Openapi', '开放接口', false, 1, 'openapi', 'openapi/views/OpenapiHome', 'openapi:view', 'Connection', 70);

-- ======== V7（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: Form 模块固定元数据表 (PostgreSQL)
-- ===================================================================
-- 注意：动态宽表（sw_form_{nanoId} / sw_form_table_{nanoId}）不在此处，
-- 由 DynamicTableManager 按 §6.2 例外管理。
-- ===================================================================

-- ==================== 1. 表单定义主表 ====================
CREATE TABLE sw_form_def (
    id                   VARCHAR(36)  PRIMARY KEY,
    form_key             VARCHAR(100) NOT NULL UNIQUE,
    name                 VARCHAR(200) NOT NULL,
    logical_table_name   VARCHAR(100),
    status               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    physical_table_name  VARCHAR(100),
    form_version         INT          NOT NULL DEFAULT 1,
    description          VARCHAR(500),
    sub_table_mapping    TEXT,
    tenant_id            BIGINT       NOT NULL DEFAULT 0,
    deleted              SMALLINT     NOT NULL DEFAULT 0,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by            BIGINT,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by            BIGINT,
    version              BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_def             IS '表单定义主表';
COMMENT ON COLUMN sw_form_def.id           IS 'UUID 主键';
COMMENT ON COLUMN sw_form_def.form_key     IS '表单业务标识（唯一）';
COMMENT ON COLUMN sw_form_def.name         IS '表单名称';
COMMENT ON COLUMN sw_form_def.logical_table_name IS '用户自定义逻辑表名';
COMMENT ON COLUMN sw_form_def.status       IS '状态: DRAFT(草稿) / PUBLISHED(已发布)';
COMMENT ON COLUMN sw_form_def.physical_table_name IS '发布后回填的动态宽表物理名';
COMMENT ON COLUMN sw_form_def.form_version IS '表单版本号（每次发布递增）';

-- ==================== 2. 表单配置/样式表 ====================
CREATE TABLE sw_form_config (
    id          VARCHAR(36)  PRIMARY KEY,
    form_id     VARCHAR(36)  NOT NULL,
    definition  JSONB        NOT NULL,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by   BIGINT,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT,
    version     BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_config               IS '表单配置/样式表';
COMMENT ON COLUMN sw_form_config.id             IS 'UUID 主键';
COMMENT ON COLUMN sw_form_config.form_id        IS '关联 sw_form_def.id';
COMMENT ON COLUMN sw_form_config.definition     IS '表单样式/控件/布局 schema (JSONB)';

-- ==================== 3. 表单快照表 ====================
CREATE TABLE sw_form_snapshot (
    id           VARCHAR(36)  PRIMARY KEY,
    form_id      VARCHAR(36)  NOT NULL,
    form_version INT          NOT NULL,
    definition   JSONB        NOT NULL,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    BIGINT,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by    BIGINT,
    version      BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_snapshot               IS '表单版本快照表';
COMMENT ON COLUMN sw_form_snapshot.id             IS 'UUID 主键';
COMMENT ON COLUMN sw_form_snapshot.form_id        IS '关联 sw_form_def.id';
COMMENT ON COLUMN sw_form_snapshot.form_version   IS '快照版本号（与 sw_form_def.form_version 对齐）';
COMMENT ON COLUMN sw_form_snapshot.definition     IS '该版本的完整 definition JSONB 快照';

-- ==================== 4. 表单提交溯源表 ====================
CREATE TABLE sw_form_trace (
    id                 VARCHAR(36)  PRIMARY KEY,
    form_id            VARCHAR(36)  NOT NULL,
    record_id          VARCHAR(36)  NOT NULL,
    submit_user_id     BIGINT       NOT NULL,
    submit_ip          VARCHAR(200),
    submit_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    device_fingerprint VARCHAR(200),
    user_agent         VARCHAR(500),
    tenant_id          BIGINT       NOT NULL DEFAULT 0,
    deleted            SMALLINT     NOT NULL DEFAULT 0,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by          BIGINT,
    update_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by          BIGINT,
    version            BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_trace                   IS '表单提交溯源表';
COMMENT ON COLUMN sw_form_trace.id                 IS 'UUID 主键';
COMMENT ON COLUMN sw_form_trace.form_id            IS '关联 sw_form_def.id';
COMMENT ON COLUMN sw_form_trace.record_id          IS '动态宽表记录 UUID';
COMMENT ON COLUMN sw_form_trace.submit_user_id     IS '提交人（BIGINT，指向 sys_user）';
COMMENT ON COLUMN sw_form_trace.submit_ip          IS '提交 IP（AES 加密存储）';
COMMENT ON COLUMN sw_form_trace.device_fingerprint IS '设备指纹（哈希值）';

-- ======== V8（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: BPM 模块元数据表 (PostgreSQL)
-- ===================================================================
-- M04 第三环第 2 步：表单↔流程绑定 + 流程实例记录。
-- 仅建表与数据访问层，不写发起逻辑、不写 listener、不写 Controller。
--
-- 约定：
--   表前缀       = sw_bpm_（§3）
--   8 基列在前   = id, create_time, create_by, update_time, update_by,
--                  deleted, tenant_id, version（与 sys_* 对齐）
--   PK           = bigint（ASSIGN_ID 雪花算法）
--   无真实 DB 外键（应用层处理关系）
--   全归 Flyway 管理（非动态宽表）
-- ===================================================================

-- ==================== 1. 表单↔流程绑定表 ====================
-- 将表单（by form_key）绑定到一条 BPMN 流程定义（by process_def_key）。
-- 一个表单只能有一条启用绑定（active=true），停用后可用新绑定替换。
create table sw_bpm_form_binding (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    form_key          varchar(200)    not null,
    process_def_key   varchar(200)    not null,
    active            boolean         not null default true
);

comment on table  sw_bpm_form_binding                is '表单↔流程绑定表';
comment on column sw_bpm_form_binding.form_key        is '表单业务标识（对应 FormSubmittedEvent.formKey）';
comment on column sw_bpm_form_binding.process_def_key is 'BPMN 流程定义 key（Flowable 部署用）';
comment on column sw_bpm_form_binding.active          is '是否启用：true=启用（唯一，同 form_key+tenant 仅一条）, false=停用';

-- 唯一索引：同租户下同表单只能有一条启用绑定
create unique index uk_sw_bpm_binding_active on sw_bpm_form_binding (tenant_id, form_key) where active = true;

-- ==================== 2. 流程实例记录表 ====================
-- 记录我方发起的每个 Flowable 流程实例，供"我发起的"/监控查询。
-- 与 Flowable ACT_HI_PROCINST 保持 process_instance_id 映射。
create table sw_bpm_instance (
    id                   bigint          not null primary key,
    create_time          timestamp       not null default current_timestamp,
    create_by            bigint,
    update_time          timestamp       not null default current_timestamp,
    update_by            bigint,
    deleted              smallint        not null default 0,
    tenant_id            bigint          not null default 0,
    version              bigint          not null default 0,
    process_instance_id  varchar(64)     not null,
    process_def_key      varchar(200)    not null,
    business_key         varchar(36)     not null,
    form_key             varchar(200)    not null,
    initiator_id         bigint          not null,
    status               varchar(20)     not null default 'RUNNING'
);

comment on table  sw_bpm_instance                       is '流程实例记录表';
comment on column sw_bpm_instance.process_instance_id    is 'Flowable 流程实例 ID（对应 ACT_HI_PROCINST.ID_）';
comment on column sw_bpm_instance.process_def_key        is 'BPMN 流程定义 key';
comment on column sw_bpm_instance.business_key           is '业务键（= 表单动态宽表 recordId，VARCHAR 36）';
comment on column sw_bpm_instance.form_key               is '表单业务标识';
comment on column sw_bpm_instance.initiator_id           is '发起人（指向 sys_user.id）';
comment on column sw_bpm_instance.status                 is '实例状态：RUNNING(运行中) / APPROVED(已通过) / REJECTED(已驳回)';

create index idx_sw_bpm_inst_process_inst on sw_bpm_instance (process_instance_id);
create index idx_sw_bpm_inst_business_key on sw_bpm_instance (business_key);
create index idx_sw_bpm_inst_tenant_status on sw_bpm_instance (tenant_id, status);

-- ======== V9（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: Notify 通知表 (PostgreSQL)
-- ===================================================================
-- M05 Step 1：新建 sw_notify_message 表 + Facade + 数据层。
--
-- 约定：
--   表前缀       = sw_notify_（§3）
--   8 基列在前   = id, create_time, create_by, update_time, update_by,
--                  deleted, tenant_id, version（与 sys_* 对齐）
--   PK           = bigint（ASSIGN_ID 雪花算法）
--   无真实 DB 外键（应用层处理关系）
--   全归 Flyway 管理（非动态宽表）
-- ===================================================================

create table sw_notify_message (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    recipient_id      bigint          not null,
    title             varchar(200)    not null,
    content           text            not null,
    biz_type          varchar(30)     not null,
    biz_id            varchar(64),
    is_read           boolean         not null default false
);

comment on table  sw_notify_message               is '站内信通知表';
comment on column sw_notify_message.recipient_id  is '接收人用户 ID（指向 sys_user.id）';
comment on column sw_notify_message.title         is '通知标题';
comment on column sw_notify_message.content       is '通知内容';
comment on column sw_notify_message.biz_type      is '业务类型（NotifyBizType 枚举 name：WF_TODO / WF_APPROVED）';
comment on column sw_notify_message.biz_id        is '业务 ID（Flowable taskId / piId 等，VARCHAR）';
comment on column sw_notify_message.is_read       is '是否已读';

-- 索引：同租户下按接收人查询
create index idx_sw_notify_msg_recipient on sw_notify_message (tenant_id, recipient_id);

-- ======== V10（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V10: 追加"字典管理"二级菜单
--
-- 在"系统管理"(id=1)下新增字典类型列表页菜单行，
-- 指向前端 DictTypeList 组件。
--
-- 约束：
--   · 不 seed sys_role_menu（超管旁路，沿用 V6 决策）
--   · 字典项列表页(DictDataList)是经类型页跳转进入的下钻页，
--     不单独建顶层菜单。
-- ===================================================================

-- 系统管理 → 字典管理
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (10, current_timestamp, current_timestamp, 0, 0, 1, 'DictManage', '字典管理', false, 1, 'dict', 'system/views/DictTypeList', 'system:dict:view', 'Collection', 10);

-- ======== V11（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V11: 将"系统管理"(id=1) 从叶子菜单矫正为目录
--
-- 使子节点"字典管理"(id=10) 在侧边栏可见。
-- V6 将 id=1 从 V2 的目录改为了菜单(menu_type=1, component='system/views/SystemHome')，
-- 导致其下子菜单无法展开。
-- 本迁移将其恢复为目录(menu_type=0, component=NULL)，
-- 子菜单的渲染交给对应叶子菜单自己的 component。
-- ===================================================================

UPDATE sys_menu SET menu_type = 0, component = NULL WHERE id = 1;

-- ======== V12（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: Form 模块样式元数据表升级 (PostgreSQL)
-- ===================================================================
-- V12: sw_form_config 从 "per form_id 一行" 升级到 "per physical table 一行"
--
-- 变更：
--   1. 新增 table_name 列（每个物理表唯一标识）
--   2. 新增 parent_table 列（子表指向父主表单 table_name）
--   3. table_name 唯一索引（NULL 不冲突）
--   4. parent_table 普通索引（查主表单的子表 WHERE parent_table = ?）
--
-- 注意：动态宽表（sw_form_{nanoId} / sw_form_table_{nanoId}）不在此处，
-- 由 DynamicTableManager 按 §6.2 例外管理。
-- ===================================================================

-- ==================== 1. 扩展 sw_form_config ====================
ALTER TABLE sw_form_config ADD COLUMN table_name   VARCHAR(200);
ALTER TABLE sw_form_config ADD COLUMN parent_table VARCHAR(200);

-- table_name 唯一（NULL 不冲突，符合 SQL 标准）
ALTER TABLE sw_form_config ADD CONSTRAINT uk_sw_form_cfg_tname UNIQUE (table_name);

-- parent_table 查子表用
CREATE INDEX idx_sw_form_cfg_parent ON sw_form_config(parent_table);

COMMENT ON COLUMN sw_form_config.table_name   IS '物理表名（唯一 key，主表单/子表各占一行）';
COMMENT ON COLUMN sw_form_config.parent_table IS '父表 table_name（子表行填写，主表单/被引用表单留空）';

-- ======== V13（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V13: 逻辑删除唯一约束改造 — 所有唯一索引加上 deleted 列
-- ===================================================================
-- 目的：支持逻辑删除（@TableLogic）后，以相同业务键重建记录时不撞唯一索引。
-- 背景：所有主库业务表已有 deleted 列（smallint not null default 0），
--       但现有唯一约束/唯一索引不含 deleted，导致软删后 INSERT 同名记录失败。
--
-- 方案：将所有唯一索引改为复合唯一 (..., deleted) 而非 partial WHERE deleted=0。
-- 理由：复合唯一列是 ANSI SQL 标准，PG / H2 / MySQL 通用；
--       允许 (key, deleted=0) 与 (key, deleted=1) 共存。
--
-- 改动：
--   1. sys_tenant(code)                     → UNIQUE (code, deleted)
--   2. sys_user(username)                   → UNIQUE (username, deleted)
--   3. sys_role(tenant_id, code)            → UNIQUE (tenant_id, code, deleted)
--   4. sys_user_role(tenant_id, user_id, role_id) → UNIQUE (..., deleted)
--   5. sys_role_menu(tenant_id, role_id, menu_id) → UNIQUE (..., deleted)
--   6. sys_dict_type(code)                  → UNIQUE (code, deleted)
--   7. sw_form_def(form_key)                → UNIQUE (form_key, deleted)
--   8. sw_form_config(table_name)           → UNIQUE (table_name, deleted)
--   9. sw_bpm_ext_datasource(name)          → UNIQUE (name, deleted)
--
-- PG 侧说明（H2 侧 V13 保持原样，两份互为镜像）：
--   第 7 项 sw_form_def 的唯一约束由 V7 inline UNIQUE（form_key VARCHAR(100)
--   NOT NULL UNIQUE）创建，PG 中该约束背书隐式索引 sw_form_def_form_key_key，
--   直接 DROP INDEX 触发 2BP01（cannot drop index ... because constraint ...
--   requires it），必须先 ALTER TABLE ... DROP CONSTRAINT 释放；
--   H2 允许直接 DROP INDEX，故 H2 侧 V13 不采用此写法。
--
-- 无需改动的唯一索引：
--   · sw_bpm_form_binding.uk_sw_bpm_binding_active
--     — 已有 WHERE active=true，不屏蔽软删重建
--
-- ❌ 不碰动态宽表（sw_form_{nanoId} / sw_form_table_{nanoId}）
-- ❌ 不碰 ACT_*（Flowable 自管）
-- ❌ 不碰扩展库表
-- ===================================================================

-- ==================== 1. sys_tenant ====================
DROP INDEX IF EXISTS uk_sys_tenant_code;
CREATE UNIQUE INDEX uk_sys_tenant_code ON sys_tenant (code, deleted);

-- ==================== 2. sys_user ====================
DROP INDEX IF EXISTS uk_sys_user_username;
CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (username, deleted);

-- ==================== 3. sys_role ====================
DROP INDEX IF EXISTS uk_sys_role_tenant_code;
CREATE UNIQUE INDEX uk_sys_role_tenant_code ON sys_role (tenant_id, code, deleted);

-- ==================== 4. sys_user_role ====================
DROP INDEX IF EXISTS uk_sys_user_role_tenant;
CREATE UNIQUE INDEX uk_sys_user_role_tenant ON sys_user_role (tenant_id, user_id, role_id, deleted);

-- ==================== 5. sys_role_menu ====================
DROP INDEX IF EXISTS uk_sys_role_menu_tenant;
CREATE UNIQUE INDEX uk_sys_role_menu_tenant ON sys_role_menu (tenant_id, role_id, menu_id, deleted);

-- ==================== 6. sys_dict_type ====================
DROP INDEX IF EXISTS uk_sys_dict_type_code;
CREATE UNIQUE INDEX uk_sys_dict_type_code ON sys_dict_type (code, deleted);

-- ==================== 7. sw_form_def (form_key) ====================
-- V7 inline UNIQUE 在 PG 中创建的是约束背书的隐式索引 sw_form_def_form_key_key，
-- 直接 DROP INDEX 会触发 2BP01（cannot drop index ... because constraint ...
-- requires it），必须先 DROP CONSTRAINT 释放该隐式索引；
-- H2 允许直接 DROP INDEX，故 H2 侧 V13 保持 DROP INDEX 不变。
ALTER TABLE sw_form_def DROP CONSTRAINT IF EXISTS sw_form_def_form_key_key;
CREATE UNIQUE INDEX uk_sw_form_def_form_key ON sw_form_def (form_key, deleted);

-- ==================== 8. sw_form_config (table_name) ====================
-- V12 用 ALTER TABLE ADD CONSTRAINT 创建的唯一约束
ALTER TABLE sw_form_config DROP CONSTRAINT IF EXISTS uk_sw_form_cfg_tname;
CREATE UNIQUE INDEX uk_sw_form_cfg_tname ON sw_form_config (table_name, deleted);

-- ==================== 9. sw_bpm_ext_datasource ====================
DROP INDEX IF EXISTS uk_sw_bpm_ext_ds_name;
CREATE UNIQUE INDEX uk_sw_bpm_ext_ds_name ON sw_bpm_ext_datasource (name, deleted);

-- ==================== 校验说明 ====================
-- 全部索引保持原名（uk_xxx），仅改定义（复合列加上 deleted）。
-- 版本 V13 = 当前最高 V12 + 1。
-- 排除项理由：
--   ✅ sw_bpm_form_binding.uk_sw_bpm_binding_active — 已有 WHERE active=true，不冲突
--   ✅ sys_post — 无唯一约束
--   ✅ sys_dict_data — 无唯一约束（仅普通索引 idx_sys_dict_data_dict_code）
--   ✅ sys_menu — 无唯一约束
--   ✅ sys_dept — 无唯一约束
--   ✅ sw_form_snapshot — 无唯一约束
--   ✅ sw_form_trace — 无唯一约束
--   ✅ sw_notify_message — 无唯一约束
--   ✅ sw_bpm_instance — 无唯一约束
--   ✅ sw_bpm_ext_sql_execution_audit — 无唯一约束

-- ======== V14（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: BPM 流程定义表 (PostgreSQL)
-- ===================================================================
-- cut A：流程定义图模型存储。
-- 本表走 MyBatis-Plus 常规通道：@TableLogic + 租户拦截器自动处理
-- deleted/tenant_id，不写裸 SQL。
--
-- 约定：
--   表前缀       = sw_bpm_（§3）
--   8 基列在前   = id, create_time, create_by, update_time, update_by,
--                  deleted, tenant_id, version（与现有 sw_bpm_ 表对齐）
--   PK           = bigint（ASSIGN_ID 雪花算法）
--   graph_json   = clob（H2）/ text（PG），存储 ProcessGraph JSON
--   status       = DRAFT | PUBLISHED；本刀恒 DRAFT
-- ===================================================================

create table sw_bpm_process_def (
    id                   bigint          not null primary key,
    create_time          timestamp       not null default current_timestamp,
    create_by            bigint,
    update_time          timestamp       not null default current_timestamp,
    update_by            bigint,
    deleted              smallint        not null default 0,
    tenant_id            bigint          not null default 0,
    version              bigint          not null default 0,
    process_key          varchar(200)    not null,
    name                 varchar(200)    not null,
    form_key             varchar(200)    not null,
    def_version          int             not null default 1,
    status               varchar(20)     not null default 'DRAFT',
    deployment_id        varchar(64),
    process_definition_id varchar(64),
    graph_json           text
);

comment on table  sw_bpm_process_def                 is '流程定义表（图模型存储）';
comment on column sw_bpm_process_def.process_key      is '流程业务 key（发布后冻结）';
comment on column sw_bpm_process_def.name             is '流程名称';
comment on column sw_bpm_process_def.form_key         is '绑定表单 formKey';
comment on column sw_bpm_process_def.def_version      is '定义版本号（默认 1）';
comment on column sw_bpm_process_def.status           is '状态：DRAFT（草稿）/ PUBLISHED（已发布）';
comment on column sw_bpm_process_def.deployment_id    is 'Flowable 部署 ID（cut B 回填）';
comment on column sw_bpm_process_def.process_definition_id is 'Flowable 流程定义 ID（cut B 回填）';
comment on column sw_bpm_process_def.graph_json       is '图 JSON 文档（ProcessGraph 序列化）';

create index idx_sw_bpm_proc_def_key on sw_bpm_process_def (process_key);
create index idx_sw_bpm_proc_def_form  on sw_bpm_process_def (form_key);

-- ======== V15（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V15: 系统管理子菜单 —— 用户管理 / 角色管理 / 部门管理 / 岗位管理
--
-- 约束：
--   · id 使用 11-14（避开 1-9, 15+ 以免冲突）
--   · parent_id = 1（System 菜单，详见 V6）
--   · component 路径使用 vue 组件路径格式（无后缀无前导斜杠）
--   · 不 seed sys_role_menu（超管旁路）
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (11, current_timestamp, current_timestamp, 0, 0, 1, 'User', '用户管理', false, 1, 'user', 'system/views/UserList', 'system:user:list', 'User', 10);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (12, current_timestamp, current_timestamp, 0, 0, 1, 'Role', '角色管理', false, 1, 'role', 'system/views/RoleList', 'system:role:list', 'Avatar', 20);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (13, current_timestamp, current_timestamp, 0, 0, 1, 'Dept', '部门管理', false, 1, 'dept', 'system/views/DeptList', 'system:dept:list', 'Collection', 30);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (14, current_timestamp, current_timestamp, 0, 0, 1, 'Post', '岗位管理', false, 1, 'post', 'system/views/PostList', 'system:post:list', 'Tickets', 40);

-- ======== V16（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V16: 初始化文件存储记录表 (PostgreSQL)
-- ===================================================================
CREATE TABLE sw_storage_file (
    id              BIGINT      NOT NULL,
    original_name   VARCHAR(512) NOT NULL,
    storage_key     VARCHAR(512) NOT NULL,
    storage_name    VARCHAR(512) NOT NULL,
    file_size       BIGINT      NOT NULL DEFAULT 0,
    content_type    VARCHAR(255) DEFAULT NULL,
    file_ext        VARCHAR(32)  DEFAULT NULL,
    provider_type   VARCHAR(32)  NOT NULL DEFAULT 'local',
    bucket_name     VARCHAR(255) DEFAULT NULL,
    storage_url     VARCHAR(1024) DEFAULT NULL,
    create_time     TIMESTAMP    DEFAULT NULL,
    create_by       BIGINT       DEFAULT NULL,
    update_time     TIMESTAMP    DEFAULT NULL,
    update_by       BIGINT       DEFAULT NULL,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    version         BIGINT       DEFAULT NULL,
    PRIMARY KEY (id)
);

COMMENT ON TABLE  sw_storage_file              IS '文件存储记录';
COMMENT ON COLUMN sw_storage_file.id            IS '主键';
COMMENT ON COLUMN sw_storage_file.original_name IS '文件原始名称';
COMMENT ON COLUMN sw_storage_file.storage_key   IS '存储唯一标识（提供商侧 key）';
COMMENT ON COLUMN sw_storage_file.storage_name  IS '存储文件名（系统重命名，含扩展名）';
COMMENT ON COLUMN sw_storage_file.file_size     IS '文件大小（字节）';
COMMENT ON COLUMN sw_storage_file.content_type  IS '文件 MIME 类型';
COMMENT ON COLUMN sw_storage_file.file_ext      IS '文件扩展名（小写，不含点）';
COMMENT ON COLUMN sw_storage_file.provider_type IS '存储提供商类型（local/minio/cos/qiniu）';
COMMENT ON COLUMN sw_storage_file.bucket_name   IS '存储桶名称（本地模式为目录名）';
COMMENT ON COLUMN sw_storage_file.storage_url   IS '文件访问地址';
COMMENT ON COLUMN sw_storage_file.create_time   IS '创建时间';
COMMENT ON COLUMN sw_storage_file.create_by     IS '创建人';
COMMENT ON COLUMN sw_storage_file.update_time   IS '更新时间';
COMMENT ON COLUMN sw_storage_file.update_by     IS '更新人';
COMMENT ON COLUMN sw_storage_file.deleted       IS '逻辑删除标记（0=未删, 1=已删）';
COMMENT ON COLUMN sw_storage_file.tenant_id     IS '租户 ID';
COMMENT ON COLUMN sw_storage_file.version       IS '乐观锁版本号';

CREATE INDEX idx_sw_storage_file_tenant_deleted ON sw_storage_file (tenant_id, deleted);

-- ======== V17（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V17: 初始化定时任务调度表 (PostgreSQL)
-- ===================================================================
CREATE TABLE sw_job_info (
    id              BIGINT      NOT NULL,
    job_name        VARCHAR(128) NOT NULL,
    job_group       VARCHAR(128) NOT NULL DEFAULT 'DEFAULT',
    job_type        VARCHAR(16)  NOT NULL DEFAULT 'BEAN',
    cron_expression VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    concurrent      SMALLINT     NOT NULL DEFAULT 0,
    misfire_policy  SMALLINT     NOT NULL DEFAULT 0,
    description     VARCHAR(512) DEFAULT NULL,
    bean_name       VARCHAR(256) DEFAULT NULL,
    bean_params     TEXT         DEFAULT NULL,
    flow_def_key    VARCHAR(128) DEFAULT NULL,
    form_data       TEXT         DEFAULT NULL,
    last_fire_time  TIMESTAMP    DEFAULT NULL,
    next_fire_time  TIMESTAMP    DEFAULT NULL,
    create_time     TIMESTAMP    DEFAULT NULL,
    create_by       BIGINT       DEFAULT NULL,
    update_time     TIMESTAMP    DEFAULT NULL,
    update_by       BIGINT       DEFAULT NULL,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    version         BIGINT       DEFAULT NULL,
    PRIMARY KEY (id)
);

COMMENT ON TABLE  sw_job_info               IS '定时任务定义';
COMMENT ON COLUMN sw_job_info.id            IS '主键';
COMMENT ON COLUMN sw_job_info.job_name      IS '任务名称';
COMMENT ON COLUMN sw_job_info.job_group     IS '任务组（Quartz JobKey 分组）';
COMMENT ON COLUMN sw_job_info.job_type      IS '任务类型（BEAN=处理器 / FLOW=发起流程）';
COMMENT ON COLUMN sw_job_info.cron_expression IS 'Cron 表达式';
COMMENT ON COLUMN sw_job_info.status        IS '任务状态（NORMAL=启用 / PAUSED=停用）';
COMMENT ON COLUMN sw_job_info.concurrent    IS '是否允许并发（0=否 / 1=是）';
COMMENT ON COLUMN sw_job_info.misfire_policy IS 'Misfire 策略（0=忽略 / 1=立即触发 / 2=放弃）';
COMMENT ON COLUMN sw_job_info.description   IS '任务描述';
COMMENT ON COLUMN sw_job_info.bean_name     IS 'Spring Bean 名称（BEAN 类型必填）';
COMMENT ON COLUMN sw_job_info.bean_params   IS 'Bean 方法参数（JSON）';
COMMENT ON COLUMN sw_job_info.flow_def_key  IS '流程定义 Key（FLOW 类型必填）';
COMMENT ON COLUMN sw_job_info.form_data     IS '表单数据（JSON）';
COMMENT ON COLUMN sw_job_info.last_fire_time IS '上次执行时间';
COMMENT ON COLUMN sw_job_info.next_fire_time IS '下次计划执行时间';
COMMENT ON COLUMN sw_job_info.create_time   IS '创建时间';
COMMENT ON COLUMN sw_job_info.create_by     IS '创建人';
COMMENT ON COLUMN sw_job_info.update_time   IS '更新时间';
COMMENT ON COLUMN sw_job_info.update_by     IS '更新人';
COMMENT ON COLUMN sw_job_info.deleted       IS '逻辑删除标记（0=未删, 1=已删）';
COMMENT ON COLUMN sw_job_info.tenant_id     IS '租户 ID';
COMMENT ON COLUMN sw_job_info.version       IS '乐观锁版本号';

CREATE INDEX idx_sw_job_info_tenant_deleted ON sw_job_info (tenant_id, deleted);

CREATE TABLE sw_job_log (
    id              BIGINT      NOT NULL,
    job_id          BIGINT      NOT NULL,
    job_name        VARCHAR(128) NOT NULL,
    job_group       VARCHAR(128) NOT NULL DEFAULT 'DEFAULT',
    trigger_type    VARCHAR(16)  NOT NULL DEFAULT 'AUTO',
    job_params      TEXT         DEFAULT NULL,
    exec_status     VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    start_time      TIMESTAMP    DEFAULT NULL,
    end_time        TIMESTAMP    DEFAULT NULL,
    duration        BIGINT       DEFAULT NULL,
    result_msg      TEXT         DEFAULT NULL,
    exception_stack TEXT         DEFAULT NULL,
    create_time     TIMESTAMP    DEFAULT NULL,
    create_by       BIGINT       DEFAULT NULL,
    update_time     TIMESTAMP    DEFAULT NULL,
    update_by       BIGINT       DEFAULT NULL,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    version         BIGINT       DEFAULT NULL,
    PRIMARY KEY (id)
);

COMMENT ON TABLE  sw_job_log                IS '定时任务执行日志';
COMMENT ON COLUMN sw_job_log.id             IS '主键';
COMMENT ON COLUMN sw_job_log.job_id         IS '关联任务 ID（sw_job_info.id）';
COMMENT ON COLUMN sw_job_log.job_name       IS '任务名称（冗余）';
COMMENT ON COLUMN sw_job_log.job_group      IS '任务组（冗余）';
COMMENT ON COLUMN sw_job_log.trigger_type   IS '触发方式（AUTO=定时 / MANUAL=手动）';
COMMENT ON COLUMN sw_job_log.job_params     IS '任务参数快照';
COMMENT ON COLUMN sw_job_log.exec_status    IS '执行状态（RUNNING / SUCCESS / FAILED）';
COMMENT ON COLUMN sw_job_log.start_time     IS '执行开始时间';
COMMENT ON COLUMN sw_job_log.end_time       IS '执行结束时间';
COMMENT ON COLUMN sw_job_log.duration       IS '执行耗时（毫秒）';
COMMENT ON COLUMN sw_job_log.result_msg     IS '执行结果/异常信息';
COMMENT ON COLUMN sw_job_log.exception_stack IS '异常堆栈（仅失败时记录）';
COMMENT ON COLUMN sw_job_log.create_time    IS '创建时间';
COMMENT ON COLUMN sw_job_log.create_by      IS '创建人';
COMMENT ON COLUMN sw_job_log.update_time    IS '更新时间';
COMMENT ON COLUMN sw_job_log.update_by      IS '更新人';
COMMENT ON COLUMN sw_job_log.deleted        IS '逻辑删除标记（0=未删, 1=已删）';
COMMENT ON COLUMN sw_job_log.tenant_id      IS '租户 ID';
COMMENT ON COLUMN sw_job_log.version        IS '乐观锁版本号';

CREATE INDEX idx_sw_job_log_job_id ON sw_job_log (job_id);
CREATE INDEX idx_sw_job_log_tenant_deleted ON sw_job_log (tenant_id, deleted);

-- ======== V18（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V18: Refresh Token 存储表 (PostgreSQL)
-- ===================================================================
CREATE TABLE sys_refresh_token (
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(128) NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    revoked     SMALLINT     NOT NULL DEFAULT 0,
    create_time TIMESTAMP    DEFAULT NULL,
    create_by   BIGINT       DEFAULT NULL,
    update_time TIMESTAMP    DEFAULT NULL,
    update_by   BIGINT       DEFAULT NULL,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    version     BIGINT       DEFAULT NULL,
    PRIMARY KEY (id)
);

COMMENT ON TABLE  sys_refresh_token             IS 'Refresh Token 存储表';
COMMENT ON COLUMN sys_refresh_token.id          IS '主键';
COMMENT ON COLUMN sys_refresh_token.user_id     IS '关联用户 ID（sys_user.id）';
COMMENT ON COLUMN sys_refresh_token.token_hash  IS 'Refresh Token 的 SHA-256 哈希值';
COMMENT ON COLUMN sys_refresh_token.expires_at  IS '过期时间';
COMMENT ON COLUMN sys_refresh_token.revoked     IS '是否已撤销（0=有效, 1=已撤销）';
COMMENT ON COLUMN sys_refresh_token.create_time IS '创建时间';
COMMENT ON COLUMN sys_refresh_token.create_by   IS '创建人';
COMMENT ON COLUMN sys_refresh_token.update_time IS '更新时间';
COMMENT ON COLUMN sys_refresh_token.update_by   IS '更新人';
COMMENT ON COLUMN sys_refresh_token.deleted     IS '逻辑删除标记（0=未删, 1=已删）';
COMMENT ON COLUMN sys_refresh_token.tenant_id   IS '租户 ID';
COMMENT ON COLUMN sys_refresh_token.version     IS '乐观锁版本号';

CREATE INDEX idx_srt_user_tenant ON sys_refresh_token (user_id, tenant_id);
CREATE UNIQUE INDEX uk_srt_token_hash ON sys_refresh_token (token_hash);

-- ======== V19（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V19: 初始化大模型接入配置表 (PostgreSQL)
-- M07-F01 大模型管理：API Key 以 AesGcmCipher 密文（TEXT）落库
-- ===================================================================
CREATE TABLE sw_agent_model_config (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    protocol_type   VARCHAR(32) NOT NULL,
    base_url        VARCHAR(500) NOT NULL,
    model_name      VARCHAR(100) NOT NULL,
    api_key_cipher  TEXT,
    temperature     DECIMAL(4,2),
    max_tokens      INT,
    top_p           DECIMAL(4,2),
    timeout_seconds INT NOT NULL DEFAULT 30,
    retry_count     INT NOT NULL DEFAULT 0,
    enabled         SMALLINT NOT NULL DEFAULT 1,
    remark          VARCHAR(500),
    create_time     TIMESTAMP,
    create_by       VARCHAR(64),
    update_time     TIMESTAMP,
    update_by       VARCHAR(64),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_sw_agent_model_name ON sw_agent_model_config (tenant_id, name);
CREATE INDEX idx_sw_agent_model_tenant_deleted ON sw_agent_model_config (tenant_id, deleted);

COMMENT ON TABLE sw_agent_model_config IS 'M07 大模型接入配置';
COMMENT ON COLUMN sw_agent_model_config.protocol_type IS '协议类型：openai/ollama/other';
COMMENT ON COLUMN sw_agent_model_config.api_key_cipher IS 'API Key 密文（AesGcmCipher）';

-- ======== V20（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V20: 初始化工具沙箱白名单表 (PostgreSQL)
-- M07 Step3 工具沙箱：内部工具（Spring bean 反射调用）+ 外部工具（HTTP 调用）
-- 安全边界：工具名 → (beanName, methodName) / (url, httpMethod) 映射仅存于
-- 白名单表，管理员写入，LLM/用户不可在运行时新增条目
-- input_schema（JSON Schema 字符串，可能较长）：PG 用 TEXT（参照 V19 api_key_cipher 惯例）
-- ===================================================================
CREATE TABLE sw_agent_tool_internal (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500) NOT NULL,
    input_schema    TEXT,
    bean_name       VARCHAR(100) NOT NULL,
    method_name     VARCHAR(100) NOT NULL,
    enabled         SMALLINT NOT NULL DEFAULT 1,
    remark          VARCHAR(500),
    create_time     TIMESTAMP,
    create_by       VARCHAR(64),
    update_time     TIMESTAMP,
    update_by       VARCHAR(64),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE sw_agent_tool_external (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500) NOT NULL,
    input_schema    TEXT,
    url             VARCHAR(500) NOT NULL,
    http_method     VARCHAR(10) NOT NULL DEFAULT 'POST',
    timeout_seconds INT NOT NULL DEFAULT 30,
    enabled         SMALLINT NOT NULL DEFAULT 1,
    remark          VARCHAR(500),
    create_time     TIMESTAMP,
    create_by       VARCHAR(64),
    update_time     TIMESTAMP,
    update_by       VARCHAR(64),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_agent_tool_internal_tenant_deleted ON sw_agent_tool_internal (tenant_id, deleted);
CREATE INDEX idx_sw_agent_tool_external_tenant_deleted ON sw_agent_tool_external (tenant_id, deleted);

COMMENT ON TABLE sw_agent_tool_internal IS 'M07 工具沙箱-内部工具白名单（bean 反射调用）';
COMMENT ON COLUMN sw_agent_tool_internal.input_schema IS 'JSON Schema 字符串，描述入参结构';
COMMENT ON COLUMN sw_agent_tool_internal.bean_name IS 'Spring bean 名称（白名单值，安全边界）';
COMMENT ON COLUMN sw_agent_tool_internal.method_name IS '方法名（白名单值，约定签名 String→String）';
COMMENT ON TABLE sw_agent_tool_external IS 'M07 工具沙箱-外部 HTTP 工具白名单';
COMMENT ON COLUMN sw_agent_tool_external.input_schema IS 'JSON Schema 字符串，描述入参结构';
COMMENT ON COLUMN sw_agent_tool_external.url IS '白名单 URL（完整 URL，含路径）';
COMMENT ON COLUMN sw_agent_tool_external.http_method IS 'HTTP 方法：GET/POST/PUT';
COMMENT ON COLUMN sw_agent_tool_external.timeout_seconds IS '请求超时（秒），默认 30';

-- ======== V21（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V21: 初始化 Agent 会话主表 (PostgreSQL)
-- M07 Step4 F04 对话交互：会话主表
-- PK = 雪花 ID（Java 层生成，MyBatis-Plus IdType.ASSIGN_ID）
-- status varchar(20) 对齐 sw_bpm_instance 惯例（V8）
-- create_time 无 DEFAULT：由 Java 层（MetaObjectHandler）显式赋值（agent 模块 V19/V20 惯例）
-- ===================================================================
CREATE TABLE sw_agent_session (
    id                    BIGINT      NOT NULL PRIMARY KEY,
    agent_model_config_id BIGINT      NOT NULL,
    title                 VARCHAR(500),
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    create_time           TIMESTAMP   NOT NULL,
    create_by             VARCHAR(64),
    update_time           TIMESTAMP,
    update_by             VARCHAR(64),
    deleted               SMALLINT    NOT NULL DEFAULT 0,
    tenant_id             BIGINT      NOT NULL DEFAULT 0,
    version               BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_agent_session_user ON sw_agent_session (tenant_id, create_by, deleted);
CREATE INDEX idx_sw_agent_session_cfg  ON sw_agent_session (agent_model_config_id, deleted);

COMMENT ON TABLE sw_agent_session IS 'M07 Agent 会话主表（F04 对话交互）';
COMMENT ON COLUMN sw_agent_session.agent_model_config_id IS '大模型接入配置 id';
COMMENT ON COLUMN sw_agent_session.title IS '会话标题（自动生成留后续迭代，当前为 null）';
COMMENT ON COLUMN sw_agent_session.status IS '会话状态：ACTIVE（当前写死，永久有效）';

-- ======== V22（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V22: 初始化 Agent 会话消息明细表 (PostgreSQL)
-- M07 Step4 F04 对话交互：会话内消息明细
-- role：'USER' / 'ASSISTANT' / 'SYSTEM'（varchar + String，仓库惯例，不建 enum）
-- msg_order：本会话内消息顺序号（0-based，写入时由 Java 层计算 = 已有消息数）
-- content：大文本，PG 用 TEXT（agent 模块 V19/V20 惯例）
-- ===================================================================
CREATE TABLE sw_agent_message (
    id          BIGINT      NOT NULL PRIMARY KEY,
    session_id  BIGINT      NOT NULL,
    role        VARCHAR(20) NOT NULL,
    content     TEXT        NOT NULL,
    msg_order   INT         NOT NULL,
    create_time TIMESTAMP   NOT NULL,
    create_by   VARCHAR(64),
    update_time TIMESTAMP,
    update_by   VARCHAR(64),
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    version     BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_agent_msg_session ON sw_agent_message (session_id, msg_order, deleted);

COMMENT ON TABLE sw_agent_message IS 'M07 Agent 会话消息明细（F04 对话交互）';
COMMENT ON COLUMN sw_agent_message.session_id IS '所属会话 id（sw_agent_session）';
COMMENT ON COLUMN sw_agent_message.role IS '消息角色：USER/ASSISTANT/SYSTEM';
COMMENT ON COLUMN sw_agent_message.content IS '消息内容（大文本）';
COMMENT ON COLUMN sw_agent_message.msg_order IS '会话内顺序号（0-based，单调递增）';

-- ======== V23（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V23: 初始化 Agent 工具调用日志表 (PostgreSQL)
-- M07 Step4 F04 对话交互：每轮工具调用日志
-- tool_call_args / tool_call_result：JSON 字符串，可能较长，PG 用 TEXT
-- latency_ms：工具执行耗时（毫秒），由 FunctionToolCallback lambda 包装计时
-- ===================================================================
CREATE TABLE sw_agent_tool_call_log (
    id               BIGINT       NOT NULL PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    tool_name        VARCHAR(100) NOT NULL,
    tool_call_args   TEXT,
    tool_call_result TEXT,
    latency_ms       BIGINT,
    create_time      TIMESTAMP    NOT NULL,
    create_by        VARCHAR(64),
    update_time      TIMESTAMP,
    update_by        VARCHAR(64),
    deleted          SMALLINT     NOT NULL DEFAULT 0,
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_agent_tcl_session ON sw_agent_tool_call_log (session_id, deleted);

COMMENT ON TABLE sw_agent_tool_call_log IS 'M07 Agent 工具调用日志（F04 对话交互）';
COMMENT ON COLUMN sw_agent_tool_call_log.session_id IS '所属会话 id（sw_agent_session）';
COMMENT ON COLUMN sw_agent_tool_call_log.tool_name IS '工具名（白名单表 name）';
COMMENT ON COLUMN sw_agent_tool_call_log.tool_call_args IS '工具入参（JSON 字符串）';
COMMENT ON COLUMN sw_agent_tool_call_log.tool_call_result IS '工具返回（JSON 字符串）';
COMMENT ON COLUMN sw_agent_tool_call_log.latency_ms IS '工具执行耗时（毫秒）';

-- ======== V24（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V24: sw_agent_model_config 扩展多Key轮询/额度限流字段 (PostgreSQL)
-- M07-F01：group_key 归组 + sort 优先级 + locked_until 临时锁定 + quota_cooldown_seconds 冷却期
-- ===================================================================
ALTER TABLE sw_agent_model_config ADD COLUMN group_key VARCHAR(100);
ALTER TABLE sw_agent_model_config ADD COLUMN sort INT NOT NULL DEFAULT 0;
ALTER TABLE sw_agent_model_config ADD COLUMN locked_until TIMESTAMP;
ALTER TABLE sw_agent_model_config ADD COLUMN quota_cooldown_seconds INT NOT NULL DEFAULT 60;

CREATE INDEX idx_sw_agent_model_group ON sw_agent_model_config (tenant_id, group_key, sort);

COMMENT ON COLUMN sw_agent_model_config.group_key IS '多Key轮询候选分组标识，null=独立配置不参与轮询';
COMMENT ON COLUMN sw_agent_model_config.sort IS '组内优先级，数值越小优先级越高';
COMMENT ON COLUMN sw_agent_model_config.locked_until IS '限流临时锁定至该时间点，null或已过期=可用';
COMMENT ON COLUMN sw_agent_model_config.quota_cooldown_seconds IS '触发限流后的锁定冷却时长（秒），默认60';

-- ======== V25（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V25: 初始化 Agent 图定义表 (PostgreSQL)
-- M07-F02 Step7：图定义 CRUD + 版本 + 发布骨架（纯存储+管理，无执行语义）
-- 借鉴 sw-bpm V14（sw_bpm_process_def：process_key/name/def_version/status/graph_json），
-- 适配 agent 模块惯例（V19-V24：审计列在后、create_by VARCHAR(64)、大字段 TEXT）
-- 本表走 MyBatis-Plus 常规通道：@TableLogic + 租户拦截器自动处理 deleted/tenant_id
-- ===================================================================
CREATE TABLE sw_agent_graph_def (
    id           BIGINT NOT NULL PRIMARY KEY,
    graph_key    VARCHAR(100) NOT NULL,
    name         VARCHAR(200) NOT NULL,
    def_version  INT NOT NULL DEFAULT 1,
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    graph_json   TEXT,
    create_time  TIMESTAMP,
    create_by    VARCHAR(64),
    update_time  TIMESTAMP,
    update_by    VARCHAR(64),
    deleted      SMALLINT NOT NULL DEFAULT 0,
    tenant_id    BIGINT NOT NULL DEFAULT 0,
    version      BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_sw_agent_graph_key ON sw_agent_graph_def (tenant_id, graph_key);
CREATE INDEX idx_sw_agent_graph_tenant_deleted ON sw_agent_graph_def (tenant_id, deleted);

COMMENT ON TABLE  sw_agent_graph_def IS 'M07 Agent 图定义表（图设计器后端存储）';
COMMENT ON COLUMN sw_agent_graph_def.graph_key IS '图业务 key（服务端生成，发布后冻结）';
COMMENT ON COLUMN sw_agent_graph_def.name IS '图名称';
COMMENT ON COLUMN sw_agent_graph_def.def_version IS '定义版本号（每次发布递增）';
COMMENT ON COLUMN sw_agent_graph_def.status IS '状态：DRAFT（草稿）/ PUBLISHED（已发布）';
COMMENT ON COLUMN sw_agent_graph_def.graph_json IS '图 JSON 文档（ProcessGraph 序列化，config/style 不透明透传）';

-- ======== V26（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V26: 智能体菜单矫正为目录 + 图定义管理二级菜单（M07-F02 Step9 前端入口）
--
-- 现场核验（非训练记忆）：
--   · V6 已 seed「智能体」(id=7) 为叶子菜单（menu_type=1,
--     component='agent/views/AgentHome', permission='agent:view'）——
--     即真实 sys_menu 中「智能体」占位已存在，本迁移按方案 §3.1
--     「已有同名真实目录则复用其结构层级」处理：仿 V11 对「系统管理」(id=1)
--     的矫正先例，将 id=7 从叶子菜单矫正为目录，再挂二级「图定义管理」。
--   · 权限-菜单关联为独立关联表 sys_role_menu（V5 唯一索引
--     uk_sys_role_menu_tenant）；V6 决策「不 seed sys_role_menu
--     （超管旁路，本环既定）」沿用，本迁移同样不 seed。
--   · 不新增权限码：目录沿用 V6 既有 agent:view；二级菜单沿用 Step1/7/8
--     既有 agent:model:view（列表只读）；新建/发布/删除等按钮级操作复用
--     同一份 agent:model:manage（前端 hasPerm 控制按钮显隐）。
--   · 图设计器画布页（agent/graph-designer/:id）为参数化静态路由
--     （仿 form-designer 先例），不占菜单节点，由列表页按钮跳转进入。
-- ===================================================================

-- 1. 将「智能体」(id=7) 从叶子菜单矫正为目录（对齐 V11 对「系统管理」的矫正，
--    使子菜单在侧边栏可展开；component 置空后由子菜单自己的 component 渲染）
UPDATE sys_menu SET menu_type = 0, component = NULL WHERE id = 7;

-- 2. 智能体 → 图定义管理
--    id 使用 15（1-14 已被 V6/V10/V15 占用，15+ 空闲）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (15, current_timestamp, current_timestamp, 0, 0, 7, 'AgentGraphDef', '图定义管理', false, 1, 'graph-def', 'agent/views/GraphDefList', 'agent:model:view', 'Share', 10);

-- ======== V27（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V27: 初始化 Agent 图执行记录表 (PostgreSQL)
-- M07 Step12 图执行历史持久化：执行摘要主表
-- status：'RUNNING'（执行前建行）→ 'SUCCESS' / 'FAILED'（执行后回写终态；
--   成功与失败两类路径均落库，区别于 F04 只写成功分支）
-- input/result_text/error_message：大文本，PG 用 TEXT（agent 模块 V19/V22 惯例）
-- result_text：最终输出列（命名避开 output 保留字——租户拦截器 JSqlParser
--   解析 UPDATE SET 子句时 output 为非法 token，实测踩坑，见执行回执 §6）
-- error_category：错误分类（STEP_LIMIT/LOOP_LIMIT/UNDEFINED_VARIABLE/
--   CONDITION_NO_MATCH/TOPOLOGY_INVALID/MODEL_CALL_FAILED/TOOL_CALL_FAILED/
--   UNKNOWN），由解释器 GraphExecutionException 携带（V27 起 Java 层分类，
--   失败记录完整可查，不靠文本子串匹配）
-- latency_ms：整次执行耗时（毫秒，Service 层 currentTimeMillis 起止差）
-- graph_def_version：执行时图定义版本快照（发布锚点，图可后续再发布）
-- ===================================================================
CREATE TABLE sw_agent_graph_execution (
    id                BIGINT      NOT NULL PRIMARY KEY,
    graph_def_id      BIGINT      NOT NULL,
    graph_def_version INT         NOT NULL,
    status            VARCHAR(20) NOT NULL,
    input             TEXT,
    result_text       TEXT,
    error_category    VARCHAR(50),
    error_message     TEXT,
    latency_ms        BIGINT,
    create_time       TIMESTAMP   NOT NULL,
    create_by         VARCHAR(64),
    update_time       TIMESTAMP,
    update_by         VARCHAR(64),
    deleted           SMALLINT    NOT NULL DEFAULT 0,
    tenant_id         BIGINT      NOT NULL DEFAULT 0,
    version           BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_agent_gexec_graph ON sw_agent_graph_execution (graph_def_id, deleted);
CREATE INDEX idx_sw_agent_gexec_time  ON sw_agent_graph_execution (tenant_id, create_time, deleted);

COMMENT ON TABLE sw_agent_graph_execution IS 'M07 Agent 图执行记录（Step12 执行历史）';
COMMENT ON COLUMN sw_agent_graph_execution.graph_def_id IS '图定义 id（sw_agent_graph_def）';
COMMENT ON COLUMN sw_agent_graph_execution.graph_def_version IS '执行时图定义版本快照';
COMMENT ON COLUMN sw_agent_graph_execution.status IS '执行状态：RUNNING/SUCCESS/FAILED';
COMMENT ON COLUMN sw_agent_graph_execution.input IS '执行入参文本';
COMMENT ON COLUMN sw_agent_graph_execution.result_text IS '最终输出（成功时）';
COMMENT ON COLUMN sw_agent_graph_execution.error_category IS '错误分类（解释器携带，非文本匹配）';
COMMENT ON COLUMN sw_agent_graph_execution.error_message IS '失败原因摘要（不含明文 API Key）';
COMMENT ON COLUMN sw_agent_graph_execution.latency_ms IS '整次执行耗时（毫秒）';

-- ======== V28（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V28: 初始化 Agent 图执行节点明细表 (PostgreSQL)
-- M07 Step12 图执行历史持久化：节点级执行轨迹
-- node_seq：本次执行内全局访问步序（1-based，节点出队即分配；END 也占一条）
-- branch_id：并行分支标识（FORK 扇出按出边在 elements 中的出现顺序追加下标，
--   如 "0" / "0-1" / "0-2"；非 FORK 路径恒为 "0"；JOIN 汇合后沿用最后到达
--   分支的 branch_id；同一分支内 LOOP 迭代 = 多条 node_seq 递增的记录）
-- node_latency_ms：节点级耗时（出队到本步路由完成，毫秒）
-- variable_snapshot：该节点执行后的变量表快照（JSON，Map<String,String>）
-- ===================================================================
CREATE TABLE sw_agent_graph_execution_node (
    id                BIGINT       NOT NULL PRIMARY KEY,
    execution_id      BIGINT       NOT NULL,
    node_seq          INT          NOT NULL,
    branch_id         VARCHAR(64)  NOT NULL,
    node_id           VARCHAR(100) NOT NULL,
    node_type         VARCHAR(20)  NOT NULL,
    node_latency_ms   BIGINT,
    variable_snapshot TEXT,
    create_time       TIMESTAMP    NOT NULL,
    create_by         VARCHAR(64),
    update_time       TIMESTAMP,
    update_by         VARCHAR(64),
    deleted           SMALLINT     NOT NULL DEFAULT 0,
    tenant_id         BIGINT       NOT NULL DEFAULT 0,
    version           BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_agent_genode_exec ON sw_agent_graph_execution_node (execution_id, node_seq, deleted);

COMMENT ON TABLE sw_agent_graph_execution_node IS 'M07 Agent 图执行节点明细（Step12 执行轨迹）';
COMMENT ON COLUMN sw_agent_graph_execution_node.execution_id IS '所属执行记录 id（sw_agent_graph_execution）';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_seq IS '本次执行内全局访问步序（1-based）';
COMMENT ON COLUMN sw_agent_graph_execution_node.branch_id IS '并行分支标识（FORK 按出边顺序追加下标）';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_id IS '图节点 id';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_type IS '节点类型（START/LLM/TOOL/CONDITION/LOOP/FORK/JOIN/END）';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_latency_ms IS '节点级耗时（毫秒）';
COMMENT ON COLUMN sw_agent_graph_execution_node.variable_snapshot IS '该节点执行后的变量表快照（JSON）';

-- ======== V29（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V29: job / storage 生产菜单 seed（checklist-gap-hardening 第一批）
--
-- 背景：定时任务（M10-F03-01）与文件存储（M10-F06-01）功能代码与
-- 前端页面全链完整且已 PASSED，但生产菜单树（V6/V10/V15/V26）无对应
-- 行，仅 dev:mock 的 seeds.ts 注册，正式环境无入口（known-issues
-- I43/I44）。本迁移仿 V6/V26 先例补齐，层级/权限/路径/图标与
-- seeds.ts 中 storage/job 节点结构逐一对应：
--   · storage 顶级菜单（menu_type=1，仿 V6 顶级叶子菜单行）
--   · job 顶级目录（menu_type=0，component=NULL，仿 V6「低代码」目录行）
--   · job → 任务管理 / 执行日志 二级菜单（仿 V10/V15/V26 子菜单行）
--
-- 约束：
--   · id 使用 16-19（1-15 已被 V6/V10/V15/V26 占用，16+ 空闲）
--   · 顶级 parent_id 写 0（与 V6/V15 顶级菜单行实际写法一致；
--     后端 SysMenuServiceImpl.buildTree 将 parent_id=0 与 NULL
--     同等视为根节点，toVo 亦将两者统一转为 null）
--   · component = NULL 表示目录（前端据此判定；后端 toVo 对
--     menu_type=0/2 的行同样将 component 置 null）
--   · 不 seed sys_role_menu（超管旁路，V6 决策沿用）
--   · hidden 列类型为 boolean，使用 false 字面量（V6 惯例）
-- ===================================================================

-- 1. 文件存储（顶级菜单）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (16, current_timestamp, current_timestamp, 0, 0, 0, 'Storage', '文件管理', false, 1, 'storage', 'storage/views/StorageList', 'storage:view', 'FolderOpened', 80);

-- 2. 定时任务（顶级目录）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (17, current_timestamp, current_timestamp, 0, 0, 0, 'Job', '定时任务', false, 0, 'job', NULL, 'job:view', 'Clock', 90);

-- 3. 定时任务 → 任务管理
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (18, current_timestamp, current_timestamp, 0, 0, 17, 'JobList', '任务管理', false, 1, 'job/list', 'job/views/JobList', 'job:list', 'List', 10);

-- 4. 定时任务 → 执行日志
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (19, current_timestamp, current_timestamp, 0, 0, 17, 'JobLog', '执行日志', false, 1, 'job/log', 'job/views/JobLog', 'job:log', 'Document', 20);

-- ======== V30（来源: sw-bootstrap，副本 1 处已去重） ========
-- Smart-WorkFlow 角色部门关联表（H2/PostgreSQL 双端逐字一致）
-- 数据范围 CUSTOM（自定义部门集合）时，角色可见部门由本表承载；
-- 结构/风格对齐 V1 sys_user_role（同构关联表，含租户列与逻辑删除）。
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
create unique index uk_sys_role_dept on sys_role_dept (role_id, dept_id);

-- ======== V31（来源: sw-bootstrap，副本 1 处已去重） ========
-- P24/I49：普通 admin seed 与 job/storage 方法权限。
-- 历史冲突必须显式失败：条件插入会触发既有主键/唯一约束，不得静默跳过、覆盖或夺取既有角色。
INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version,
                      name, code, sort, status, data_scope, built_in, remark)
SELECT 2, current_timestamp, current_timestamp, 0, 0, 0,
       '管理员', 'admin', 10, 1, 0, false, 'P24 V31 conflict sentinel'
WHERE EXISTS (SELECT 1 FROM sys_role WHERE code = 'admin' AND deleted = 0)
   OR EXISTS (SELECT 1 FROM sys_role WHERE id = 2 AND deleted = 0);

INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version,
                      name, code, sort, status, data_scope, built_in, remark)
VALUES (2, current_timestamp, current_timestamp, 0, 0, 0,
        '管理员', 'admin', 10, 1, 0, false, '系统初始化普通管理员角色');

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 200, current_timestamp, current_timestamp, 0, 0, 18, 'JobCreate', '任务新增', 2, '', '', 'job:create', '', 30, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 200);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 201, current_timestamp, current_timestamp, 0, 0, 18, 'JobUpdate', '任务修改', 2, '', '', 'job:update', '', 31, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 201);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 202, current_timestamp, current_timestamp, 0, 0, 18, 'JobDelete', '任务删除', 2, '', '', 'job:delete', '', 32, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 202);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 203, current_timestamp, current_timestamp, 0, 0, 18, 'JobPause', '任务暂停', 2, '', '', 'job:pause', '', 33, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 203);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 204, current_timestamp, current_timestamp, 0, 0, 18, 'JobResume', '任务恢复', 2, '', '', 'job:resume', '', 34, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 204);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 205, current_timestamp, current_timestamp, 0, 0, 18, 'JobTrigger', '任务触发', 2, '', '', 'job:trigger', '', 35, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 205);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 206, current_timestamp, current_timestamp, 0, 0, 16, 'StorageUpload', '文件上传', 2, '', '', 'storage:upload', '', 30, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 206);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 207, current_timestamp, current_timestamp, 0, 0, 16, 'StorageDelete', '文件删除', 2, '', '', 'storage:delete', '', 31, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 207);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 208, current_timestamp, current_timestamp, 0, 0, 16, 'StorageDownload', '文件下载', 2, '', '', 'storage:download', '', 32, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 208);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT COALESCE((SELECT MAX(id) FROM sys_role_menu), 0) + ROW_NUMBER() OVER (ORDER BY m.id), current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.code = 'admin' AND r.deleted = 0);

-- ======== V32（来源: sw-bootstrap，副本 1 处已去重） ========
CREATE TABLE sys_user_post (
    id BIGINT NOT NULL,
    create_time TIMESTAMP,
    create_by BIGINT,
    update_time TIMESTAMP,
    update_by BIGINT,
    deleted SMALLINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL
);
CREATE UNIQUE INDEX uk_sys_user_post ON sys_user_post (tenant_id, user_id, post_id, deleted);
CREATE INDEX idx_sys_user_post_user ON sys_user_post (tenant_id, user_id, deleted);
CREATE INDEX idx_sys_user_post_post ON sys_user_post (tenant_id, post_id, deleted);

-- ======== V33（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V33: 智能体 → 大模型管理二级菜单 + 按钮级权限（P5 / M07-F01）
--
-- 现场核验（非训练记忆）：
--   · 菜单可达性审计：V26 仅挂「图定义管理」(id=15, permission=agent:model:view)，
--     「智能体」(id=7) 目录下无「大模型管理」菜单；agent:model:manage /
--     agent:model:test 两个按钮权限无任何 seed（grep 全迁移目录仅 V26 注释提及，
--     无 menu_type=2 行），生产环境无入口 → 本迁移为最小可达性 seed。
--   · 后端权限契约（现场）：AgentModelController 列表/详情 @ss.hasPermi
--     ('agent:model:view')、新建/编辑/删除 ('agent:model:manage')、
--     连通性测试 ('agent:model:test')，与本节菜单权限一一闭合。
--   · 按钮级 seed 先例：V31 以 menu_type=2 + path/component='' 的形态为
--     job/storage 补 9 枚方法权限按钮（id=200-208），本迁移沿用该形态。
--   · 不 seed sys_role_menu（V6/V26 决策「不自动授予普通角色，超管旁路」沿用；
--     普通 admin 角色是否授按钮权限由管理员在菜单管理中自行配置）。
--   · 字段清单与 V6/V10/V15/V26/V29 完全一致（含 hidden 列，V5 起存在）。
-- ===================================================================

-- 1. 智能体 → 大模型管理（二级菜单，仿 V26 id=15 图定义管理写法）
--    id 使用 209（1-208 已被 V6/V10/V15/V26/V29/V31 占用；V2 旧 10-16/100-112
--    已被 V6 DELETE 清理，V31 按钮从 200 起，209 为空闲最小值）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (209, current_timestamp, current_timestamp, 0, 0, 7, 'AgentModel', '大模型管理', false, 1, 'model', 'agent/views/ModelList', 'agent:model:view', 'Cpu', 20);

-- 2. 智能体 → 大模型管理 → 新建/编辑/删除（按钮级，复用 agent:model:manage）
--    字段清单与列顺序对齐 V31 按钮行先例（hidden 在列清单末尾）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 210, current_timestamp, current_timestamp, 0, 0, 209, 'AgentModelManage', '模型新建/编辑/删除', 2, '', '', 'agent:model:manage', '', 30, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 210);

-- 3. 智能体 → 大模型管理 → 连通性测试（按钮级，agent:model:test）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 211, current_timestamp, current_timestamp, 0, 0, 209, 'AgentModelTest', '模型连通性测试', 2, '', '', 'agent:model:test', '', 31, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 211);

-- ======== V34（来源: sw-bootstrap，副本 1 处已去重） ========
-- P28/I36（D112）：用户组维护与成员绑定基础闭环 —— 主表 + 成员关系表。
-- 语义：租户内扁平虚拟用户组（无层级/无负责人），业务标识租户内稳定唯一；
--       用户-组多对多成员关系；全部逻辑删除；双方言（H2/PostgreSQL）逐字一致。
-- 唯一性采用 (tenant_id, deleted) 复合模式（对齐 V13/V32 逻辑删除唯一语义）：
--   同一租户内同一业务标识最多一条 deleted=0 生效行；deleted=1 历史行允许同 key 共存。

CREATE TABLE sys_user_group (
    id BIGINT NOT NULL,
    create_time TIMESTAMP,
    create_by BIGINT,
    update_time TIMESTAMP,
    update_by BIGINT,
    deleted SMALLINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    group_code VARCHAR(64) NOT NULL,
    group_name VARCHAR(64) NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    remark VARCHAR(255)
);
CREATE UNIQUE INDEX uk_sys_user_group_code ON sys_user_group (tenant_id, group_code, deleted);
CREATE INDEX idx_sys_user_group_name ON sys_user_group (tenant_id, group_name, deleted);
CREATE INDEX idx_sys_user_group_status ON sys_user_group (tenant_id, status, deleted);

CREATE TABLE sys_user_group_member (
    id BIGINT NOT NULL,
    create_time TIMESTAMP,
    create_by BIGINT,
    update_time TIMESTAMP,
    update_by BIGINT,
    deleted SMALLINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL
);
CREATE UNIQUE INDEX uk_sys_user_group_member ON sys_user_group_member (tenant_id, group_id, user_id, deleted);
CREATE INDEX idx_sys_user_group_member_group ON sys_user_group_member (tenant_id, group_id, deleted);
CREATE INDEX idx_sys_user_group_member_user ON sys_user_group_member (tenant_id, user_id, deleted);

-- ======== V35（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V35: Agent Token Usage 统计字段 (PostgreSQL)
-- M07-F04-02 Agent Token 使用统计可观测闭环
-- 为以下表添加 input_tokens / output_tokens 字段：
--   1. sw_agent_message        — 会话消息级 Token 记录
--   2. sw_agent_graph_execution — 图执行级 Token 汇总
--   3. sw_agent_graph_execution_node — 图执行节点级 Token 明细
-- total_tokens 不单独存储，由 input + output 计算得出
-- ===================================================================

-- 1. sw_agent_message: 会话消息级 Token
ALTER TABLE sw_agent_message
    ADD COLUMN input_tokens  BIGINT,
    ADD COLUMN output_tokens BIGINT;

COMMENT ON COLUMN sw_agent_message.input_tokens IS '供应商返回的输入 Token 数（未知时为 NULL，不为 0）';
COMMENT ON COLUMN sw_agent_message.output_tokens IS '供应商返回的输出 Token 数（未知时为 NULL，不为 0）';

-- 2. sw_agent_graph_execution: 图执行级 Token 汇总
ALTER TABLE sw_agent_graph_execution
    ADD COLUMN input_tokens  BIGINT,
    ADD COLUMN output_tokens BIGINT;

COMMENT ON COLUMN sw_agent_graph_execution.input_tokens IS '本次图执行全部 LLM 节点输入 Token 汇总（未知时不参与计算）';
COMMENT ON COLUMN sw_agent_graph_execution.output_tokens IS '本次图执行全部 LLM 节点输出 Token 汇总（未知时不参与计算）';

-- 3. sw_agent_graph_execution_node: 图执行节点级 Token
ALTER TABLE sw_agent_graph_execution_node
    ADD COLUMN input_tokens  BIGINT,
    ADD COLUMN output_tokens BIGINT;

COMMENT ON COLUMN sw_agent_graph_execution_node.input_tokens IS '该节点 LLM 调用的输入 Token（非 LLM 节点或供应商未返回时为 NULL）';
COMMENT ON COLUMN sw_agent_graph_execution_node.output_tokens IS '该节点 LLM 调用的输出 Token（非 LLM 节点或供应商未返回时为 NULL）';

-- ======== V36（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V36: 初始化 Agent 图调试会话及节点明细表 (PostgreSQL)
-- M07-F02-04 图单步调试闭环：调试会话主表 + 节点轨迹明细
-- sw_agent_graph_debug_session
--   graph_def_id/graph_def_version：调试时图定义快照锚点（同 V27 执行表惯例）
--   graph_json：调试时图 JSON 快照（TEXT，含 elements/edges）
--   status：PAUSED（断点暂停）/ COMPLETED / FAILED / STOPPED（用户停止）/ EXPIRED（TTL 过期）
--   input/breakpoints/state_json/result_text/error_message：大文本，PG 用 TEXT（agent 模块 V19/V22/V27 惯例）
--   breakpoints：断点列表（JSON 数组，元素为 nodeId）
--   state_json：可恢复解释器状态（JSON，含 variables/activePoints/loopCounts/joinCounts/nextNodeIds 等）
--   latency_ms/expires_at/input_tokens/output_tokens：同 V27/V35 执行表语义，调试会话维度
--   expires_at：会话过期时间（TTL，到期后置为 EXPIRED）
-- sw_agent_graph_debug_node
--   debug_session_id：归属调试会话（sw_agent_graph_debug_session）
--   node_seq/branch_id/node_latency_ms/variable_snapshot/input_tokens/output_tokens：同 V28 节点表语义
-- ===================================================================
CREATE TABLE sw_agent_graph_debug_session (
    id                BIGINT      NOT NULL PRIMARY KEY,
    graph_def_id      BIGINT      NOT NULL,
    graph_def_version INT         NOT NULL,
    graph_json        TEXT,
    status            VARCHAR(20) NOT NULL,
    input             TEXT,
    breakpoints       TEXT,
    state_json        TEXT,
    result_text       TEXT,
    error_category    VARCHAR(50),
    error_message     TEXT,
    latency_ms        BIGINT,
    expires_at        TIMESTAMP,
    input_tokens      BIGINT,
    output_tokens     BIGINT,
    create_time       TIMESTAMP   NOT NULL,
    create_by         VARCHAR(64),
    update_time       TIMESTAMP,
    update_by         VARCHAR(64),
    deleted           SMALLINT    NOT NULL DEFAULT 0,
    tenant_id         BIGINT      NOT NULL DEFAULT 0,
    version           BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_gexec_debug_graph   ON sw_agent_graph_debug_session (graph_def_id, deleted);
CREATE INDEX idx_gexec_debug_expires ON sw_agent_graph_debug_session (expires_at);

CREATE TABLE sw_agent_graph_debug_node (
    id                BIGINT       NOT NULL PRIMARY KEY,
    debug_session_id  BIGINT       NOT NULL,
    node_seq          INT          NOT NULL,
    branch_id         VARCHAR(64)  NOT NULL,
    node_id           VARCHAR(100) NOT NULL,
    node_type         VARCHAR(20)  NOT NULL,
    node_latency_ms   BIGINT,
    variable_snapshot TEXT,
    input_tokens      BIGINT,
    output_tokens     BIGINT,
    create_time       TIMESTAMP    NOT NULL,
    create_by         VARCHAR(64),
    update_time       TIMESTAMP,
    update_by         VARCHAR(64),
    deleted           SMALLINT     NOT NULL DEFAULT 0,
    tenant_id         BIGINT       NOT NULL DEFAULT 0,
    version           BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_genode_debug ON sw_agent_graph_debug_node (debug_session_id, node_seq, deleted);

COMMENT ON TABLE sw_agent_graph_debug_session IS 'M07-F02-04 Agent 图调试会话（单步调试闭环）';
COMMENT ON COLUMN sw_agent_graph_debug_session.graph_def_id IS '图定义 id（sw_agent_graph_def）';
COMMENT ON COLUMN sw_agent_graph_debug_session.graph_def_version IS '调试时图定义版本快照';
COMMENT ON COLUMN sw_agent_graph_debug_session.graph_json IS '调试时图 JSON 快照';
COMMENT ON COLUMN sw_agent_graph_debug_session.status IS '调试状态：PAUSED/COMPLETED/FAILED/STOPPED/EXPIRED';
COMMENT ON COLUMN sw_agent_graph_debug_session.input IS '调试入参文本';
COMMENT ON COLUMN sw_agent_graph_debug_session.breakpoints IS '断点列表（JSON 数组，元素为 nodeId）';
COMMENT ON COLUMN sw_agent_graph_debug_session.state_json IS '可恢复解释器状态（JSON，含 variables/activePoints/loopCounts/joinCounts/next 等）';
COMMENT ON COLUMN sw_agent_graph_debug_session.result_text IS '最终输出（成功完成时）';
COMMENT ON COLUMN sw_agent_graph_debug_session.error_category IS '错误分类（解释器携带）';
COMMENT ON COLUMN sw_agent_graph_debug_session.error_message IS '失败原因摘要（不含明文 API Key）';
COMMENT ON COLUMN sw_agent_graph_debug_session.latency_ms IS '整次调试耗时（毫秒）';
COMMENT ON COLUMN sw_agent_graph_debug_session.expires_at IS '会话过期时间（TTL，到期后置为 EXPIRED）';
COMMENT ON COLUMN sw_agent_graph_debug_session.input_tokens IS '本次调试全部 LLM 节点输入 Token 汇总';
COMMENT ON COLUMN sw_agent_graph_debug_session.output_tokens IS '本次调试全部 LLM 节点输出 Token 汇总';

COMMENT ON TABLE sw_agent_graph_debug_node IS 'M07-F02-04 Agent 图调试节点明细（单步调试轨迹）';
COMMENT ON COLUMN sw_agent_graph_debug_node.debug_session_id IS '所属调试会话 id（sw_agent_graph_debug_session）';
COMMENT ON COLUMN sw_agent_graph_debug_node.node_seq IS '本次调试内全局访问步序（1-based）';
COMMENT ON COLUMN sw_agent_graph_debug_node.branch_id IS '并行分支标识（FORK 按出边顺序追加下标）';
COMMENT ON COLUMN sw_agent_graph_debug_node.node_id IS '图节点 id';
COMMENT ON COLUMN sw_agent_graph_debug_node.node_type IS '节点类型（START/LLM/TOOL/CONDITION/LOOP/FORK/JOIN/END）';
COMMENT ON COLUMN sw_agent_graph_debug_node.node_latency_ms IS '节点级耗时（毫秒）';
COMMENT ON COLUMN sw_agent_graph_debug_node.variable_snapshot IS '该节点执行后的变量表快照（JSON）';
COMMENT ON COLUMN sw_agent_graph_debug_node.input_tokens IS '该节点 LLM 输入 Token';
COMMENT ON COLUMN sw_agent_graph_debug_node.output_tokens IS '该节点 LLM 输出 Token';

-- ======== V37（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V37: 智能体 → 工具管理二级菜单 + 按钮级权限（P48 / M07-F03-02）
--
-- 现场核验（非训练记忆）：
--   · V33 已在智能体目录（id=7）下挂「大模型管理」（id=209），
--     按钮级 id=210/211；V37 沿用同目录，页面 id=212，按钮 id=213。
--   · 后端权限契约：AgentToolConfigController
--     列表/详情 @ss.hasPermi('agent:tool:view')、
--     新建/编辑/删除/启停 @ss.hasPermi('agent:tool:manage')，
--     与本节菜单权限一一闭合。
--   · 不 seed sys_role_menu（沿用 V6/V26/V33 决策：超管旁路，
--     普通角色由管理员在菜单管理中自行配置）。
-- ===================================================================

-- 1. 智能体 → 工具管理（二级菜单，仿 V33 id=209 写法）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (212, current_timestamp, current_timestamp, 0, 0, 7, 'AgentTool', '工具管理', false, 1, 'tool', 'agent/views/ToolList', 'agent:tool:view', 'SetUp', 30);

-- 2. 智能体 → 工具管理 → 新建/编辑/删除/启停（按钮级，agent:tool:manage）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 213, current_timestamp, current_timestamp, 0, 0, 212, 'AgentToolManage', '工具新建/编辑/删除/启停', 2, '', '', 'agent:tool:manage', '', 1, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 213);

-- ======== V38（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V38: 消息模板表 + 通知菜单目录化（P36 / M05-F02-01）
--
-- 现场核验（非训练记忆）：
--   · V6 已 seed「通知」(id=6) 为叶子菜单（menu_type=1,
--     component='notify/views/NotifyHome', permission='notify:view'）——
--     本迁移仿 V11/V26 先例将 id=6 矫正为目录，再挂两个二级菜单
--     （收件箱 + 消息模板），原 NotifyHome 由收件箱二级菜单承载，
--     路径/component/permission 保持原值不变（不破坏既有可达性）。
--   · 菜单 id 已用至 213（V29 200-208 / V33 209-211 / V37 212-213）；
--     本迁移使用 215/216/217（214 在 V37 注释中被提及但从未占用，
--     为免歧义跳过）。
--   · 不 seed sys_role_menu（沿用 V6/V26/V33/V37 决策：超管旁路）。
--   · 表约定对齐 V9 sw_notify_message：前缀 sw_notify_、8 基列在前、
--     bigint 主键（ASSIGN_ID 雪花）、无真实外键。
--   · 租户内模板代码唯一：仿 V13 sys_role(tenant_id, code, deleted)
--     复合唯一先例，显式含 tenant_id 与 deleted（支持软删重建）。
-- 注意：本文件注释中的占位符示例使用 ＄｛var｝ 全角写法，
--       避免 Flyway placeholder 解析把 ＄{var} 当作迁移变量。
-- ===================================================================

-- ==================== 1. 消息模板表 ====================
CREATE TABLE sw_notify_template (
    id                BIGINT NOT NULL PRIMARY KEY,
    create_time       TIMESTAMP,
    create_by         VARCHAR(64),
    update_time       TIMESTAMP,
    update_by         VARCHAR(64),
    deleted           SMALLINT NOT NULL DEFAULT 0,
    tenant_id         BIGINT NOT NULL DEFAULT 0,
    version           BIGINT NOT NULL DEFAULT 0,
    template_code     VARCHAR(100) NOT NULL,
    name              VARCHAR(100) NOT NULL,
    title_template    VARCHAR(200) NOT NULL,
    content_template  TEXT NOT NULL,
    enabled           SMALLINT NOT NULL DEFAULT 1,
    remark            VARCHAR(500)
);

COMMENT ON TABLE sw_notify_template IS 'M05 消息模板（＄{var} 简单占位符渲染，P36/M05-F02-01）';
COMMENT ON COLUMN sw_notify_template.template_code IS '稳定模板代码，同租户唯一，发送与外部调用的标识';
COMMENT ON COLUMN sw_notify_template.title_template IS '标题模板，支持 ＄{var} 占位符';
COMMENT ON COLUMN sw_notify_template.content_template IS '正文模板，支持 ＄{var} 占位符';
COMMENT ON COLUMN sw_notify_template.enabled IS '1=启用 0=停用（停用不得预览/发送）';

-- 同租户模板代码唯一（含 deleted 支持软删重建，V13 先例）
CREATE UNIQUE INDEX uk_sw_notify_template_tenant_code ON sw_notify_template (tenant_id, template_code, deleted);
CREATE INDEX idx_sw_notify_template_tenant_deleted ON sw_notify_template (tenant_id, deleted);

-- ==================== 2. 「通知」叶子矫正为目录（仿 V11/V26） ====================
UPDATE sys_menu SET menu_type = 0, component = NULL WHERE id = 6;

-- ==================== 3. 通知 → 收件箱（承载原 NotifyHome） ====================
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (215, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyInbox', '收件箱', false, 1, 'inbox', 'notify/views/NotifyHome', 'notify:view', 'Bell', 10);

-- ==================== 4. 通知 → 消息模板（管理页） ====================
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (216, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyTemplate', '消息模板', false, 1, 'template', 'notify/views/NotifyTemplateList', 'notify:template:view', 'Tickets', 20);

-- ==================== 5. 消息模板按钮级权限（新建/编辑/删除/启停/预览/发送） ====================
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 217, current_timestamp, current_timestamp, 0, 0, 216, 'NotifyTemplateManage', '模板新建/编辑/删除/启停/预览/发送', 2, '', '', 'notify:template:manage', '', 1, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 217);

-- ======== V39（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V39: 通知批量发送页面与独立权限（M05-F01-01）— PostgreSQL 方言
--
-- 仅注册生产菜单/按钮资源；普通角色授权仍由管理员通过角色菜单管理完成。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 218, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyBatchSend', '批量发送', false, 1,
       'batch-send', 'notify/views/NotifyBatchSend', 'notify:batch:send', 'Promotion', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 218);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 219, current_timestamp, current_timestamp, 0, 0, 218, 'NotifyBatchSendAction', '批量发送操作', 2,
       '', '', 'notify:batch:send', '', 1, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 219);

-- ======== V40（来源: sw-basic/sw-basic-iot，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: IoT 设备与控制命令表 (PostgreSQL)
-- ===================================================================
-- 最小设备控制链：设备注册 / 状态 / 控制命令 / 执行结果。
-- 约定与 H2 版本对齐（8 基列 + bigint PK + 无外键）。
-- ===================================================================

create table sw_iot_device (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    device_key        varchar(64)     not null,
    name              varchar(200)    not null,
    device_type       varchar(50),
    status            varchar(20)     not null default 'OFFLINE',
    last_online_time  timestamp
);

create index idx_sw_iot_device_key on sw_iot_device (tenant_id, device_key);

create table sw_iot_device_command (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    device_key        varchar(64)     not null,
    command_key       varchar(64)     not null,
    payload           varchar(1000),
    status            varchar(20)     not null default 'PENDING',
    result            varchar(1000),
    approval_biz_id   varchar(64)
);

create index idx_sw_iot_cmd_device on sw_iot_device_command (tenant_id, device_key);
create index idx_sw_iot_cmd_biz on sw_iot_device_command (tenant_id, approval_biz_id);

-- ======== V42（来源: sw-bootstrap，副本 1 处已去重） ========
-- IoT 设备身份升级：从单一 deviceKey 收敛到 productId + deviceName 复合身份
-- 新增字段：product_id, device_name, tencent_status（设备表）
-- 新增字段：product_id, device_name, command_type, semantic_mode, idempotent_key, expiry_time, retry_count, last_error, tencent_request_id, client_token, device_output（命令表）

-- ============================================================
-- sw_iot_device 表升级
-- ============================================================

-- 新增 product_id 字段（腾讯云产品 ID）
ALTER TABLE sw_iot_device ADD COLUMN IF NOT EXISTS product_id VARCHAR(64) NOT NULL DEFAULT 'unknown';

-- 新增 device_name 字段（腾讯云设备名称，在产品内唯一）
ALTER TABLE sw_iot_device ADD COLUMN IF NOT EXISTS device_name VARCHAR(128) NOT NULL DEFAULT 'unknown';

-- 新增 tencent_status 字段（腾讯云在线状态）
ALTER TABLE sw_iot_device ADD COLUMN IF NOT EXISTS tencent_status VARCHAR(32) DEFAULT 'offline';

-- 创建 product_id + device_name 唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_iot_device_product_device ON sw_iot_device (product_id, device_name, tenant_id);

-- ============================================================
-- sw_iot_device_command 表升级
-- ============================================================

-- 新增 product_id 字段
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS product_id VARCHAR(64) NOT NULL DEFAULT 'unknown';

-- 新增 device_name 字段
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS device_name VARCHAR(128) NOT NULL DEFAULT 'unknown';

-- 新增 command_type 字段（PROPERTY / ACTION）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS command_type VARCHAR(32) DEFAULT 'PROPERTY';

-- 新增 semantic_mode 字段（DEFERRED / ONLINE_CONFIRM）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS semantic_mode VARCHAR(32) DEFAULT 'DEFERRED';

-- 新增 idempotent_key 字段（幂等键，防止重复发送）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS idempotent_key VARCHAR(128);

-- 新增 expiry_time 字段（命令过期时间）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS expiry_time TIMESTAMP;

-- 新增 retry_count 字段（已尝试次数）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0;

-- 新增 last_error 字段（最后失败原因）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS last_error VARCHAR(512);

-- 新增 tencent_request_id 字段（腾讯云 RequestId）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS tencent_request_id VARCHAR(128);

-- 新增 client_token 字段（腾讯云异步行为 ClientToken）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS client_token VARCHAR(128);

-- 新增 device_output 字段（设备输出参数）
ALTER TABLE sw_iot_device_command ADD COLUMN IF NOT EXISTS device_output TEXT;

-- 创建幂等键唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_iot_command_idempotent ON sw_iot_device_command (idempotent_key) WHERE idempotent_key IS NOT NULL;

-- 创建按设备查询待发送命令的索引
CREATE INDEX IF NOT EXISTS idx_iot_command_status_device ON sw_iot_device_command (product_id, device_name, status);

-- 创建过期命令查询索引
CREATE INDEX IF NOT EXISTS idx_iot_command_expiry ON sw_iot_device_command (expiry_time, status);

-- ======== V43（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V43: 表单数据导入导出独立权限（P32 / M03-F04-02）— PostgreSQL 方言
--
-- 仅注册按钮权限资源（挂在低代码→表单设计菜单下）；
-- 普通角色授权仍由管理员通过角色菜单管理完成。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 230, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataTemplate', '下载模板', false, 2,
       '', '', 'form:data:template', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 230);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 231, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataImport', '数据导入', false, 2,
       '', '', 'form:data:import', '', 2
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 231);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 232, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataExport', '数据导出', false, 2,
       '', '', 'form:data:export', '', 3
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 232);

-- ======== V44（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V44: 流程引擎子菜单真实化（最小闭环修复 A-02）— PostgreSQL 方言
--
-- 背景：
--   V6 将「流程引擎」seed 为叶子菜单（component=workflow/views/WorkflowHome），
--   WorkflowHome 仅是目录重定向占位（<div/>），而重定向目标 /workflow/todo
--   与流程定义页 /workflow/defs 只存在于前端 Mock 菜单种子
--   （foundation/mock/seeds.ts），真实模式无路由 → 菜单落地页白屏、
--   待办/定义页 404（验收缺口 A-02/A-03）。
--
-- 变更：
--   1. id=5「流程引擎」由叶子菜单（menu_type=1）改为目录（menu_type=0，
--      component 置 NULL），与「系统管理」「低代码」目录语义对齐；
--   2. 新增子菜单（id=20~23，挂 parent_id=5）：
--      待办任务 / 已办任务 / 流程监控 / 流程定义，component 指向
--      src/modules/workflow/views 下既有页面，经前端
--      buildRoutesFromMenu 白名单解析注册真实路由；
--   3. 幂等：UPDATE + WHERE NOT EXISTS，重复执行不产生重复行。
--

-- 6. 低代码子菜单修正（同一契约：完整相对路径 + 真实组件）
--    V6 将 overview/form 写为相对段（嵌套挂载后无法命中），且 component 指向
--    不存在的 lowcode/views/LowcodeHome|LowcodeForm（仅 Mock 菜单引用的幻影组件，
--    resolveComponent 解析失败跳过注册 → 授权目录不可落地）。统一修正为
--    完整相对路径 + form 模块真实页面：概览落地表单管理列表，表单设计落地设计器。
UPDATE sys_menu SET path = 'lowcode/overview', component = 'form/views/FormDefList',
    permission = 'lowcode:view', update_time = current_timestamp
WHERE id = 3;

UPDATE sys_menu SET path = 'lowcode/form', component = 'form/views/FormDesigner',
    permission = 'lowcode:form:design', update_time = current_timestamp
WHERE id = 4;

-- 角色授权仍由管理员通过角色菜单管理完成，本脚本不 seed sys_role_menu。
-- ===================================================================

-- 1. 流程引擎改为目录
UPDATE sys_menu SET
    menu_type = 0,
    component = NULL,
    permission = 'workflow:view',
    update_time = current_timestamp
WHERE id = 5;

-- 2. 待办任务
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 20, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowTodo', '待办任务', false, 1,
       'workflow/todo', 'workflow/views/TodoList', 'workflow:todo:view', 'Bell', 10
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 20);

-- 3. 已办任务
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 21, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowProcessed', '已办任务', false, 1,
       'workflow/processed', 'workflow/views/ProcessedList', 'workflow:processed:view', 'Finished', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 21);

-- 4. 流程监控（实例列表 + 结果/流转记录）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 22, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowInstances', '流程监控', false, 1,
       'workflow/instances', 'workflow/views/ProcessInstanceList', 'workflow:instance:view', 'Monitor', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 22);

-- 5. 流程定义
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 23, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowDefs', '流程定义', false, 1,
       'workflow/defs', 'workflow/views/ProcessDefList', 'workflow:def:view', 'Setting', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 23);

-- ======== V45（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V45: 补齐系统管理 CRUD 按钮权限菜单
--
-- 背景：V2 曾以 status/visible 列风格种子 system:user/role:create|update|delete
-- 按钮菜单（id 100-112），但生产库 sys_menu 为 V15 列风格（title/hidden），
-- 该批行从未落库；后端 UserController/RoleController 的
-- @PreAuthorize("@ss.hasPermi('system:*:create|update|delete')") 因此
-- 对任何非超管用户都无法满足（角色编辑器也无权限树可选）。
--
-- 约束：
--   · 按钮菜单 id 使用 300-305（避开既有 1-232）
--   · 父菜单按 permission 定位（system:user:list / system:role:list），
--     不硬编码父 id，容忍 V2/V15 两代种子的 id 差异
--   · 全部幂等：NOT EXISTS 守卫，可安全重复执行
--   · 同步授予角色 2（管理员/admin），延续 V31「admin 角色拥有全量菜单」语义
-- ===================================================================

-- -------------------- 用户管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 300, current_timestamp, current_timestamp, 0, 0, p.id, 'UserCreate', '用户新增', false, 2, '', '', 'system:user:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 301, current_timestamp, current_timestamp, 0, 0, p.id, 'UserUpdate', '用户修改', false, 2, '', '', 'system:user:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 302, current_timestamp, current_timestamp, 0, 0, p.id, 'UserDelete', '用户删除', false, 2, '', '', 'system:user:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:delete' AND m.deleted = 0);

-- -------------------- 角色管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 303, current_timestamp, current_timestamp, 0, 0, p.id, 'RoleCreate', '角色新增', false, 2, '', '', 'system:role:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:role:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:role:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 304, current_timestamp, current_timestamp, 0, 0, p.id, 'RoleUpdate', '角色修改', false, 2, '', '', 'system:role:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:role:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:role:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 305, current_timestamp, current_timestamp, 0, 0, p.id, 'RoleDelete', '角色删除', false, 2, '', '', 'system:role:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:role:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:role:delete' AND m.deleted = 0);

-- -------------------- 授予角色 2（管理员） --------------------
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT 3000 + m.id, current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND m.permission IN ('system:user:create', 'system:user:update', 'system:user:delete',
                       'system:role:create', 'system:role:update', 'system:role:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

-- ======== V46（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V46: 清理 lowcode 命名残留
--
-- 背景：lowcode 前端模块已更名为 form（组件 form/views/*），但菜单种子
-- 仍保留 lowcode 标识（V6 目录 path、V44 的子菜单 path 与 permission），
-- 导致生产路由出现 /lowcode/overview、/lowcode/form。
--
-- 约束：
--   · 仅改标识符（path/name/permission），中文标题「低代码」为产品概念名保留
--   · 幂等：WHERE 守卫旧值，重复执行无副作用
-- ===================================================================

UPDATE sys_menu SET path = 'form', name = 'Form', update_time = current_timestamp
WHERE id = 2 AND path = 'lowcode';

UPDATE sys_menu SET path = 'form/overview', name = 'FormOverview',
    permission = 'form:view', update_time = current_timestamp
WHERE id = 3 AND path = 'lowcode/overview';

UPDATE sys_menu SET path = 'form/designer', name = 'FormDesigner',
    permission = 'form:design', update_time = current_timestamp
WHERE id = 4 AND path = 'lowcode/form';

-- ======== V47（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V47: P52 表单工作台操作权限按钮
--
-- 方向：p52-form-workbench §3.5 —— 保存、发布、历史查看、流程创建和流程
-- 管理分别沿用真实权限，前端按钮可见性不能替代后端授权。
--
-- 内容：
--   1. 注册 P52 操作边界按钮权限（menu_type=2）：
--      表单侧挂表单设计菜单（permission 基线 form:design，见 V46）：
--        form:design:save     —— 建草稿/改元数据/存 config/删草稿
--        form:design:publish  —— 发布
--      流程侧挂流程定义菜单（permission 基线 workflow:def:view，见 V44）：
--        workflow:def:create
--        workflow:def:save
--        workflow:def:publish
--        workflow:def:delete
--      查看类（快照查询、流程定义列表）复用既有菜单基线码，不新增。
--   2. 将新按钮授权给既有管理员角色（role_id=2，对齐 V45 手法），
--      保证既有非超管管理员能力不变；超管经 superAdmin 短路天然放行。
--
-- 约束：
--   · 按钮菜单 id 使用 306-311（避开既有 1-305）
--   · 父菜单按 permission 定位，不硬编码父 id
--   · 全部幂等：NOT EXISTS 守卫，可安全重复执行
-- ===================================================================

-- -------------------- 表单侧按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 306, current_timestamp, current_timestamp, 0, 0, p.id, 'FormDesignSave', '表单草稿保存', false, 2, '', '', 'form:design:save', '', 21
FROM sys_menu p
WHERE p.permission = 'form:design' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'form:design:save' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 307, current_timestamp, current_timestamp, 0, 0, p.id, 'FormDesignPublish', '表单发布', false, 2, '', '', 'form:design:publish', '', 22
FROM sys_menu p
WHERE p.permission = 'form:design' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'form:design:publish' AND m.deleted = 0);

-- -------------------- 流程侧按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 308, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefCreate', '流程创建', false, 2, '', '', 'workflow:def:create', '', 11
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 309, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefSave', '流程修改', false, 2, '', '', 'workflow:def:save', '', 12
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:save' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 310, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefPublish', '流程发布', false, 2, '', '', 'workflow:def:publish', '', 13
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:publish' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 311, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefDelete', '流程删除', false, 2, '', '', 'workflow:def:delete', '', 14
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:delete' AND m.deleted = 0);

-- -------------------- 授权管理员角色（role_id=2） --------------------
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 3000 + m.id, current_timestamp, current_timestamp, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.id IN (306, 307, 308, 309, 310, 311)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

-- ======== V48（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
alter table sw_notify_message add column channel varchar(40) not null default 'IN_APP';
alter table sw_notify_message add column delivery_status varchar(20) not null default 'SUCCESS';
alter table sw_notify_message add column external_message_id varchar(200);
alter table sw_notify_message add column failure_reason varchar(500);
alter table sw_notify_message add column idempotency_key varchar(200);
create index idx_sw_notify_msg_delivery on sw_notify_message (tenant_id, delivery_status);
create unique index uk_sw_notify_msg_idempotency on sw_notify_message (tenant_id, idempotency_key);

-- ======== V49（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- P58：参与人快照、审批动作/意见、分支轨迹与抄送投递审计。
create table sw_bpm_participant_snapshot (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, task_id varchar(64) not null,
    participant_id varchar(64) not null, participant_status varchar(20) not null,
    invalid_reason varchar(500)
);
create index idx_sw_bpm_participant_task on sw_bpm_participant_snapshot (tenant_id, task_id);

create table sw_bpm_approval_action (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, task_id varchar(64) not null,
    actor_id bigint not null, action varchar(20) not null, opinion_form_id varchar(200),
    opinion_form_version varchar(64), initialization_summary text, opinion_data text,
    settlement_status varchar(20)
);
create unique index uk_sw_bpm_approval_task_actor on sw_bpm_approval_action (tenant_id, task_id, actor_id);

create table sw_bpm_branch_trace (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, branch_id varchar(200) not null,
    condition_version varchar(64) not null, input_summary text
);
create index idx_sw_bpm_branch_trace_instance on sw_bpm_branch_trace (tenant_id, process_instance_id);

create table sw_bpm_copy_record (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, task_id varchar(64),
    recipient_id varchar(64) not null, delivery_status varchar(30) not null, failure_reason varchar(500)
);
create index idx_sw_bpm_copy_instance on sw_bpm_copy_record (tenant_id, process_instance_id);

-- ======== V50（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- P4：流程业务命令持久受理/队列 与 业务发起草稿。
create table sw_bpm_command (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    command_key varchar(128) not null, command_type varchar(32) not null,
    channel varchar(16) not null default 'NORMAL', status varchar(16) not null default 'PENDING',
    payload text, result text, failure_reason varchar(1000),
    retry_count int not null default 0, next_retry_at timestamp,
    claimed_at timestamp, finished_at timestamp, initiator_id bigint
);
create unique index uk_sw_bpm_command_key on sw_bpm_command (tenant_id, command_key);
create index idx_sw_bpm_command_dispatch on sw_bpm_command (status, channel, next_retry_at);

create table sw_bpm_draft (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    title varchar(200), form_key varchar(128) not null, form_version bigint,
    process_def_key varchar(128), payload text,
    status varchar(20) not null default 'EDITING', command_id bigint, submit_seq int not null default 0,
    result_record_id varchar(36), last_error varchar(1000)
);
create index idx_sw_bpm_draft_owner on sw_bpm_draft (tenant_id, create_by, form_key);

-- ======== V51（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- P4 阶段B：P0 同步优先通道专用调用权限（仅注册，不默认授权）。
-- 权限码 workflow:p0:dispatch（BPM 命令通道 P0 车道 + 同步有界等待）。
-- D2 边界：不得将所有管理员或所有登录用户自动视为紧急调用方——本迁移仅注册
-- 按钮权限（menu_type=2），不向任何角色（含 role_id=2 管理员）默认授予；
-- 授权由 Owner/管理员经既有角色治理显式操作。superAdmin 短路为平台既有语义，
-- 不构成本权限的默认发放。全部 NOT EXISTS 幂等，可重复执行。
-- 未授权调用 P0 通道返回 403（含一般管理员）。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 312, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowP0Dispatch', 'P0紧急流程调用', false, 2, '', '', 'workflow:p0:dispatch', '', 31
FROM sys_menu p
WHERE p.permission = 'workflow:def:view'
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 312 AND m.deleted = 0);

-- ======== V52（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- P4：表单提交幂等键（草稿/命令重试场景防重复落表单数据）。
alter table sw_form_trace add column submit_idempotency_key varchar(128);
create unique index uk_sw_form_trace_idem on sw_form_trace (tenant_id, submit_idempotency_key);

-- ======== V53（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V53: P4 OA 个人中心三入口菜单 — PostgreSQL/H2 通用方言
--
-- 变更：在「流程引擎」目录（id=5，V44 已目录化）下新增：
--   id=24 我发起的   path=workflow/my-instances  component=workflow/views/MyInstances
--   id=25 我的草稿   path=workflow/my-drafts     component=workflow/views/MyDrafts
--   id=26 我的已办   path=workflow/my-processed  component=workflow/views/MyProcessed
-- 查看权限挂目录基线 workflow:view；角色授权由管理员经角色菜单管理显式操作，
-- 本脚本不 seed sys_role_menu（对齐 V44 口径）。幂等：WHERE NOT EXISTS。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 24, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyInstances', '我发起的', false, 1,
       'workflow/my-instances', 'workflow/views/MyInstances', 'workflow:my:view', 'Document', 21
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 24);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 25, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyDrafts', '我的草稿', false, 1,
       'workflow/my-drafts', 'workflow/views/MyDrafts', 'workflow:my:view', 'EditPen', 22
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 25);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 26, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyProcessed', '我的已办', false, 1,
       'workflow/my-processed', 'workflow/views/MyProcessed', 'workflow:my:view', 'Finished', 23
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 26);

-- ======== V54（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- P4: 业务发起可见范围。空值表示当前租户内全部用户。
ALTER TABLE sw_form_def ADD COLUMN visibility_scope TEXT;

-- ======== V55（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- P4 G4b：命令领取租约令牌 + 审批动作命令关联（与 H2 版本同语义）。
-- 1) sw_bpm_command.claim_token：每次 claimDue 生成一次性租约令牌；complete/reject/
--    failAndScheduleRetry 必须携带当前令牌方可写回。stale 回收后旧持有者在新持有者
--    仍 PROCESSING 期间的迟到写回因令牌不匹配被拒，不污染当前领取者。
-- 2) sw_bpm_approval_action.command_id：动作记录与受理命令关联；同一命令确认丢失
--    重投时据此定位自身已提交结果并恢复一致的可回查命令结果，不误报"已被处理"；
--    不同命令/意图的冲突仍确定性拒绝。同步 HTTP 入口该列为 null。

ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS claim_token varchar(64);
ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS command_id bigint;

-- ======== V56（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- V56: v0.0.2 OA — 流程中心分类 + 事项归属 + 催办记录 (PostgreSQL)
-- ===================================================================
-- 1) sw_bpm_category：单层流程分类（名称/排序），租户隔离，逻辑删除。
-- 2) sw_bpm_process_def.category_id：事项归属分类（NULL=未分类兜底）。
-- 3) sw_bpm_urge_record：催办记录（发起人对运行中实例催办当前实际待办人）；
--    result=ACCEPTED 行的 create_time 用于 10 分钟冷却判定；
--    cooldown_key 仅 ACCEPTED 行非空，唯一索引提供并发重复受理的数据库级兜底。
-- 全部幂等（IF NOT EXISTS），可重复执行；与 H2 版本同语义。
-- ===================================================================

CREATE TABLE IF NOT EXISTS sw_bpm_category (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    name              varchar(100)    not null,
    sort_no           int             not null default 0
);

ALTER TABLE sw_bpm_process_def ADD COLUMN IF NOT EXISTS category_id bigint;

CREATE TABLE IF NOT EXISTS sw_bpm_urge_record (
    id                  bigint          not null primary key,
    create_time         timestamp       not null default current_timestamp,
    create_by           bigint,
    update_time         timestamp       not null default current_timestamp,
    update_by           bigint,
    deleted             smallint        not null default 0,
    tenant_id           bigint          not null default 0,
    version             bigint          not null default 0,
    process_instance_id varchar(64)     not null,
    initiator_id        bigint          not null,
    target_user_id      bigint,
    result              varchar(20)     not null,
    detail              varchar(500),
    cooldown_key        varchar(128)
);

CREATE INDEX IF NOT EXISTS idx_sw_bpm_urge_instance ON sw_bpm_urge_record (process_instance_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_urge_cooldown ON sw_bpm_urge_record (cooldown_key);

-- ======== V57（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V57: v0.0.2 OA — 通知发送尝试流水 (PostgreSQL)
-- ===================================================================
-- sw_notify_send_attempt：每次渠道投递（首次发送与失败重发）各写一行，
-- 保留原始失败、各次尝试与最新结果；消息行 delivery_status 恒为最新结果。
-- 全部幂等（IF NOT EXISTS），可重复执行；与 H2 版本同语义。
-- ===================================================================

CREATE TABLE IF NOT EXISTS sw_notify_send_attempt (
    id                  bigint          not null primary key,
    create_time         timestamp       not null default current_timestamp,
    create_by           bigint,
    update_time         timestamp       not null default current_timestamp,
    update_by           bigint,
    deleted             smallint        not null default 0,
    tenant_id           bigint          not null default 0,
    version             bigint          not null default 0,
    message_id          bigint          not null,
    attempt_no          int             not null default 1,
    channel             varchar(32),
    status              varchar(20)     not null,
    failure_reason      varchar(500),
    external_message_id varchar(200)
);

CREATE INDEX IF NOT EXISTS idx_sw_notify_attempt_message ON sw_notify_send_attempt (message_id);

-- ======== V58（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V58: v0.0.2 OA — 工作台布局持久化 + 新页面菜单/权限 (H2/PostgreSQL 通用方言)
-- ===================================================================
-- 1) sys_user_workspace：用户级工作台布局与常用事项（租户+用户唯一，JSON 存储）。
--    组件键白名单（todo/myInitiated/cc/favoriteItems）由服务层校验；
--    常用事项存事项稳定标识（processKey），可执行性由目录/绑定链路实时校验。
-- 2) 菜单/权限种子（幂等 WHERE NOT EXISTS，不 seed sys_role_menu，对齐 V44/V53 口径）：
--   id=320 流程中心（workflow/catalog，workflow:catalog:view）
--   id=321 抄送我的（workflow/my-cc，workflow:cc:view）
--   id=322 催办按钮（挂我发起的 id=24，workflow:urge）
--   id=323 分类/事项管理按钮（挂流程中心 id=320，workflow:catalog:manage）
--   id=324 通知发送记录（notify/record，notify:record:view）
--   id=325 通知失败重发按钮（挂发送记录 id=324，notify:record:resend）
-- ===================================================================

CREATE TABLE IF NOT EXISTS sys_user_workspace (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    user_id           bigint          not null,
    layout_json       text
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_workspace ON sys_user_workspace (tenant_id, user_id);

-- ==================== 菜单/权限种子 ====================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 320, current_timestamp, current_timestamp, 0, 0, 5, 'ProcessCatalog', '流程中心', false, 1,
       'workflow/catalog', 'workflow/views/ProcessCatalog', 'workflow:catalog:view', 'Files', 24
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 320);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 321, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyCc', '抄送我的', false, 1,
       'workflow/my-cc', 'workflow/views/MyCc', 'workflow:cc:view', 'Message', 25
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 321);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 322, current_timestamp, current_timestamp, 0, 0, 24, 'WorkflowUrge', '催办', false, 2, '', '', 'workflow:urge', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 322);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 323, current_timestamp, current_timestamp, 0, 0, 320, 'CatalogManage', '分类/事项管理', false, 2, '', '', 'workflow:catalog:manage', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 323);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 324, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyRecord', '发送记录', false, 1,
       'record', 'notify/views/NotifyRecordList', 'notify:record:view', 'List', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 324);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 325, current_timestamp, current_timestamp, 0, 0, 324, 'NotifyRecordResend', '失败重发', false, 2, '', '', 'notify:record:resend', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 325);

-- ======== V59（来源: sw-basic/sw-basic-iot，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: P21 IoT 平台化扩展 (PostgreSQL)
-- ===================================================================
-- 连接配置 / 产品与物模型版本 / 设备扩展 / Topic / 受控脚本 / 事件规则 /
-- 消息与运行记录 / 流程触发幂等 / 统一命令。
-- 约定：8 基列 + bigint PK + 无外键，与既有 sw_iot_* 对齐。
-- ===================================================================

-- 1. 连接配置（独立于设备，可复用；凭证仅密文）
create table sw_iot_connection (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    code              varchar(64)     not null,
    name              varchar(200)    not null,
    conn_type         varchar(20)     not null,
    enabled           smallint        not null default 1,
    health_status     varchar(20)     not null default 'UNKNOWN',
    last_check_time   timestamp,
    last_check_result varchar(500),
    host              varchar(200),
    port              int,
    use_tls           smallint        not null default 0,
    client_id_prefix  varchar(64),
    username          varchar(128),
    password_cipher   varchar(512),
    keepalive         int             not null default 60,
    clean_session     smallint        not null default 1,
    reconnect_min_sec int             not null default 1,
    reconnect_max_sec int             not null default 60,
    region            varchar(64),
    endpoint          varchar(200),
    secret_ref        varchar(256),
    ext_json          text
);
create unique index uk_sw_iot_conn_code on sw_iot_connection (tenant_id, code);

-- 2. 产品（承载统一物模型）
create table sw_iot_product (
    id                 bigint          not null primary key,
    create_time        timestamp       not null default current_timestamp,
    create_by          bigint,
    update_time        timestamp       not null default current_timestamp,
    update_by          bigint,
    deleted            smallint        not null default 0,
    tenant_id          bigint          not null default 0,
    version            bigint          not null default 0,
    code               varchar(64)     not null,
    name               varchar(200)    not null,
    conn_id            bigint,
    conn_type          varchar(20)     not null default 'MQTT',
    model_status       varchar(20)     not null default 'DRAFT',
    published_model_id bigint,
    description        varchar(500)
);
create unique index uk_sw_iot_product_code on sw_iot_product (tenant_id, code);

-- 3. 物模型版本（草稿→校验/发布→归档；content_json = properties/events/actions）
create table sw_iot_thing_model (
    id           bigint          not null primary key,
    create_time  timestamp       not null default current_timestamp,
    create_by    bigint,
    update_time  timestamp       not null default current_timestamp,
    update_by    bigint,
    deleted      smallint        not null default 0,
    tenant_id    bigint          not null default 0,
    version      bigint          not null default 0,
    product_id   bigint          not null,
    model_version int            not null,
    status       varchar(20)     not null default 'DRAFT',
    content_json text,
    publish_time timestamp
);
create unique index uk_sw_iot_model_ver on sw_iot_thing_model (tenant_id, product_id, model_version);

-- 4. 设备扩展列（保留既有腾讯字段）
alter table sw_iot_device add column if not exists connection_id      bigint;
alter table sw_iot_device add column if not exists product_ref_id     bigint;
alter table sw_iot_device add column if not exists manage_status      varchar(20) not null default 'DRAFT';
alter table sw_iot_device add column if not exists process_access_enabled smallint not null default 0;
alter table sw_iot_device add column if not exists last_report_time   timestamp;
alter table sw_iot_device add column if not exists last_command_time  timestamp;
alter table sw_iot_device add column if not exists labels             varchar(500);
alter table sw_iot_device add column if not exists ext_json           text;

-- 5. Topic 配置（订阅/发布、方向、QoS、载荷映射）
create table sw_iot_topic (
    id            bigint          not null primary key,
    create_time   timestamp       not null default current_timestamp,
    create_by     bigint,
    update_time   timestamp       not null default current_timestamp,
    update_by     bigint,
    deleted       smallint        not null default 0,
    tenant_id     bigint          not null default 0,
    version       bigint          not null default 0,
    conn_id       bigint          not null,
    product_id    bigint,
    topic         varchar(200)    not null,
    direction     varchar(10)     not null default 'UP',
    qos           int             not null default 1,
    retain        smallint        not null default 0,
    payload_type  varchar(20)     not null default 'RAW',
    mapping_json  text,
    enabled       smallint        not null default 1,
    remark        varchar(500)
);
create unique index uk_sw_iot_topic on sw_iot_topic (tenant_id, topic);

-- 6. 受控脚本与版本
create table sw_iot_script (
    id               bigint          not null primary key,
    create_time      timestamp       not null default current_timestamp,
    create_by        bigint,
    update_time      timestamp       not null default current_timestamp,
    update_by        bigint,
    deleted          smallint        not null default 0,
    tenant_id        bigint          not null default 0,
    version          bigint          not null default 0,
    code             varchar(64)     not null,
    name             varchar(200)    not null,
    language         varchar(10)     not null,
    trigger_type     varchar(20)     not null default 'MESSAGE',
    binding_json     text,
    input_schema     text,
    output_schema    text,
    status           varchar(20)     not null default 'DRAFT',
    current_version  int             not null default 1,
    published_version int,
    timeout_ms       int             not null default 5000
);
create unique index uk_sw_iot_script_code on sw_iot_script (tenant_id, code);

create table sw_iot_script_version (
    id           bigint          not null primary key,
    create_time  timestamp       not null default current_timestamp,
    create_by    bigint,
    update_time  timestamp       not null default current_timestamp,
    update_by    bigint,
    deleted      smallint        not null default 0,
    tenant_id    bigint          not null default 0,
    version      bigint          not null default 0,
    script_id    bigint          not null,
    script_version int           not null,
    source_code  text,
    status       varchar(20)     not null default 'DRAFT',
    publish_time timestamp
);
create unique index uk_sw_iot_script_ver on sw_iot_script_version (tenant_id, script_id, script_version);

create table sw_iot_script_exec (
    id             bigint          not null primary key,
    create_time    timestamp       not null default current_timestamp,
    create_by      bigint,
    update_time    timestamp       not null default current_timestamp,
    update_by      bigint,
    deleted        smallint        not null default 0,
    tenant_id      bigint          not null default 0,
    version        bigint          not null default 0,
    script_id      bigint          not null,
    script_version int             not null,
    trigger_source varchar(20),
    trigger_ref    varchar(128),
    device_ref     varchar(128),
    status         varchar(20)     not null,
    input_json     text,
    output_json    text,
    error          varchar(1000),
    duration_ms    bigint,
    side_effect    smallint        not null default 0,
    idempotent_key varchar(128)
);
create index idx_sw_iot_exec_script on sw_iot_script_exec (tenant_id, script_id, create_time);

-- 7. 事件规则（阈值/变化/事件/上下线；防抖/冷却；流程映射）
create table sw_iot_event_rule (
    id                   bigint          not null primary key,
    create_time          timestamp       not null default current_timestamp,
    create_by            bigint,
    update_time          timestamp       not null default current_timestamp,
    update_by            bigint,
    deleted              smallint        not null default 0,
    tenant_id            bigint          not null default 0,
    version              bigint          not null default 0,
    code                 varchar(64)     not null,
    name                 varchar(200)    not null,
    device_id            bigint          not null,
    rule_type            varchar(20)     not null,
    condition_json       text,
    debounce_ms          bigint          not null default 0,
    cooldown_ms          bigint          not null default 0,
    continuous_count     int             not null default 1,
    script_id            bigint,
    process_template_key varchar(128),
    form_mapping_json    text,
    process_enabled      smallint        not null default 0,
    status               varchar(20)     not null default 'DRAFT',
    rule_version         int             not null default 1,
    last_fired_time      timestamp
);
create unique index uk_sw_iot_rule_code on sw_iot_event_rule (tenant_id, code);
create index idx_sw_iot_rule_device on sw_iot_event_rule (tenant_id, device_id);

-- 8. 运行记录
create table sw_iot_message_log (
    id            bigint          not null primary key,
    create_time   timestamp       not null default current_timestamp,
    create_by     bigint,
    update_time   timestamp       not null default current_timestamp,
    update_by     bigint,
    deleted       smallint        not null default 0,
    tenant_id     bigint          not null default 0,
    version       bigint          not null default 0,
    conn_id       bigint,
    device_id     bigint,
    topic         varchar(200),
    direction     varchar(10)     not null default 'UP',
    message_id    varchar(128),
    dedup_key     varchar(128),
    payload       text,
    payload_type  varchar(20),
    parse_status  varchar(20)     not null default 'RECEIVED',
    parse_error   varchar(500),
    qos           int             not null default 0
);
create index idx_sw_iot_msg_dedup on sw_iot_message_log (tenant_id, dedup_key);
create index idx_sw_iot_msg_topic on sw_iot_message_log (tenant_id, topic, create_time);

create table sw_iot_property_record (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    device_id       bigint          not null,
    property_id     varchar(64)     not null,
    value_json      varchar(1000),
    pre_value_json  varchar(1000),
    report_time     timestamp       not null
);
create index idx_sw_iot_prop_dev on sw_iot_property_record (tenant_id, device_id, property_id, report_time);

create table sw_iot_event_record (
    id          bigint          not null primary key,
    create_time timestamp       not null default current_timestamp,
    create_by   bigint,
    update_time timestamp       not null default current_timestamp,
    update_by   bigint,
    deleted     smallint        not null default 0,
    tenant_id   bigint          not null default 0,
    version     bigint          not null default 0,
    device_id   bigint          not null,
    event_id    varchar(64)     not null,
    payload     text,
    occur_time  timestamp       not null
);
create index idx_sw_iot_event_dev on sw_iot_event_record (tenant_id, device_id, occur_time);

-- 9. 流程触发幂等记录
create table sw_iot_process_trigger (
    id                  bigint          not null primary key,
    create_time         timestamp       not null default current_timestamp,
    create_by           bigint,
    update_time         timestamp       not null default current_timestamp,
    update_by           bigint,
    deleted             smallint        not null default 0,
    tenant_id           bigint          not null default 0,
    version             bigint          not null default 0,
    rule_id             bigint,
    script_id           bigint,
    device_id           bigint,
    idempotent_key      varchar(128)    not null,
    status              varchar(20)     not null default 'PENDING',
    process_instance_id varchar(64),
    form_snapshot       text,
    error               varchar(500),
    trigger_time        timestamp       not null
);
create unique index uk_sw_iot_trigger_key on sw_iot_process_trigger (tenant_id, idempotent_key);

-- 11. 遗留腾讯身份列放宽
alter table sw_iot_device alter column product_id drop not null;
alter table sw_iot_device alter column device_name drop not null;

-- 10. 统一命令（Provider 无关；区分 Broker 接收 / 设备回复 / 业务执行）
create table sw_iot_command (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    device_id       bigint          not null,
    conn_id         bigint,
    provider        varchar(20)     not null default 'MQTT',
    capability_type varchar(20)     not null,
    capability_id   varchar(64),
    params_json     text,
    status          varchar(20)     not null default 'PENDING',
    result_json     text,
    error           varchar(500),
    idempotent_key  varchar(128),
    source_type     varchar(20)     not null default 'MANUAL',
    source_ref      varchar(128),
    flow_instance_id varchar(64),
    flow_task_id    varchar(64),
    qos             int             not null default 1,
    sent_time       timestamp,
    reply_time      timestamp,
    timeout_ms      int             not null default 30000
);
create index idx_sw_iot_cmd_dev on sw_iot_command (tenant_id, device_id, create_time);
create index idx_sw_iot_cmd_flow on sw_iot_command (tenant_id, flow_instance_id);

-- ======== V60（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V60: P21 IoT 接入 — 流程模板 IoT 接入开关（PostgreSQL）
--
-- 变更：sw_bpm_process_def 新增 iot_access_enabled（默认 false）。
-- 仅「已发布 + iot_access_enabled」的流程模板允许被 IoT 事件规则/受控脚本
-- 发起流程；未开启的模板在规则配置与触发时均被明确拒绝。
-- 幂等：IF NOT EXISTS。
-- ===================================================================

alter table sw_bpm_process_def add column if not exists iot_access_enabled boolean not null default false;

-- ======== V61（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V61: P21 IoT 平台管理菜单 — PostgreSQL/H2 通用方言
--
-- 变更：在「物联网」目录（id=8，V6 已建）下新增 7 个管理入口：
--   330 连接配置  331 产品物模型  332 设备管理  333 Topic 配置
--   334 受控脚本  335 事件规则    336 运行记录
-- 查看权限挂 iot:view 基线；管理权限由角色菜单管理显式授权；
-- 本脚本不 seed sys_role_menu（对齐 V44/V53 口径）。幂等：WHERE NOT EXISTS。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 330, current_timestamp, current_timestamp, 0, 0, 8, 'IotConnectionList', '连接配置', false, 1,
       'iot/connections', 'iot/views/IotConnectionList', 'iot:connection:manage', 'Link', 61
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 330);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 331, current_timestamp, current_timestamp, 0, 0, 8, 'IotProductList', '产品物模型', false, 1,
       'iot/products', 'iot/views/IotProductList', 'iot:product:manage', 'Goods', 62
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 331);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 332, current_timestamp, current_timestamp, 0, 0, 8, 'IotDeviceList', '设备管理', false, 1,
       'iot/devices-manage', 'iot/views/IotDeviceList', 'iot:device:manage', 'Monitor', 63
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 332);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 333, current_timestamp, current_timestamp, 0, 0, 8, 'IotTopicList', 'Topic 配置', false, 1,
       'iot/topics', 'iot/views/IotTopicList', 'iot:topic:manage', 'ChatLineSquare', 64
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 333);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 334, current_timestamp, current_timestamp, 0, 0, 8, 'IotScriptList', '受控脚本', false, 1,
       'iot/scripts', 'iot/views/IotScriptList', 'iot:script:manage', 'EditPen', 65
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 334);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 335, current_timestamp, current_timestamp, 0, 0, 8, 'IotRuleList', '事件规则', false, 1,
       'iot/rules', 'iot/views/IotRuleList', 'iot:rule:manage', 'Bell', 66
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 335);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 336, current_timestamp, current_timestamp, 0, 0, 8, 'IotRuntimeLogs', '运行记录', false, 1,
       'iot/runtime', 'iot/views/IotRuntimeLogs', 'iot:runtime:view', 'DataLine', 67
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 336);

-- ======== V62（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- V62: P21 IoT 接入 — 流程模板 IoT 接入开关（bpm 模块链，PostgreSQL）
-- 与主库 V60 同构幂等：bpm 隔离链（如 p4overlap 测试库）亦能获得该列。
-- ===================================================================

alter table sw_bpm_process_def add column if not exists iot_access_enabled boolean not null default false;

-- ======== V63（来源: sw-biz/sw-bpm/sw-bpm-process,sw-bootstrap，副本 2 处已去重） ========
-- ===================================================================
-- V63: P21 A6 — 流程模板设备动作配置（PostgreSQL/H2 通用幂等）
-- iot_device_action_json: {"enabled","deviceSource":"FIXED|FORM_FIELD|VARIABLE",
--   "deviceId","deviceField","variableName","commandKey","paramField","failurePolicy"}
-- ===================================================================
alter table sw_bpm_process_def add column if not exists iot_device_action_json text;

-- ======== V64（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V64: P21 — 物联网父菜单改为目录（PostgreSQL/H2 通用幂等）
-- id=8 原为叶子页（IotHome），改为目录后 V61 七个管理入口在侧边栏渲染。
-- ===================================================================
update sys_menu set menu_type = 0, component = null where id = 8 and menu_type <> 0;

-- ======== V65（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V65: P21 A6/G2a — 流程设备动作配置入口（PostgreSQL/H2 通用幂等）
--   id=337 path=iot/flow-actions component=iot/views/IotFlowActions
--   权限 workflow:def:publish（复用流程发布权限，不新增权限串）。
-- ===================================================================
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 337, current_timestamp, current_timestamp, 0, 0, 8, 'IotFlowActions', '流程设备动作', false, 1,
       'iot/flow-actions', 'iot/views/IotFlowActions', 'workflow:def:publish', 'Setting', 68
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 337);

-- ======== V66（来源: sw-basic/sw-basic-iot，副本 1 处已去重） ========
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

-- ======== V67（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V67: P60 I1 组织与权限底座 — PostgreSQL/H2 通用方言
--
-- 内容：
--   1. sys_user_post 增加任职部门 dept_id（岗位在部门内承担，方向 §3.1）：
--      NOT NULL DEFAULT 0 起步，再按用户主部门回填，0 仅保留给
--      主部门为空的历史数据（只参与岗位级选人，不参与部门+岗位选人）。
--   2. sw_bpm_participant_snapshot 增加参与人展示名快照 participant_name：
--      节点进入时冻结办理人可读身份，后续改名/停用不再重写历史展示
--      （历史流程身份不被改写，方向 §3.1）。
--   3. 部门/岗位管理按钮权限种子（menu_type=2，id 340-345，避开既有
--      1-337），父菜单按 permission 定位（system:dept:list /
--      system:post:list，V15 已建），并授予角色 2（管理员，对齐 V45
--      「3000 + 菜单 id」手法）。DeptController/PostController 将补
--      @PreAuthorize，无该批权限的非超管用户此前可越权增删改。
--
-- 约束：
--   · 全部幂等/前向：不加回滚段，ALTER 仅在列不存在时执行效果为幂等
--     （Flyway 版本链保证单次执行）；种子 NOT EXISTS 守卫可重复执行
--   · 与既有 V45/V47/V61 种子风格一致，不硬编码父 id
-- ===================================================================

-- -------------------- 1. 岗位任职部门 --------------------
ALTER TABLE sys_user_post ADD COLUMN dept_id bigint NOT NULL DEFAULT 0;

UPDATE sys_user_post
SET dept_id = COALESCE(
    (SELECT u.dept_id FROM sys_user u
      WHERE u.id = sys_user_post.user_id AND u.deleted = 0),
    0)
WHERE dept_id = 0;

CREATE INDEX idx_sys_user_post_dept ON sys_user_post (tenant_id, dept_id, deleted);

-- -------------------- 2. 参与人展示名快照 --------------------
ALTER TABLE sw_bpm_participant_snapshot ADD COLUMN participant_name varchar(100);

-- -------------------- 3. 部门管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 340, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptCreate', '部门新增', false, 2, '', '', 'system:dept:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 341, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptUpdate', '部门修改', false, 2, '', '', 'system:dept:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 342, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptDelete', '部门删除', false, 2, '', '', 'system:dept:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:delete' AND m.deleted = 0);

-- -------------------- 4. 岗位管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 343, current_timestamp, current_timestamp, 0, 0, p.id, 'PostCreate', '岗位新增', false, 2, '', '', 'system:post:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 344, current_timestamp, current_timestamp, 0, 0, p.id, 'PostUpdate', '岗位修改', false, 2, '', '', 'system:post:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 345, current_timestamp, current_timestamp, 0, 0, p.id, 'PostDelete', '岗位删除', false, 2, '', '', 'system:post:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:delete' AND m.deleted = 0);

-- -------------------- 5. 授予角色 2（管理员） --------------------
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT 3000 + m.id, current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND m.permission IN ('system:dept:create', 'system:dept:update', 'system:dept:delete',
                       'system:post:create', 'system:post:update', 'system:post:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

-- ======== V68（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V68: P60 I2 低代码表单收口 — 表单数据动作权限按钮
--
-- 方向：direction-stage-i2-low-code-form-closure §4.6 —— 动作权限覆盖
-- 创建、查看、编辑、删除/停用、导入、导出；页面按钮、深链与真实接口
-- 结果必须一致，服务端 @PreAuthorize 是唯一权威。
--
-- 内容：
--   注册 I2 数据动作按钮权限（menu_type=2，挂低代码→表单设计菜单 parent_id=4）：
--     form:data:submit   —— 表单填报/正式提交
--     form:data:edit     —— 记录更新
--     form:data:delete   —— 记录删除
--     form:data:query    —— 记录列表/详情查询
--   （form:data:import/export 已由 V43 登记，此处不重复）
--   停用/启用与列表配置沿用既有 form:design:publish / form:design:save 基线码。
--
-- 约束：
--   · 按钮 id 使用 350-353（避开既有 1-349）
--   · 全部幂等：NOT EXISTS 守卫，可安全重复执行
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 350, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataSubmit', '表单填报提交', false, 2,
       '', '', 'form:data:submit', '', 11
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 350);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 351, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataEdit', '记录编辑', false, 2,
       '', '', 'form:data:edit', '', 12
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 351);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 352, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataDelete', '记录删除', false, 2,
       '', '', 'form:data:delete', '', 13
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 352);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 353, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataQuery', '记录查询', false, 2,
       '', '', 'form:data:query', '', 14
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 353);

-- ======== V69（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- ===================================================================
-- P60 I2 低代码表单收口 :: 列表配置 / 外部数据源查询契约 / 生命周期审计
-- ===================================================================

-- ==================== 1. 表单列表展示配置 ====================
CREATE TABLE sw_form_list_config (
    id           VARCHAR(36)  PRIMARY KEY,
    form_id      VARCHAR(36)  NOT NULL UNIQUE,
    config_json  TEXT         NOT NULL,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    BIGINT,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by    BIGINT,
    version      BIGINT       NOT NULL DEFAULT 0
);

-- ==================== 2. 受控外部数据源查询契约（版本化） ====================
CREATE TABLE sw_form_ext_query (
    id             VARCHAR(36)  PRIMARY KEY,
    datasource_id  BIGINT       NOT NULL,
    query_key      VARCHAR(64)  NOT NULL,
    query_version  INT          NOT NULL DEFAULT 1,
    sql_text       TEXT         NOT NULL,
    output_fields  TEXT         NOT NULL,
    enabled        SMALLINT     NOT NULL DEFAULT 1,
    tenant_id      BIGINT       NOT NULL DEFAULT 0,
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by      BIGINT,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by      BIGINT,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_sw_form_ext_query UNIQUE (tenant_id, query_key, query_version)
);

-- ==================== 3. 表单生命周期审计 ====================
CREATE TABLE sw_form_lifecycle_audit (
    id           VARCHAR(36)  PRIMARY KEY,
    form_id      VARCHAR(36)  NOT NULL,
    action       VARCHAR(20)  NOT NULL,
    reason       VARCHAR(500),
    operator_id  BIGINT,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    BIGINT,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by    BIGINT,
    version      BIGINT       NOT NULL DEFAULT 0
);

-- ======== V70（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- V64 (bpm 分链): P60 I3 人工审批与自研流程设计器 — 定义版本与发布冻结
--
-- 方向：product/v0.1.0-oa-completion/ready/direction-stage-i3-manual-approval-first-party-process-designer.md §4.3
--
-- 内容：
--   1. 新表 sw_bpm_process_def_version —— 每次发布冻结一条不可变版本行：
--      graph_json、节点配置、表单版本、函数版本快照全部冻结；
--      PUBLISHED 行不得被原地覆盖；SUSPENDED/DISABLED 用状态字段表达。
--   2. sw_bpm_process_def 增列 published_version —— 已发布过的最高版本号
--      （发布时与版本行同步递增）；def 行的 graph_json 仅承载当前草稿。
--   3. sw_bpm_instance 增列 def_version —— 实例发起时绑定的发布版本快照。
--
-- 约束：
--   · 版本号 (tenant_id, def_id, version) 唯一，单调递增
--   · def 行的 graph_json 仅承载当前草稿，不改写已发布版本行
--   · 全部幂等：IF NOT EXISTS 守卫，可安全重复执行
-- ===================================================================

CREATE TABLE IF NOT EXISTS sw_bpm_process_def_version (
    id                    bigint          not null primary key,
    create_time           timestamp       not null default current_timestamp,
    create_by             bigint,
    update_time           timestamp       not null default current_timestamp,
    update_by             bigint,
    deleted               smallint        not null default 0,
    tenant_id             bigint          not null default 0,
    version               bigint          not null default 0,
    def_id                bigint          not null,
    graph_version         int             not null,
    status                varchar(20)     not null default 'PUBLISHED',
    name                  varchar(200)    not null,
    form_key              varchar(200),
    form_version          varchar(64),
    function_versions     text,
    graph_json            text,
    deployment_id         varchar(64),
    process_definition_id varchar(64),
    published_by          bigint,
    published_at          timestamp
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_def_version
    ON sw_bpm_process_def_version (tenant_id, def_id, graph_version);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_def_version_status
    ON sw_bpm_process_def_version (tenant_id, def_id, status);

ALTER TABLE sw_bpm_process_def ADD COLUMN IF NOT EXISTS published_version int;
ALTER TABLE sw_bpm_instance ADD COLUMN IF NOT EXISTS def_version int;

-- ======== V71（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- V65 (bpm 分链): P60 I3 人工审批与自研流程设计器 — 动作语义与生命周期表
--
-- 方向：.../direction-stage-i3-manual-approval-first-party-process-designer.md §4.4—§4.7
--
-- 内容：
--   1. sw_bpm_approval_action 增列，承载 I3 新动作的完整勾稽链：
--        target_user_id     转入人/补充确认人目标
--        proxy_for_user_id  原责任人（转办转出人 / 委托委托人）
--        round_no           办理轮次（退回重走递增）
--        related_task_id    关联任务（加签/补签原任务、委托回归任务）
--        detail             动作说明 JSON（取消原因、顺序、附件等）
--   2. 新表 sw_bpm_authorize_rule —— 授权代理规则（生效期/范围/条件）。
--   3. 新表 sw_bpm_communication —— 沟通征询与回复（不授审批权）。
--   4. 新表 sw_bpm_sign_record —— 加签/补签记录（串行/并行 + 补签关联原终态）。
--   5. 新表 sw_bpm_consensus_vote —— 会签计数落库：DB 唯一键保证多实例
--      部署下同任务同人只形成一次合法计数，替代单 JVM synchronized。
--
-- 约束：
--   · 唯一键防重放：代理规则同一 principal+agent+范围+时段仅一条；
--     会签 (tenant_id, task_id, actor_id) 唯一
--   · 全部幂等：IF NOT EXISTS 守卫
-- ===================================================================

ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS target_user_id bigint;
ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS proxy_for_user_id bigint;
ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS round_no int;
ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS related_task_id varchar(64);
ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS detail text;

CREATE TABLE IF NOT EXISTS sw_bpm_authorize_rule (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    principal_id      bigint          not null,
    agent_id          bigint          not null,
    scope_type        varchar(20)     not null default 'GLOBAL',
    process_def_key   varchar(200),
    node_key          varchar(200),
    business_key      varchar(200),
    condition_json    text,
    start_at          timestamp,
    end_at            timestamp,
    status            varchar(20)     not null default 'ACTIVE',
    detail            varchar(500)
);

CREATE INDEX IF NOT EXISTS idx_sw_bpm_authorize_agent
    ON sw_bpm_authorize_rule (tenant_id, agent_id, status);

CREATE TABLE IF NOT EXISTS sw_bpm_communication (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    process_instance_id varchar(64)   not null,
    node_key          varchar(200)    not null,
    task_id           varchar(64),
    round_no          int             not null default 1,
    initiator_id      bigint          not null,
    receiver_id       bigint          not null,
    message           text,
    reply_message     text,
    status            varchar(20)     not null default 'PENDING',
    reply_time        timestamp
);

CREATE INDEX IF NOT EXISTS idx_sw_bpm_comm_task
    ON sw_bpm_communication (tenant_id, task_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_comm_instance_receiver
    ON sw_bpm_communication (tenant_id, process_instance_id, task_id, receiver_id);

CREATE TABLE IF NOT EXISTS sw_bpm_sign_record (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    process_instance_id varchar(64)   not null,
    node_key          varchar(200)    not null,
    task_id           varchar(64)     not null,
    sign_type         varchar(20)     not null,
    mode_type         varchar(10)     not null default 'SERIAL',
    operator_id       bigint          not null,
    participant_id    bigint          not null,
    seq_no            int             not null default 0,
    sign_status       varchar(20)     not null default 'PENDING',
    result_status     varchar(20),
    cancel_reason     varchar(500),
    original_task_id  varchar(64),
    original_status   varchar(30),
    detail            text
);

CREATE INDEX IF NOT EXISTS idx_sw_bpm_sign_task
    ON sw_bpm_sign_record (tenant_id, task_id);

CREATE TABLE IF NOT EXISTS sw_bpm_consensus_vote (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    process_instance_id varchar(64)   not null,
    node_key          varchar(200)    not null,
    task_id           varchar(64)     not null,
    actor_id          bigint          not null,
    outcome           varchar(20)     not null
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_vote_task_actor
    ON sw_bpm_consensus_vote (tenant_id, task_id, actor_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_vote_node
    ON sw_bpm_consensus_vote (tenant_id, process_instance_id, node_key);

-- ======== V72（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- V66 (bpm 分链): P60 I3 — 办理时限、受控节点函数与审批意见不可变快照
--
-- 方向：.../direction-stage-i3-manual-approval-first-party-process-designer.md §4.8—§4.10
--
-- 内容：
--   1. sw_bpm_approval_action 增列 opinion_form_snapshot —— 提交动作时的
--      意见表单定义不可变快照（等价引用），后续表单版本变化不改历史解释；
--      init_summary 已有 initialization_summary，不变。
--   2. 新表 sw_bpm_task_deadline —— 人工节点时限与受控自动动作的调度账本。
--      状态机由 UPDATE 原子认领推进，多实例下重复触发只产生一次效果。
--   3. 新表 sw_bpm_node_function —— 节点函数注册表：稳定 func_key + 版本
--      单调递增；发布期校验存在/启用/schema/允许节点/超时/失败策略；
--      生产路径无任意脚本执行（仅受控内置实现 + 版本冻结引用）。
--
-- 约束：
--   · 函数 (tenant_id, func_key, func_version) 唯一
--   · 时限记录对每任务一条（uk_task_deadline）
--   · 全部幂等：IF NOT EXISTS 守卫
-- ===================================================================

ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS opinion_form_snapshot text;

CREATE TABLE IF NOT EXISTS sw_bpm_task_deadline (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    process_instance_id varchar(64)   not null,
    node_key          varchar(200)    not null,
    task_id           varchar(64)     not null,
    due_at            timestamp       not null,
    remind_fired      smallint        not null default 0,
    escalation_fired  smallint        not null default 0,
    auto_action       varchar(20),
    auto_action_config text,
    run_state         varchar(20)     not null default 'PENDING',
    result_status     varchar(20),
    last_reason       varchar(500),
    handled_at        timestamp
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_deadline_task
    ON sw_bpm_task_deadline (tenant_id, task_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_deadline_due
    ON sw_bpm_task_deadline (run_state, due_at);

CREATE TABLE IF NOT EXISTS sw_bpm_node_function (
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
    config            text,
    allowed_nodes     text,
    timeout_ms        bigint          not null default 5000,
    failure_strategy  varchar(20)     not null default 'BLOCK',
    enabled           smallint        not null default 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_node_func
    ON sw_bpm_node_function (tenant_id, func_key, func_version);


-- I3 内建节点函数注册（func_tenant_admins / func_audit_trail v1；全局）
INSERT INTO sw_bpm_node_function (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes, timeout_ms, failure_strategy, enabled) SELECT 9001,'func_tenant_admins',1,'RESOLVE_PARTICIPANTS','func_tenant_admins','{"input":"context","output":"List<userId>","maxItems":100}','[]',5000,'FRAMEWORK',1 WHERE NOT EXISTS (SELECT 1 FROM sw_bpm_node_function WHERE id = 9001);
INSERT INTO sw_bpm_node_function (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes, timeout_ms, failure_strategy, enabled) SELECT 9002,'func_audit_trail',1,'HANDLE_RESULT','func_audit_trail','{"input":"nodeResult","output":"summaryResultVariables"}','[]',5000,'FRAMEWORK',1 WHERE NOT EXISTS (SELECT 1 FROM sw_bpm_node_function WHERE id = 9002);

-- ======== V73（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V67 (bpm 分链): P60 I3 — 审批动作与设计器权限按钮 + 角色授予
--
-- 方向：.../direction-stage-i3-manual-approval-first-party-process-designer.md §4.11
--
-- 内容：
--   挂流程定义菜单（parent=23）的 I3 动作按钮权限（menu_type=2）：
--     workflow:def:design        自研流程设计器页（设计/编辑图）
--     workflow:def:validate      草稿独立校验
--     workflow:def:suspend       发布版本挂起/激活
--   挂待办菜单（parent=20）的 I3 办理动作权限：
--     workflow:task:transfer     转办
--     workflow:task:delegate     委托
--     workflow:task:authorize    授权代理规则管理
--     workflow:task:add-sign     加签/补签
--     workflow:task:withdraw     发起人撤回
--     workflow:task:communicate  沟通征询
--     workflow:task:discard      无效实例废弃
--     workflow:task:manage       时限/催办/升级与自动动作管理
--   挂流程定义菜单（parent=23）：
--     workflow:def:publish 已有——不重复
--   普通办理（APPROVE/DISAPPROVE/RETURN/REJECT、意见录入）沿用既有
--   workflow:todo:view + 任务归属校验，不新增按钮码。
--
-- 约束：
--   · 按钮 id 使用 354-364（避开既有 1-353）
--   · 使用 sys_role_menu 结构 3600 + menu_id 移位，避开既有 3000-3499 区
--   · 越权动作的服务端授权以 @PreAuthorize + 任务归属校验为唯一权威
--   · 全部幂等：NOT EXISTS 守卫，可安全重复执行
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 354, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDesigner', '流程设计器页', false, 2,
       '', '', 'workflow:def:design', '', 21
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 354);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 355, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDefValidate', '流程草稿校验', false, 2,
       '', '', 'workflow:def:validate', '', 22
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 355);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 356, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDefSuspend', '发布版本挂起/激活', false, 2,
       '', '', 'workflow:def:suspend', '', 23
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 356);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 357, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskTransfer', '任务转办', false, 2,
       '', '', 'workflow:task:transfer', '', 31
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 357);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 358, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskDelegate', '任务委托', false, 2,
       '', '', 'workflow:task:delegate', '', 32
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 358);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 359, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskAuthorize', '授权代理规则', false, 2,
       '', '', 'workflow:task:authorize', '', 33
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 359);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 360, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskAddSign', '任务加签/补签', false, 2,
       '', '', 'workflow:task:add-sign', '', 34
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 360);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 361, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskWithdraw', '发起人撤回', false, 2,
       '', '', 'workflow:task:withdraw', '', 35
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 361);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 362, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskCommunicate', '沟通征询', false, 2,
       '', '', 'workflow:task:communicate', '', 36
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 362);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 363, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskDiscard', '实例废弃', false, 2,
       '', '', 'workflow:task:discard', '', 37
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 363);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 364, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskManage', '时限与自动动作管理', false, 2,
       '', '', 'workflow:task:manage', '', 38
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 364);

-- -------------------- 授予角色 2（管理员） --------------------
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT 3600 + m.id, current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND m.permission IN ('workflow:def:design', 'workflow:def:validate', 'workflow:def:suspend',
                       'workflow:task:transfer', 'workflow:task:delegate', 'workflow:task:authorize',
                       'workflow:task:add-sign', 'workflow:task:withdraw', 'workflow:task:communicate',
                       'workflow:task:discard', 'workflow:task:manage')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

-- ======== V74（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V74 (I3 G10b): 代理接管审计行与真实办理动作行共存。
-- 原 uk_sw_bpm_approval_task_actor(tenant, task, actor) 把 AUTHORIZE 的 PROXY_JOINED
-- 审计行与代理人后续真实 APPROVE 行判为重复，代理人实际办理时撞唯一键 500。
-- 迁移为 (tenant, task, actor, action) 四列唯一：同动作重复仍被幂等阻断，
-- 不同动作（PROXY_JOINED 审计 vs APPROVE 办理）各自保留一行。
drop index if exists uk_sw_bpm_approval_task_actor;
create unique index uk_sw_bpm_approval_task_actor_action
    on sw_bpm_approval_action (tenant_id, task_id, actor_id, action);

-- ======== V75（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V75 (I3 G13a/G13b): 节点函数调用审计（§4.9 运行必须完整审计）。
-- 每次参与人解析/结果处理调用（成功或失败）各写一行：函数身份、调用位置、
-- 结果或错误码、幂等键与耗时；生产任意脚本入口仍不存在。
create table sw_bpm_node_function_audit (
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
create index idx_sw_bpm_fn_audit_instance on sw_bpm_node_function_audit (process_instance_id);

-- 内建结果函数注册：func_result_echo v1（白名单变量写回证据链）
INSERT INTO sw_bpm_node_function (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes, timeout_ms, failure_strategy, enabled) SELECT 9003,'func_result_echo',1,'HANDLE_RESULT','func_result_echo','{"input":"nodeResult","output":"summaryResultVariables"}','[]',5000,'FRAMEWORK',1 WHERE NOT EXISTS (SELECT 1 FROM sw_bpm_node_function WHERE id = 9003);

-- ======== V76（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V76 (I4 §3.1): 动态并行分支冻结快照。
-- 进入节点时冻结来源部门、负责人与汇聚策略；运行中表单或组织变化不改写本表。
-- 无效对象（失效部门/负责人缺失/跨租户）落 CANCELED 行并记录原因，不静默跳过。
create table sw_bpm_dynamic_branch (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    node_key varchar(128) not null,
    branch_index int not null,
    source_type varchar(32),
    source_value varchar(512),
    dept_ids varchar(512),
    leader_id bigint,
    converge_mode varchar(16) not null,
    status varchar(20) not null,
    task_id varchar(64),
    cancel_reason varchar(512)
);
create index idx_sw_bpm_dyn_branch_instance on sw_bpm_dynamic_branch (process_instance_id, node_key);
create unique index uk_sw_bpm_dyn_branch on sw_bpm_dynamic_branch (tenant_id, process_instance_id, node_key, branch_index);

-- ======== V77（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V77 (I4 §3.2): 流程模板中心。
-- 模板是创建流程定义的受控来源：不直接改写已发布定义、运行实例或历史；
-- 派生定义通过 sw_bpm_process_def.source_template_id/version 保持可追溯。
-- 分级授权：scope_type=GLOBAL 全局可见；DEPT 仅 scope_dept_id 同部门可见/可复制。
create table sw_bpm_process_template (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    name varchar(200) not null,
    category varchar(100),
    description varchar(500),
    form_key varchar(200) not null,
    graph_json text,
    template_version int not null default 1,
    status varchar(20) not null default 'ENABLED',
    scope_type varchar(20) not null default 'GLOBAL',
    scope_dept_id bigint,
    source_def_id bigint,
    source_def_version int
);
create index idx_sw_bpm_tpl_category on sw_bpm_process_template (category);
create index idx_sw_bpm_tpl_scope on sw_bpm_process_template (scope_type, scope_dept_id);

alter table sw_bpm_process_def add column source_template_id bigint;
alter table sw_bpm_process_def add column source_template_version int;

-- ======== V78（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V78 (I4 §3.3): 实例级运营干预审计。挂起/恢复/终止/迁移办理人逐项授权并记录
-- 操作者、原因、对象、前后状态与受影响任务；与定义级挂起/激活互不混同。
create table sw_bpm_instance_intervention (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    action varchar(20) not null,
    operator_id bigint not null,
    reason varchar(500),
    before_state varchar(20),
    after_state varchar(20),
    from_assignee bigint,
    to_assignee bigint,
    affected_tasks int,
    intervened_at timestamp not null
);
create index idx_sw_bpm_itv_instance on sw_bpm_instance_intervention (process_instance_id);

-- ======== V79（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V79 (I4 §3.6): 流程交接批次与逐项清单。
-- 只迁移选定范围内尚未完成的可办理任务；代理规则显式勾选且通过校验才随迁；
-- 抄送/已办/历史意见/已过期规则不迁移；历史办理人与意见零改写。
create table sw_bpm_handover (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    from_user_id bigint not null,
    to_user_id bigint not null,
    scope_def_keys varchar(1000),
    include_proxy_rules boolean not null default false,
    status varchar(20) not null,
    total_items int,
    migrated_items int,
    failed_items int,
    operator_id bigint not null,
    executed_at timestamp not null
);
create index idx_sw_bpm_handover_users on sw_bpm_handover (from_user_id, to_user_id);

create table sw_bpm_handover_item (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    handover_id bigint not null,
    task_id varchar(64),
    process_instance_id varchar(64),
    item_type varchar(20) not null,
    before_assignee bigint,
    after_assignee bigint,
    result varchar(30) not null,
    fail_reason varchar(500),
    rule_id bigint
);
create index idx_sw_bpm_handover_item on sw_bpm_handover_item (handover_id);

-- ======== V80（来源: sw-biz/sw-biz-openapi/sw-biz-openapi-biz，副本 1 处已去重） ========
-- V1 (I4 §3.4): 开放接口底座 —— 应用身份（绑定租户/授权范围/代理用户）、
-- 防重放 nonce、幂等键登记、出站回调日志。
create table sw_openapi_app (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    app_name varchar(200) not null,
    secret_hash varchar(128) not null,
    scopes varchar(200) not null,
    status varchar(20) not null default 'ENABLED',
    act_as_user_id bigint not null,
    callback_url varchar(500),
    callback_secret_hash varchar(128)
);
create unique index uk_sw_openapi_app on sw_openapi_app (app_id);

create table sw_openapi_nonce (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    nonce varchar(128) not null,
    expire_at timestamp not null
);
create unique index uk_sw_openapi_nonce on sw_openapi_nonce (app_id, nonce);

create table sw_openapi_idempotency (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    idem_key varchar(200) not null,
    result_ref varchar(64)
);
create unique index uk_sw_openapi_idem on sw_openapi_idempotency (app_id, idem_key);

create table sw_openapi_callback_log (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    event varchar(50) not null,
    biz_ref varchar(64) not null,
    url varchar(500),
    attempt int not null,
    status varchar(20) not null,
    response_summary varchar(500),
    delivered_at timestamp
);
create index idx_sw_openapi_cb_app on sw_openapi_callback_log (app_id, biz_ref, event);

-- ======== V81（来源: sw-biz/sw-biz-openapi/sw-biz-openapi-biz，副本 1 处已去重） ========
-- V81 (I4 §3.4): dev 演示开放应用种子（仅本机联调用，prod 不引用本迁移）。
-- secret 原文仅存 SHA-256 摘要：i4-dev-openapi-secret
-- callback_url 指向本机受控对端接收器。
insert into sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version,
                            app_id, app_name, secret_hash, scopes, status, act_as_user_id,
                            callback_url, callback_secret_hash)
select 1, current_timestamp, current_timestamp, 0, 0, 0,
       'i4-demo-app', 'I4 演示外部应用', 'fcba56c261010a10a795ef40b347938165fcf6b69cbe9ab60eded236849dd49a',
       'PROCESS_START,PROCESS_QUERY,TASK_HANDLE', 'ENABLED', 1,
       'http://localhost:9999/i4/callback', null
where not exists (select 1 from sw_openapi_app where app_id = 'i4-demo-app');

-- ======== V82（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V82 (I4 §3.3/§3.2/§3.5/§3.6): 流程运营菜单与按钮权限种子。
-- 挂在既有「流程管理」域（parent=7）之下：
--   365 批量审批（workflow:task:batch）
--   366 流程交接（workflow:handover:manage）
--   367 流程分析（workflow:analytics:view，与 monitor:view 同权，占独立菜单项）
--   368 监控干预页（workflow:monitor:view）+ 369 干预操作按钮
--   370 模板中心（workflow:template:list）+ 371—375 view/create/save/copy/delete 按钮
-- 注意：I4 修订前本迁移曾占用 360—364，与 V73 的按钮段（354—364）冲突，
-- NOT EXISTS 守卫会静默吞掉监控/模板种子；已改用 368+ 空闲段（修复记录见回执 03）。
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 365, current_timestamp, current_timestamp, 0, 0, 7, 'BatchApproval', '批量审批', false, 1,
       'workflow/batch-approval', 'workflow/views/BatchApproval', 'workflow:task:batch', 'Checked', 33
where not exists (select 1 from sys_menu where id = 365);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 366, current_timestamp, current_timestamp, 0, 0, 7, 'TaskHandover', '流程交接', false, 1,
       'workflow/handover', 'workflow/views/TaskHandover', 'workflow:handover:manage', 'Position', 34
where not exists (select 1 from sys_menu where id = 366);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 367, current_timestamp, current_timestamp, 0, 0, 7, 'ProcessAnalytics', '流程分析', false, 1,
       'workflow/analytics', 'workflow/views/ProcessAnalytics', 'workflow:monitor:view', 'TrendCharts', 35
where not exists (select 1 from sys_menu where id = 367);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 368, current_timestamp, current_timestamp, 0, 0, 7, 'InstanceMonitor', '实例监控干预', false, 1,
       'workflow/monitor', 'workflow/views/InstanceMonitor', 'workflow:monitor:view', 'Monitor', 31
where not exists (select 1 from sys_menu where id = 368);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 369, current_timestamp, current_timestamp, 0, 0, 368, 'InstanceMonitorManage', '监控干预操作', false, 2,
       '', '', 'workflow:monitor:manage', '', 1
where not exists (select 1 from sys_menu where id = 369);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 370, current_timestamp, current_timestamp, 0, 0, 7, 'TemplateCenter', '流程模板中心', false, 1,
       'workflow/templates', 'workflow/views/TemplateCenter', 'workflow:template:list', 'Files', 32
where not exists (select 1 from sys_menu where id = 370);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 371, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateView', '模板查看', false, 2,
       '', '', 'workflow:template:view', '', 1
where not exists (select 1 from sys_menu where id = 371);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 372, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateCreate', '模板新建', false, 2,
       '', '', 'workflow:template:create', '', 2
where not exists (select 1 from sys_menu where id = 372);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 373, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateSave', '模板编辑', false, 2,
       '', '', 'workflow:template:save', '', 3
where not exists (select 1 from sys_menu where id = 373);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 374, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateCopy', '模板复制', false, 2,
       '', '', 'workflow:template:copy', '', 4
where not exists (select 1 from sys_menu where id = 374);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 375, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateDelete', '模板删除', false, 2,
       '', '', 'workflow:template:delete', '', 5
where not exists (select 1 from sys_menu where id = 375);

-- ======== V83（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V83 (I5): sw_form_def form_key 唯一约束收敛为租户级
-- ===================================================================
-- 目的：相同业务 formKey 允许不同租户独立使用，同一租户内保持唯一（I5 §3.1）。
-- 语义：
--   1. uk_sw_form_def_form_key 从 (form_key, deleted) 改为 (tenant_id, form_key, deleted)。
--   2. sw_form_config(table_name) 保持数据库全局唯一不变——table_name 是动态宽表
--      物理表名（sw_form_{nanoId}），属于数据库全局命名空间；nanoId 由服务端随机
--      生成，天然全局无碰撞，不降级为租户级（I5 §3.1：不得把物理表名唯一约束改成
--      租户级而制造两个配置争用同一物理表）。
--   3. 历史数据全部 tenant_id=0（V1 默认），改约束不产生冲突行；迁移不改写任何
--      历史行的归属租户（I5 §7 兼容边界）。
-- 兼容：前向迁移，仅重建索引；H2 与 PostgreSQL 逐字节语义一致。
-- ===================================================================

DROP INDEX IF EXISTS uk_sw_form_def_form_key;
CREATE UNIQUE INDEX uk_sw_form_def_form_key ON sw_form_def (tenant_id, form_key, deleted);

-- ======== V84（来源: sw-biz/sw-biz-system/sw-biz-system-biz，副本 1 处已去重） ========
-- ===================================================================
-- V84 (I5): 第三方 SSO 数据模型 — 配置 / 绑定 / 授权状态 / 审计
-- ===================================================================
-- 三个 Provider（企业微信/飞书/钉钉）共用同一套载体，Provider 以字符串字面量区分
-- （WECOM / FEISHU / DINGTALK），不建立第二套身份/会话/授权体系。
--
-- 表：
--   sys_sso_provider_config  租户级 Provider 配置（凭据 AES-GCM 落库加密）
--   sys_sso_user_binding     外部身份 → 本地账号绑定（(provider,tenant,external) 唯一）
--   sys_sso_auth_state       一次性授权 state（防重放：唯一消费 + 过期）
--   sys_sso_audit_record     最小持久审计（脱敏，不含票据/秘密）
--
-- 全部表带标准基列（tenant_id/deleted/version/审计列）；敏感列（app_secret）
-- 存 AES-256-GCM 密文，不存明文；审计只存摘要/掩码，不存 code/token/secret。
-- ===================================================================

create table sys_sso_provider_config (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    provider        varchar(20)     not null,
    enabled         smallint        not null default 0,
    app_id          varchar(200)    not null,
    app_secret_enc  varchar(1000)   not null,
    extra_config    text,
    redirect_path   varchar(500)
);
create unique index uk_sys_sso_provider_config
    on sys_sso_provider_config (tenant_id, provider, deleted);

create table sys_sso_user_binding (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    provider        varchar(20)     not null,
    external_id     varchar(200)    not null,
    external_digest varchar(64)     not null,
    user_id         bigint          not null,
    bind_status     varchar(20)     not null default 'ACTIVE'
);
create unique index uk_sys_sso_binding_external
    on sys_sso_user_binding (provider, tenant_id, external_id, deleted);
create unique index uk_sys_sso_binding_user
    on sys_sso_user_binding (provider, tenant_id, user_id, deleted);
create index idx_sys_sso_binding_user_all on sys_sso_user_binding (user_id);

create table sys_sso_auth_state (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    state_value     varchar(128)    not null,
    provider        varchar(20)     not null,
    nonce           varchar(128),
    redirect_path   varchar(500),
    consumed        smallint        not null default 0,
    expire_at       timestamp       not null
);
create unique index uk_sys_sso_state_value on sys_sso_auth_state (state_value);
create index idx_sys_sso_state_expire on sys_sso_auth_state (expire_at);

create table sys_sso_audit_record (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    provider        varchar(20)     not null,
    event_type      varchar(40)     not null,
    result          varchar(20)     not null,
    actor_id        bigint,
    local_user_id   bigint,
    external_digest varchar(64),
    detail          varchar(1000)
);
create index idx_sys_sso_audit_query
    on sys_sso_audit_record (tenant_id, provider, event_type, create_time);

-- ======== V85（来源: sw-bootstrap，副本 1 处已去重） ========
-- I5 复验（G2）：真实迁移链补默认租户种子行。
-- V2 种子只含 id=1 的 'default' 展示租户；I5 租户有效性校验上线后，
-- 种子账号（tenant_id=0）在真实启动的登录/装载路径会被判租户不存在而拒绝。
-- 本迁移按幂等方式补 id=0 默认租户行（默认租户常量在其边界真实成立），
-- 不改写任何既有行；已存在的 id=0 行保持原值。
insert into sys_tenant (id, create_time, update_time, deleted, tenant_id, version,
                        name, code, status, description)
values (0, current_timestamp, current_timestamp, 0, 0, 0,
        '默认租户', 'tenant-0', 0, 'I5 收口：系统默认租户（种子账号所属租户）')
on conflict (id) do nothing;

-- ======== V86（来源: sw-biz/sw-biz-system/sw-biz-system-biz，副本 1 处已去重） ========
-- I5 复验（G6）：同一 Provider 应用归属唯一租户。
-- 方向 §C13：同一 Provider 应用/组织中的同一稳定外部主体不能跨租户重复绑定。
-- 应用级唯一是前提：同一 (provider, app_id) 只允许一个租户登记，
-- 从而外部主体在各租户的绑定域天然不交叉。
create unique index uk_sys_sso_provider_app
    on sys_sso_provider_config (provider, app_id, deleted);

-- ======== V87（来源: sw-biz/sw-biz-system/sw-biz-system-biz，副本 1 处已去重） ========
-- I5 复验 03（G6a）：同一 Provider 稳定外部主体（摘要）全局仅允许一个租户绑定。
-- external_id 列自 iteration-03 起存摘要（与 external_digest 同值），明文不落 SQL。
create unique index uk_sys_sso_binding_digest_global
    on sys_sso_user_binding (provider, external_digest, deleted);

-- ======== V89（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V89 (I6): 通知与版本收口 — 通知域数据模型
-- 适用 H2 与 PostgreSQL（同一迁移身份，双目录逐字节一致）
-- 内容：
--   1) 模板事件/渠道/白名单扩展 + 不可变版本追加表
--   2) 通知规则（事件开关/接收人规则/渠道顺序/失败策略）
--   3) 用户订阅（普通用户可选偏好；强制事件不由订阅关闭）
--   4) 租户级渠道启停登记（秘密不入库，只存非秘密摘要）
--   5) 消息行统一业务身份（事件类型/发生次序/模板版本/深链/重试状态）
--   6) 尝试流水扩展（失败分类/起止时间）
-- 约束：表前缀 sw_notify_；不降低既有 8 基列/租户隔离语义；
--       全部列为可空或带默认值，不重写历史数据。
-- ===================================================================

-- ---------- 1. 模板扩展 ----------
alter table sw_notify_template add column event_type varchar(40) not null default 'SYSTEM';
alter table sw_notify_template add column channel varchar(40) not null default 'IN_APP';
alter table sw_notify_template add column variables_allowed varchar(1000);
alter table sw_notify_template add column jump_ref varchar(200);

-- ---------- 2. 模板版本追加（发布后不可改写） ----------
create table sw_notify_template_version (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    template_id       bigint          not null,
    template_version  int             not null default 1,
    event_type        varchar(40)     not null default 'SYSTEM',
    channel           varchar(40)     not null default 'IN_APP',
    title_template    varchar(200)    not null,
    content_template  text            not null,
    variables_allowed varchar(1000),
    jump_ref          varchar(200),
    status            varchar(20)     not null default 'RELEASED'
);
create unique index uk_sw_notify_tpl_ver on sw_notify_template_version (tenant_id, template_id, template_version);
create index idx_sw_notify_tpl_ver_template on sw_notify_template_version (template_id);

-- ---------- 3. 通知规则 ----------
create table sw_notify_rule (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    rule_code         varchar(100)    not null,
    name              varchar(100)    not null,
    event_type        varchar(40)     not null,
    channel_priority  varchar(200)    not null default 'IN_APP',
    recipient_rule    varchar(500)    not null,
    required_flag     smallint        not null default 0,
    failure_policy    varchar(20)     not null default 'RETRY',
    enabled           smallint        not null default 1,
    remark            varchar(500)
);
create unique index uk_sw_notify_rule_code on sw_notify_rule (tenant_id, rule_code, deleted);
create index idx_sw_notify_rule_event on sw_notify_rule (tenant_id, event_type);

-- ---------- 4. 用户订阅（可选偏好） ----------
create table sw_notify_subscription (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    user_id           bigint          not null,
    event_type        varchar(40)     not null,
    channel           varchar(40)     not null default 'IN_APP',
    enabled           smallint        not null default 1
);
create unique index uk_sw_notify_sub on sw_notify_subscription (tenant_id, user_id, event_type, channel, deleted);

-- ---------- 5. 租户级渠道启停（秘密不入库） ----------
create table sw_notify_channel_config (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    channel           varchar(40)     not null,
    enabled           smallint        not null default 0,
    sender_display    varchar(200),
    config_summary    varchar(500)
);
create unique index uk_sw_notify_channel on sw_notify_channel_config (tenant_id, channel, deleted);

-- ---------- 6. 消息行统一业务身份与投递状态扩展 ----------
alter table sw_notify_message add column event_type varchar(40) not null default 'SYSTEM';
alter table sw_notify_message add column occurrence_no bigint not null default 1;
alter table sw_notify_message add column template_id bigint;
alter table sw_notify_message add column template_version int;
alter table sw_notify_message add column link_type varchar(32);
alter table sw_notify_message add column link_id varchar(64);
alter table sw_notify_message add column retry_count int not null default 0;
alter table sw_notify_message add column next_retry_time timestamp;
alter table sw_notify_message add column failure_class varchar(40);
alter table sw_notify_message add column receipt_digest varchar(200);

-- 一次业务通知稳定身份：租户+事件类型+业务对象+发生次序+接收人+渠道
-- （biz_id 为空的系统通告不受唯一键约束，保持可重复广播语义）
create unique index uk_sw_notify_msg_identity on sw_notify_message (tenant_id, event_type, biz_id, occurrence_no, recipient_id, channel);
create index idx_sw_notify_msg_retry on sw_notify_message (tenant_id, event_type, biz_id);

-- ---------- 7. 尝试流水扩展 ----------
alter table sw_notify_send_attempt add column failure_class varchar(40);
alter table sw_notify_send_attempt add column started_at timestamp;
alter table sw_notify_send_attempt add column finished_at timestamp;

-- ======== V90（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V90 (I6): 通知管理端菜单与按钮权限种子
-- 适用 H2 与 PostgreSQL（同一迁移身份，双目录逐字节一致）
-- 在既有「通知」目录（id=6）下登记 I6 新管理/偏好页面：
--   218 通知规则   notify/views/NotifyRuleList      notify:rule:view
--   219 渠道配置   notify/views/NotifyChannelList   notify:channel:view
--   220 订阅偏好   notify/views/NotifyPreference    notify:preference
--   221 规则管理按钮   notify:rule:manage
--   222 渠道管理按钮   notify:channel:manage
-- 幂等：NOT EXISTS 防重入；未改动既有菜单。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 218, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyRule', '通知规则', false, 1, 'rule', 'notify/views/NotifyRuleList', 'notify:rule:view', 'Setting', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 218);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 219, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyChannel', '渠道配置', false, 1, 'channel', 'notify/views/NotifyChannelList', 'notify:channel:view', 'Link', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 219);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 220, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyPreference', '订阅偏好', false, 1, 'preference', 'notify/views/NotifyPreference', 'notify:preference', 'ToggleOn', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 220);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 221, current_timestamp, current_timestamp, 0, 0, 218, 'NotifyRuleManage', '规则新建/编辑/启停/删除', 2, '', '', 'notify:rule:manage', '', 1, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 221);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 222, current_timestamp, current_timestamp, 0, 0, 219, 'NotifyChannelManage', '渠道启停/重试策略', 2, '', '', 'notify:channel:manage', '', 1, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 222);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 223, current_timestamp, current_timestamp, 0, 0, 0, 'NotifyRecordDetail', '记录必要详情（含正文与尝试流水，独立权限并审计）', 2, '', '', 'notify:record:detail', '', 2, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 223);

-- ======== V91（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V91 (I6): 通知 Provider 主体映射权威（安全、租户隔离、可失效、可判定重复绑定）
-- 适用于 H2 与 PostgreSQL（同一迁移身份，双目录逐字节一致）
-- 关系：
--   与 I5 SSO 绑定身份独立；不复用 sys_sso_user_binding.external_id 摘要语义
--   （I5 摘要不可反解：SSO 复用摘要不能充当通知明文主体）。
-- 秘密：subject_cipher 存以工程 cipher 服务加密的密文（不落明文/SQL 日志）。
-- ===================================================================

create table sw_notify_subject_binding (
    id              bigint       not null primary key,
    create_time     timestamp    not null default current_timestamp,
    update_time     timestamp    not null default current_timestamp,
    create_by       bigint,
    update_by       bigint,
    deleted         smallint     not null default 0,
    tenant_id       bigint       not null default 0,
    version         bigint       not null default 0,
    user_id         bigint       not null,
    provider        varchar(40)  not null,
    subject_cipher  varchar(500) not null,
    subject_digest  varchar(128) not null,
    bind_status     varchar(20)  not null default 'ACTIVE'
);

create unique index uk_sw_notify_subject_binding
    on sw_notify_subject_binding (tenant_id, user_id, provider, deleted);
create index idx_sw_notify_subject_provider
    on sw_notify_subject_binding (tenant_id, provider, deleted);

-- ======== V92（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V92 (I6): sw_notify 模板标志列统一为 BOOLEAN（双方言同版本身份）
-- 修复真实 PostgreSQL 行为缺陷：V38 起模板 enabled 为 smallint，
-- 而实体（MyBatis-Plus Boolean 字段）在 PG 写入 boolean 触发类型错误。
-- H2 因宽松类型转换未在单库测试中暴露；G2e-T 真实 PG 发送链暴露。
-- 幂等：仅当列仍为 smallint 时执行转换；默认值沿用 1→true 语义。
-- ===================================================================
do $$
begin
  if exists (select 1 from information_schema.columns where table_name = 'sw_notify_template' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_template alter column enabled drop default;
    alter table sw_notify_template alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_template alter column enabled set default true;
  end if;
end $$;

-- ======== V93（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V93 (I6): sw_notify 规则/订阅/渠道标志列统一为 BOOLEAN（双方言同版本身份）
-- ===================================================================
-- V92 只覆盖 sw_notify_template.enabled。同一实体口径（MyBatis-Plus Boolean
-- 字段）在 sw_notify_rule / sw_notify_subscription / sw_notify_channel_config
-- 上仍为 smallint，真实 PostgreSQL 对 smallint 列与 boolean 参数/值的比较和
-- 写入都直接报错（operator does not exist: smallint = boolean /
-- column "enabled" is of type smallint but expression is of type boolean），
-- 命中规则解析、规则启停、订阅偏好保存等生产路径。
-- H2 对 smallint 与 boolean 的比较做隐式转换，单库测试不会暴露该差异。
-- 幂等：仅当列仍为 smallint 时转换；默认值按 1→true、0→false 语义保持。
-- ===================================================================
do $$
begin
  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_rule' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_rule alter column enabled drop default;
    alter table sw_notify_rule alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_rule alter column enabled set default true;
  end if;

  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_subscription' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_subscription alter column enabled drop default;
    alter table sw_notify_subscription alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_subscription alter column enabled set default true;
  end if;

  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_channel_config' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_channel_config alter column enabled drop default;
    alter table sw_notify_channel_config alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_channel_config alter column enabled set default false;
  end if;

  -- V92 已覆盖模板列；此处兜底 V92 未能生效的环境，保持同版本终态口径一致。
  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_template' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_template alter column enabled drop default;
    alter table sw_notify_template alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_template alter column enabled set default true;
  end if;
end $$;

-- ======== V94（来源: sw-bootstrap，副本 1 处已去重） ========
-- V94: V011-BUG-002 — 租户级工作台卡片类型与低代码元数据契约
-- 卡片类型只保存安全 renderer_key；renderer_key 由 Web 注册表解析，不是可执行路径。

CREATE TABLE IF NOT EXISTS sys_workspace_card_type (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    type_code       varchar(64)     not null,
    display_name    varchar(100)    not null,
    renderer_key    varchar(64)     not null,
    metadata_json   text            not null default '{}',
    default_span    smallint        not null default 1,
    default_order   integer         not null default 1,
    status          smallint        not null default 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_workspace_card_type
    ON sys_workspace_card_type (tenant_id, type_code, deleted);

INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9401, current_timestamp, current_timestamp, 0, 0, 0, 'todo', '我的待办', 'todo', '{}', 1, 2, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'todo' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9402, current_timestamp, current_timestamp, 0, 0, 0, 'stats', '统计概览', 'stats', '{}', 2, 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'stats' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9403, current_timestamp, current_timestamp, 0, 0, 0, 'favoriteItems', '快捷发起', 'favorites', '{}', 1, 3, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'favoriteItems' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9404, current_timestamp, current_timestamp, 0, 0, 0, 'activity', '业务动态', 'activity', '{}', 1, 4, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'activity' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9405, current_timestamp, current_timestamp, 0, 0, 0, 'efficiency', '流程效能', 'efficiency', '{}', 1, 5, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'efficiency' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9406, current_timestamp, current_timestamp, 0, 0, 0, 'drafts', '草稿', 'drafts', '{}', 1, 6, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'drafts' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9407, current_timestamp, current_timestamp, 0, 0, 0, 'messages', '消息', 'messages', '{}', 1, 7, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'messages' AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 326, current_timestamp, current_timestamp, 0, 0, 5, 'WorkspaceCardTypes', '工作台卡片', false, 1,
       'workflow/workspace-card-types', 'workflow/views/WorkspaceCardTypeList', 'workflow:workspace:manage', 'Grid', 26
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 326);

-- ======== V95（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V95: 管理后台信息架构规整（表单管理 / 流程管理 / 路径规范化）
--
-- 背景（现状由 dev 库 sys_menu 实测）：
--   1) 目录标题不达意：「低代码」应为「表单管理」，「流程引擎」应为「流程管理」；
--      叶子「低代码概览」就是表单列表页，且路径 form/overview 与应用内真实入口
--      /form/form-def-list（表单设计器「返回表单列表」也用它）不一致；
--   2) 5 个流程页面错挂在「智能体」目录下（实例监控干预/流程模板中心/批量审批/
--      流程交接/流程分析），后台看起来「流程管理」里只有监控与定义；
--   3) 部分叶子存的是**裸段名**（dict/user/role/dept/post、inbox/template/batch-send/
--      record/preference/channel、graph-def/model/tool）。菜单叶子是**平铺注册**成路由的
--      （buildRoutesFromMenu 把子节点按自身 path 直接挂到根），因此裸段名会变成根路径
--      /dict、/user、/inbox…，与「/system/xxx」「/notify/xxx」的预期不符，也让
--      前后台归属判定失准（收件箱本属前台 notify/inbox，却因路径是 inbox 被判成后台）。
--
-- 约束：
--   · 只改展示与归类字段（title / path / parent_id / sort）；不动 id、name（= 路由名，
--     前端标题键映射与既有测试依赖它）、component、permission、hidden 与按钮行，
--     故权限点与既有角色授权不受影响；
--   · 幂等：每条 UPDATE 均带「旧值守卫」，重复执行无副作用。
-- ===================================================================

-- 1) 目录更名
UPDATE sys_menu SET title = '表单管理', update_time = current_timestamp
WHERE id = 2 AND title = '低代码';

UPDATE sys_menu SET title = '流程管理', update_time = current_timestamp
WHERE id = 5 AND title = '流程引擎';

-- 2) 低代码概览 → 表单列表（路径对齐应用内真实入口 /form/form-def-list）
UPDATE sys_menu SET title = '表单列表', path = 'form/form-def-list', update_time = current_timestamp
WHERE id = 3 AND path = 'form/overview';

-- 3) 流程管理：把流程实例/监控的措辞对齐后台语义
UPDATE sys_menu SET title = '流程实例', update_time = current_timestamp
WHERE id = 22 AND title = '流程监控';

-- 4) 系统管理子页：裸段名 → 全路径
UPDATE sys_menu SET path = 'system/dict', update_time = current_timestamp WHERE id = 10 AND path = 'dict';
UPDATE sys_menu SET path = 'system/user', update_time = current_timestamp WHERE id = 11 AND path = 'user';
UPDATE sys_menu SET path = 'system/role', update_time = current_timestamp WHERE id = 12 AND path = 'role';
UPDATE sys_menu SET path = 'system/dept', update_time = current_timestamp WHERE id = 13 AND path = 'dept';
UPDATE sys_menu SET path = 'system/post', update_time = current_timestamp WHERE id = 14 AND path = 'post';

-- 5) 通知子页：裸段名 → 全路径（收件箱归前台，路径对齐 PORTAL_MENU_PATHS 'notify/inbox'）
UPDATE sys_menu SET path = 'notify/inbox', update_time = current_timestamp WHERE id = 215 AND path = 'inbox';
UPDATE sys_menu SET path = 'notify/template', update_time = current_timestamp WHERE id = 216 AND path = 'template';
UPDATE sys_menu SET path = 'notify/batch-send', update_time = current_timestamp WHERE id = 218 AND path = 'batch-send';
UPDATE sys_menu SET path = 'notify/record', update_time = current_timestamp WHERE id = 324 AND path = 'record';
UPDATE sys_menu SET path = 'notify/preference', update_time = current_timestamp WHERE id = 220 AND path = 'preference';
UPDATE sys_menu SET path = 'notify/channel', update_time = current_timestamp WHERE id = 328 AND path = 'channel';

-- 6) 智能体子页：裸段名 → 全路径
UPDATE sys_menu SET path = 'agent/graph-def', update_time = current_timestamp WHERE id = 15 AND path = 'graph-def';
UPDATE sys_menu SET path = 'agent/model', update_time = current_timestamp WHERE id = 209 AND path = 'model';
UPDATE sys_menu SET path = 'agent/tool', update_time = current_timestamp WHERE id = 212 AND path = 'tool';

-- 7) 流程页面归位：从「智能体」(id=7) 收回「流程管理」(id=5)，排在流程实例/流程定义之后
UPDATE sys_menu SET parent_id = 5, sort = 50, update_time = current_timestamp WHERE id = 368 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 60, update_time = current_timestamp WHERE id = 370 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 70, update_time = current_timestamp WHERE id = 365 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 80, update_time = current_timestamp WHERE id = 366 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 90, update_time = current_timestamp WHERE id = 367 AND parent_id = 7;
-- 8) 开放接口、文件管理并入「系统管理」（不再单列顶层分区）
UPDATE sys_menu SET parent_id = 1, sort = 60, update_time = current_timestamp WHERE id = 9 AND parent_id = 0;
UPDATE sys_menu SET parent_id = 1, sort = 70, update_time = current_timestamp WHERE id = 16 AND parent_id = 0;

-- 9) 表单设计是设计器内部页签（表单列表 → 编辑进入），不在侧栏单列入口；
--    hidden 只约束侧栏，静态路由 /form/designer/:id 深链照常可用。
UPDATE sys_menu SET hidden = true, update_time = current_timestamp WHERE id = 4 AND hidden = false;

-- ======== V96（来源: sw-bootstrap，副本 1 处已去重） ========
-- Phase 4 可靠业务事件（BAO-05）：IoT 流程触发的恢复身份列 + OpenAPI 回调持久任务。
-- 说明：IoT 触发原有 PENDING/SUCCESS/FAILED 状态与唯一幂等键不变，本迁移补齐恢复所需的
--       发起目标身份（process_template_key 等）与重试预算列；回调任务表把“回调意图”持久化，
--       使业务提交后进程退出仍可恢复投递（原实现只在进程内重试 3 次后仅留日志）。

alter table sw_iot_process_trigger add column if not exists process_template_key varchar(128);
alter table sw_iot_process_trigger add column if not exists trigger_source varchar(20);
alter table sw_iot_process_trigger add column if not exists configured_by bigint;
alter table sw_iot_process_trigger add column if not exists retry_count int not null default 0;
alter table sw_iot_process_trigger add column if not exists next_retry_time timestamp;
create index if not exists idx_sw_iot_trigger_recovery on sw_iot_process_trigger (status, next_retry_time);

create table if not exists sw_openapi_callback_task (
    id              bigint       not null primary key,
    tenant_id       bigint       not null default 0,
    version         bigint       not null default 0,
    deleted         smallint     not null default 0,
    create_time     timestamp    not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp    not null default current_timestamp,
    update_by       bigint,
    app_id          varchar(64)  not null,
    event           varchar(50)  not null,
    biz_ref         varchar(64)  not null,
    status          varchar(20)  not null default 'PENDING',
    attempts        int          not null default 0,
    next_retry_time timestamp,
    last_error      varchar(500),
    delivered_at    timestamp
);
create unique index if not exists uk_sw_openapi_cb_task on sw_openapi_callback_task (tenant_id, app_id, event, biz_ref);
create index if not exists idx_sw_openapi_cb_task_due on sw_openapi_callback_task (status, next_retry_time);

-- ======== V97（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V97: V012-BUG-011/017/019 —— 菜单信息架构与租户名称唯一化
--
-- 019：区分 V2(id=1, code=default) 与 V85(id=0, code=tenant-0) 两个同名
--      「默认租户」，使登录页租户名称精确解析可用（重名 fail-closed 已实现）。
-- 017：系统管理组子项按 用户/部门/角色/岗位/字典/菜单/文件/开放接口 排序；
--      字典管理转两级目录（字典类型 + 字典数据管理）。
-- 011：新增菜单管理入口（icon/title/sort/hidden 受控配置）。
--
-- 约束：全部幂等（UPDATE 带条件 / INSERT 带 NOT EXISTS 守卫）；不 seed sys_role_menu。
-- ===================================================================

-- 1) 019：租户名称唯一化
UPDATE sys_tenant SET name = '演示租户', update_time = current_timestamp
WHERE id = 1 AND code = 'default' AND name = '默认租户';

-- 2) 017：系统管理组排序
UPDATE sys_menu SET sort = 10, update_time = current_timestamp WHERE id = 11;
UPDATE sys_menu SET sort = 20, update_time = current_timestamp WHERE id = 13;
UPDATE sys_menu SET sort = 30, update_time = current_timestamp WHERE id = 12;
UPDATE sys_menu SET sort = 40, update_time = current_timestamp WHERE id = 14;
UPDATE sys_menu SET sort = 50, update_time = current_timestamp WHERE id = 10;
UPDATE sys_menu SET sort = 70, update_time = current_timestamp WHERE id = 16;
UPDATE sys_menu SET sort = 80, update_time = current_timestamp WHERE id = 9;

-- 3) 017：字典两级 —— 字典管理转目录，子项 字典类型/字典数据管理
UPDATE sys_menu SET menu_type = 0, component = NULL, icon = 'Collection', update_time = current_timestamp
WHERE id = 10;

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 400, current_timestamp, current_timestamp, 0, 0, 10, 'DictType', '字典类型', false, 1, 'system/dict', 'system/views/DictTypeList', 'system:dict:view', 'Collection', 10
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 400);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 401, current_timestamp, current_timestamp, 0, 0, 10, 'DictData', '字典数据管理', false, 1, 'system/dict-data', 'system/views/DictDataList', 'system:dict:view', 'Document', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 401);

-- 4) 011：菜单管理入口
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 410, current_timestamp, current_timestamp, 0, 0, 1, 'MenuManage', '菜单管理', false, 1, 'system/menu', 'system/views/MenuList', 'system:menu:manage', 'Menu', 60
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 410);

-- ======== V98（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V98: V012-BUG-017 修正 —— 字典管理目录 path 与子项去重
--
-- V97 将 id=10「字典管理」转为目录（menu_type=0），但其 path 仍是 V95
-- 设置的 'system/dict'，与子项 400（字典类型，path='system/dict'）完全
-- 相同：目录 redirect 注册在 /system/dict 且首叶也是 /system/dict，
-- 构成自指重定向（路由无限循环，初始导航永不就绪）。
-- 本迁移把目录 path 改为独立前缀 'system/dict-manage'；子项 400 保持
-- 'system/dict'（既有深链继续可达字典类型页），401 保持 'system/dict-data'。
-- 幂等：仅当当前为冲突值时更新。
-- ===================================================================
UPDATE sys_menu SET path = 'system/dict-manage', update_time = current_timestamp
WHERE id = 10 AND path = 'system/dict' AND menu_type = 0;

-- ======== V99（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V99: V012-BUG-009 —— 流程收藏表
--
-- 用户级流程收藏（收藏置顶/常用/最近使用配套）。唯一键 (tenant_id, user_id,
-- process_key)；process_name 为收藏时快照展示名。幂等创建。
-- ===================================================================
CREATE TABLE IF NOT EXISTS sw_bpm_process_favorite (
    id           bigint       NOT NULL,
    create_time  timestamp    NOT NULL DEFAULT current_timestamp,
    update_time  timestamp    NOT NULL DEFAULT current_timestamp,
    deleted      smallint     NOT NULL DEFAULT 0,
    version      bigint       NOT NULL DEFAULT 0,
    tenant_id    bigint       NOT NULL DEFAULT 0,
    user_id      bigint       NOT NULL,
    process_key  varchar(64)  NOT NULL,
    process_name varchar(128),
    CONSTRAINT pk_sw_bpm_process_favorite PRIMARY KEY (id),
    CONSTRAINT uk_sw_bpm_process_favorite UNIQUE (tenant_id, user_id, process_key)
);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_process_favorite_user
    ON sw_bpm_process_favorite (tenant_id, user_id, update_time DESC);

-- ======== V100（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V100: V012-BUG-010 —— 流程主题生成规则与实例主题
--
-- 1) sw_bpm_process_def.theme_rule：流程系统级设置的主题生成规则
--    （占位符 {TIMESTAMP}/{YYYYMMDD}/{YYYYMMDDHHMMSS}/{SEQ}，其余为字面量）。
-- 2) sw_bpm_instance.theme：发起时按规则生成的实例主题（待办/已办/详情展示）。
-- 3) sw_bpm_theme_seq：每流程自增序号计数器（{SEQ} 行锁自增，事务内一致）。
-- 幂等：ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS。
-- ===================================================================
ALTER TABLE sw_bpm_process_def ADD COLUMN IF NOT EXISTS theme_rule varchar(200);
ALTER TABLE sw_bpm_instance ADD COLUMN IF NOT EXISTS theme varchar(200);

CREATE TABLE IF NOT EXISTS sw_bpm_theme_seq (
    process_key varchar(64) NOT NULL,
    next_val    bigint      NOT NULL DEFAULT 1,
    CONSTRAINT pk_sw_bpm_theme_seq PRIMARY KEY (process_key)
);

-- ======== V101（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V012-BUG-010（bpm 隔离测试链）：主题规则/实例主题/自增计数表
ALTER TABLE sw_bpm_process_def ADD COLUMN IF NOT EXISTS theme_rule varchar(200);
ALTER TABLE sw_bpm_instance ADD COLUMN IF NOT EXISTS theme varchar(200);
CREATE TABLE IF NOT EXISTS sw_bpm_theme_seq (
    process_key varchar(64) NOT NULL,
    next_val    bigint      NOT NULL DEFAULT 1,
    CONSTRAINT pk_sw_bpm_theme_seq PRIMARY KEY (process_key)
);

-- ======== V102（来源: sw-bootstrap，副本 1 处已去重） ========
-- V012-BUG-009 修正：收藏表补 BaseEntity 审计列（create_by/update_by）
ALTER TABLE sw_bpm_process_favorite ADD COLUMN IF NOT EXISTS create_by bigint;
ALTER TABLE sw_bpm_process_favorite ADD COLUMN IF NOT EXISTS update_by bigint;

-- ======== V103（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V103: sso-admin-config —— 后台 SSO 配置管理菜单入口
--
-- 系统管理组下新增「SSO 配置管理」页面菜单（租户级列表/编辑/启停/凭据/检查/
-- 审计；权限点 system:sso:config:list 由服务端 @PreAuthorize 守卫，编辑/启停/
-- 凭据更新分别以 system:sso:config:edit|enable|secret 区分）。
-- 约束：幂等（NOT EXISTS 守卫）；不 seed sys_role_menu（超管可见全量菜单，
-- 租户角色经角色管理授权，与 V97 口径一致）。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 420, current_timestamp, current_timestamp, 0, 0, 1, 'SsoConfig', 'SSO 配置管理', false, 1, 'system/sso-config', 'system/views/SsoConfig', 'system:sso:config:list', 'Key', 65
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 420);

-- 按钮权限行（menu_type=2）：编辑/启停/凭据更新分别授权（凭据更新不隐式混入普通编辑权限）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 421, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigEdit', 'SSO 配置编辑', false, 2, '', '', 'system:sso:config:edit', '', 10
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 421);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 422, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigEnable', 'SSO 配置启停', false, 2, '', '', 'system:sso:config:enable', '', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 422);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 423, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigSecret', 'SSO 应用凭据更新', false, 2, '', '', 'system:sso:config:secret', '', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 423);

-- ======== V104（来源: sw-biz/sw-biz-system/sw-biz-system-biz，副本 1 处已去重） ========
-- ===================================================================
-- V104: sso-admin-config A4 —— 在途授权绑定配置指纹
--
-- sys_sso_auth_state 新增 config_digest：授权发起时刻的 Provider 配置
-- 指纹（SHA-256，材料=provider|appId|解密secret|企业标识|启停）。回调与
-- 未兑换票据兑换时与当前配置比对：appId、secret、身份模式（企业标识有
-- 无）、企业标识、启停任一变化 → 在途授权安全失败并可重新发起，不串用
-- 新旧配置（方向 §三）。
-- 约束：列可空，历史行 NULL 在回调侧一律 fail closed 拒绝；state 本身
-- 一次性/限时语义不变。
-- ===================================================================

alter table sys_sso_auth_state add column config_digest varchar(128);

