-- V0.2.0 baseline seed：0.1.7 期 versioned 迁移链（V0.1.7 基线种子 + V0.1.8 租户用户名唯一
-- + V0.1.9 租户管理员身份 + V0.1.10 租户状态审计）合并基线。
-- 由 0.2.0 发版收敛批次生成（product/0.2.0/receipts/planning-execution-prompt-unified-020-36.md）：
-- 内容为收敛前 versioned 迁移按版本序的逐字节拼接，仅增加段头注释；全新建库终态与原链等价
-- （等价性验证为归档原件拼接的 sha256 与新基线正文逐字节一致，见 db/upgrade/0.2.0/README.md）。
-- 收敛前原文件移入 db/upgrade/0.2.0/legacy-0.1.10/postgresql/（独立升级通道，不进 Flyway 主扫描位置），
-- 亦可从历史提交恢复；存量库升级衔接见 db/upgrade/0.2.0/README.md。


-- ======== 来源: V0.1.7__baseline_seed.sql ========
-- V0.1.7 baseline seed：0.1.6 期 versioned 迁移链（V0.1.6 基线种子 + V0.1.7 菜单/应用范围增量）合并基线。
-- 由 0.1.7 发版收敛批次生成（product/0.1.7-bugfix/ready/direction-release-handoff-017.md）：内容为收敛前
-- versioned 迁移按版本序的逐字节拼接，仅增加段头注释；全新建库终态与原链等价（等价性验证见 0.1.7 发布回执）。
-- 收敛前原文件移入 db/upgrade/0.1.7/legacy-0.1.6/postgresql/（独立升级通道，不进 Flyway 主扫描位置），
-- 亦可从历史提交恢复；存量库升级衔接见 db/upgrade/0.1.7/README.md。


-- ======== 来源: V0.1.6__baseline_seed.sql ========
-- V0.1.6 baseline seed：0.1.5 期 versioned 迁移链（V0.1.5 基线种子 + V20261015190000 应用中心增量）合并基线。
-- 由 0.1.6 发版收敛批次生成（product/0.1.6-release/ready/direction-release-016.md）：内容为收敛前
-- versioned 迁移按版本序的逐字节拼接，仅增加段头注释；全新建库终态与原链等价（等价性验证见 0.1.6 发布回执 release-016-01.md）。
-- 收敛前原文件移入 db/upgrade/0.1.6/legacy-0.1.5/postgresql/（独立升级通道，不进 Flyway 主扫描位置），
-- 亦可从历史提交恢复；存量库升级衔接见 db/upgrade/0.1.6/README.md。

-- ======== 来源: V0.1.5__baseline_seed.sql ========
-- V0.1.5 baseline seed：0.1.3—0.1.5 期 versioned 迁移链（V0.1.0—V0.1.10，共 11 个版本）合并基线。
-- 由 0.1.5 发版收敛批次生成（ready/development-declaration.md §2）：内容为原迁移链按版本序的逐字节拼接，
-- 仅增加段头注释；全新建库终态与原链等价（等价性验证见 0.1.5 发布回执 release-015-01.md）。
-- 原链文件移入 db/upgrade/0.1.5/legacy-0.1.x/postgresql/（独立升级通道，不进 Flyway 主扫描位置）。

-- ======== 来源: V0.1.0__baseline_seed.sql ========
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
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    name            VARCHAR2(100 CHAR)    not null,
    code            VARCHAR2(50 CHAR)     not null,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    description     CLOB,
    contact_name    VARCHAR2(50 CHAR),
    contact_phone   VARCHAR2(20 CHAR),
    contact_email   VARCHAR2(100 CHAR),
    expire_time     timestamp,
    domain_name     VARCHAR2(100 CHAR)
);
create unique index uk_sys_tenant_code on sys_tenant (code);

-- -------------------- 部门 --------------------
create table sys_dept (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    parent_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    name            VARCHAR2(100 CHAR)    not null,
    code            VARCHAR2(50 CHAR),
    sort            NUMBER(10)         DEFAULT 0 NOT NULL,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    description     CLOB,
    leader_id       NUMBER(19)
);
create index idx_sys_dept_parent_id on sys_dept (parent_id);

-- -------------------- 岗位 --------------------
create table sys_post (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    code            VARCHAR2(50 CHAR)     not null,
    name            VARCHAR2(100 CHAR)    not null,
    sort            NUMBER(10)         DEFAULT 0 NOT NULL,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    description     CLOB
);

-- -------------------- 用户 --------------------
create table sys_user (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    username        VARCHAR2(50 CHAR)     not null,
    password        VARCHAR2(200 CHAR)    not null,
    real_name       VARCHAR2(50 CHAR),
    email           VARCHAR2(100 CHAR),
    phone           VARCHAR2(20 CHAR),
    avatar          VARCHAR2(200 CHAR),
    sex             NUMBER(5)        DEFAULT 0 NOT NULL,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    dept_id         NUMBER(19),
    last_login_time timestamp,
    last_login_ip   VARCHAR2(50 CHAR),
    remark          CLOB,
    is_admin        NUMBER(5)        DEFAULT 0 NOT NULL
);
create unique index uk_sys_user_username on sys_user (username);
create index idx_sys_user_dept_id on sys_user (dept_id);

-- -------------------- 角色 --------------------
create table sys_role (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    name            VARCHAR2(100 CHAR)    not null,
    code            VARCHAR2(50 CHAR)     not null,
    sort            NUMBER(10)         DEFAULT 0 NOT NULL,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    data_scope      NUMBER(5)        DEFAULT 0 NOT NULL,
    description     CLOB,
    is_builtin      NUMBER(5)        DEFAULT 0 NOT NULL
);
create unique index uk_sys_role_code on sys_role (code);

-- -------------------- 菜单 --------------------
create table sys_menu (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    parent_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    name            VARCHAR2(100 CHAR)    not null,
    menu_type       NUMBER(5)        DEFAULT 0 NOT NULL,
    path            VARCHAR2(200 CHAR),
    component       VARCHAR2(200 CHAR),
    permission      VARCHAR2(100 CHAR),
    icon            VARCHAR2(100 CHAR),
    sort            NUMBER(10)         DEFAULT 0 NOT NULL,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    visible         NUMBER(5)        DEFAULT 1 NOT NULL,
    keep_alive      NUMBER(5)        DEFAULT 0 NOT NULL,
    description     CLOB
);
create index idx_sys_menu_parent_id on sys_menu (parent_id);

-- -------------------- 用户角色关联 --------------------
create table sys_user_role (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    user_id         NUMBER(19)          not null,
    role_id         NUMBER(19)          not null
);
create unique index uk_sys_user_role on sys_user_role (user_id, role_id);

-- -------------------- 角色菜单关联 --------------------
create table sys_role_menu (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    role_id         NUMBER(19)          not null,
    menu_id         NUMBER(19)          not null
);
create unique index uk_sys_role_menu on sys_role_menu (role_id, menu_id);

-- -------------------- 字典类型 --------------------
create table sys_dict_type (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    name            VARCHAR2(100 CHAR)    not null,
    code            VARCHAR2(50 CHAR)     not null,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    description     CLOB
);
create unique index uk_sys_dict_type_code on sys_dict_type (code);

-- -------------------- 字典数据 --------------------
create table sys_dict_data (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    dict_code       VARCHAR2(50 CHAR)     not null,
    label           VARCHAR2(100 CHAR)    not null,
    dict_value      VARCHAR2(100 CHAR)    not null,
    sort            NUMBER(10)         DEFAULT 0 NOT NULL,
    status          NUMBER(5)        DEFAULT 0 NOT NULL,
    is_default      NUMBER(5)        DEFAULT 0 NOT NULL,
    css_class       VARCHAR2(50 CHAR),
    list_class      VARCHAR2(50 CHAR),
    description     CLOB
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
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    name            VARCHAR2(100 CHAR)    not null,
    type            VARCHAR2(50 CHAR)     not null,
    jdbc_url        VARCHAR2(500 CHAR)    not null,
    driver_class    VARCHAR2(200 CHAR)    not null,
    username        VARCHAR2(100 CHAR)    not null,
    password_cipher CLOB            not null,
    read_only       NUMBER(5)        DEFAULT 1 NOT NULL,
    enabled         NUMBER(5)        DEFAULT 1 NOT NULL
);
create unique index uk_sw_bpm_ext_ds_name on sw_bpm_ext_datasource (name);
comment on table sw_bpm_ext_datasource is '外部数据源连接信息';
comment on column sw_bpm_ext_datasource.password_cipher is 'AES-256-GCM 加密密码密文';
comment on column sw_bpm_ext_datasource.read_only is '是否只读：1=是，0=否（建议配合只读账号）';
comment on column sw_bpm_ext_datasource.enabled is '是否启用：1=启用，0=禁用';

-- -------------------- SQL 执行审计日志 --------------------
create table sw_bpm_ext_sql_execution_audit (
    id                  NUMBER(19)          not null primary key,
    create_time         timestamp       DEFAULT current_timestamp NOT NULL,
    create_by           NUMBER(19),
    update_time         timestamp       DEFAULT current_timestamp NOT NULL,
    update_by           NUMBER(19),
    deleted             NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id           NUMBER(19)          DEFAULT 0 NOT NULL,
    version             NUMBER(19)          DEFAULT 0 NOT NULL,
    datasource_id       NUMBER(19)          not null,
    datasource_name     VARCHAR2(100 CHAR)    not null,
    sql_text            CLOB            not null,
    row_count           NUMBER(10),
    execution_time_ms   NUMBER(19),
    success             NUMBER(5)        DEFAULT 0 NOT NULL,
    error_message       CLOB,
    operator_id         NUMBER(19)          not null,
    operator_name       VARCHAR2(50 CHAR)     not null
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
--   · sys_role → is_builtin→built_in(NUMBER(1)), description→remark, 增加唯一(tenant_id,code)
--   · sys_user_role → 唯一约束改为 (tenant_id, user_id, role_id)
--   · sys_role_menu → 唯一约束改为 (tenant_id, role_id, menu_id)
--   · 超管角色 code 对齐 superadmin，status 对齐 CommonStatusEnum.ENABLE(=1)
-- ===================================================================

-- ==================== 1. sys_menu ====================
-- 新增 title 列，从 name 填充
ALTER TABLE sys_menu add title VARCHAR2(64 CHAR);
UPDATE sys_menu SET title = name;
ALTER TABLE sys_menu modify (title not null);


-- 新增 hidden 列（替换 visible 语义：visible=1 → hidden= 0）
ALTER TABLE sys_menu add hidden NUMBER(1) DEFAULT 0 NOT NULL;
UPDATE sys_menu SET hidden = CASE WHEN visible = 0 THEN 1 ELSE 0 END;


-- 移除旧列（tenant_id → 全局表不再需要；visible/keep_alive/status/description → 新规范中移除）
ALTER TABLE sys_menu DROP COLUMN tenant_id;
ALTER TABLE sys_menu DROP COLUMN visible;
ALTER TABLE sys_menu DROP COLUMN keep_alive;
ALTER TABLE sys_menu DROP COLUMN status;
ALTER TABLE sys_menu DROP COLUMN description;

-- 调整列类型与 M-Seam 规范一致（扩宽始终安全，不会丢数据）
alter table sys_menu modify (name VARCHAR2(64 CHAR));
alter table sys_menu modify (path VARCHAR2(128 CHAR));
alter table sys_menu modify (component VARCHAR2(255 CHAR));
alter table sys_menu modify (permission VARCHAR2(128 CHAR));
alter table sys_menu modify (icon VARCHAR2(64 CHAR));

-- ==================== 2. sys_role ====================
-- is_builtin → built_in（类型改为 NUMBER(1)）
ALTER TABLE sys_role add built_in NUMBER(1) DEFAULT 0 NOT NULL;
UPDATE sys_role SET built_in = CASE WHEN is_builtin = 1 THEN 1 ELSE 0 END;
ALTER TABLE sys_role DROP COLUMN is_builtin;

-- description → remark
ALTER TABLE sys_role add remark VARCHAR2(255 CHAR);
UPDATE sys_role SET remark = description;
ALTER TABLE sys_role DROP COLUMN description;

-- 调整列类型
alter table sys_role modify (name VARCHAR2(64 CHAR));
alter table sys_role modify (code VARCHAR2(64 CHAR));

-- data_scope 改为可空、无默认值（S7 预留，本环不生效）
alter table sys_role modify (data_scope null);


-- 唯一索引：从 (code) 改为 (tenant_id, code)
drop index uk_sys_role_code;
CREATE UNIQUE INDEX uk_sys_role_tenant_code ON sys_role (tenant_id, code);

-- ==================== 3. sys_user_role ====================
-- 唯一索引：从 (user_id, role_id) 改为 (tenant_id, user_id, role_id)
drop index uk_sys_user_role;
CREATE UNIQUE INDEX uk_sys_user_role_tenant ON sys_user_role (tenant_id, user_id, role_id);

-- ==================== 4. sys_role_menu ====================
-- 唯一索引：从 (role_id, menu_id) 改为 (tenant_id, role_id, menu_id)
drop index uk_sys_role_menu;
CREATE UNIQUE INDEX uk_sys_role_menu_tenant ON sys_role_menu (tenant_id, role_id, menu_id);

-- ==================== 5. 种子数据对齐 ====================
-- 超管角色：code 从 admin → superadmin，status 对齐 CommonStatusEnum.ENABLE(=1)，标记内置
UPDATE sys_role SET code = 'superadmin', data_scope = NULL, status = 1 WHERE id = 1;
-- 确保超管角色标记内置（V4 插入时 is_builtin=1，V5 迁移后 built_in 为 1，此句为幂等保障）
UPDATE sys_role SET built_in = 1 WHERE id = 1;

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
--   · hidden 列类型为 NUMBER(1)，使用 1/0 字面量
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
    hidden = 0
WHERE id = 1;

-- ==================== 4. 插入新菜单 ====================
-- 低代码目录
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (2, current_timestamp, current_timestamp, 0, 0, 0, 'Lowcode', '低代码', 0, 0, 'lowcode', NULL, NULL, 'Grid', 20);

-- 低代码 → 低代码概览
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (3, current_timestamp, current_timestamp, 0, 0, 2, 'LowcodeHome', '低代码概览', 0, 1, 'overview', 'lowcode/views/LowcodeHome', 'lowcode:view', 'Document', 10);

-- 低代码 → 表单设计
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (4, current_timestamp, current_timestamp, 0, 0, 2, 'LowcodeForm', '表单设计', 0, 1, 'form', 'lowcode/views/LowcodeForm', 'lowcode:form:design', 'EditPen', 20);

-- 流程引擎
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (5, current_timestamp, current_timestamp, 0, 0, 0, 'Workflow', '流程引擎', 0, 1, 'workflow', 'workflow/views/WorkflowHome', 'workflow:view', 'Share', 30);

-- 通知
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (6, current_timestamp, current_timestamp, 0, 0, 0, 'Notify', '通知', 0, 1, 'notify', 'notify/views/NotifyHome', 'notify:view', 'Bell', 40);

-- 智能体
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (7, current_timestamp, current_timestamp, 0, 0, 0, 'Agent', '智能体', 0, 1, 'agent', 'agent/views/AgentHome', 'agent:view', 'MagicStick', 50);

-- 物联网
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (8, current_timestamp, current_timestamp, 0, 0, 0, 'Iot', '物联网', 0, 1, 'iot', 'iot/views/IotHome', 'iot:view', 'Cpu', 60);

-- 开放接口
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (9, current_timestamp, current_timestamp, 0, 0, 0, 'Openapi', '开放接口', 0, 1, 'openapi', 'openapi/views/OpenapiHome', 'openapi:view', 'Connection', 70);

-- ======== V7（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: Form 模块固定元数据表 (PostgreSQL)
-- ===================================================================
-- 注意：动态宽表（sw_form_{nanoId} / sw_form_table_{nanoId}）不在此处，
-- 由 DynamicTableManager 按 §6.2 例外管理。
-- ===================================================================

-- ==================== 1. 表单定义主表 ====================
CREATE TABLE sw_form_def (
    id                   VARCHAR2(36 CHAR)  PRIMARY KEY,
    form_key             VARCHAR2(100 CHAR) NOT NULL,
    name                 VARCHAR2(200 CHAR) NOT NULL,
    logical_table_name   VARCHAR2(100 CHAR),
    status               VARCHAR2(20 CHAR)  DEFAULT 'DRAFT' NOT NULL,
    physical_table_name  VARCHAR2(100 CHAR),
    form_version         NUMBER(10)          DEFAULT 1 NOT NULL,
    description          VARCHAR2(500 CHAR),
    sub_table_mapping    CLOB,
    tenant_id            NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted              NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by            NUMBER(19),
    update_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by            NUMBER(19),
    version              NUMBER(19)       DEFAULT 0 NOT NULL
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
    id          VARCHAR2(36 CHAR)  PRIMARY KEY,
    form_id     VARCHAR2(36 CHAR)  NOT NULL,
    definition  CLOB        NOT NULL,
    tenant_id   NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted     NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by   NUMBER(19),
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by   NUMBER(19),
    version     NUMBER(19)       DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_config               IS '表单配置/样式表';
COMMENT ON COLUMN sw_form_config.id             IS 'UUID 主键';
COMMENT ON COLUMN sw_form_config.form_id        IS '关联 sw_form_def.id';
COMMENT ON COLUMN sw_form_config.definition     IS '表单样式/控件/布局 schema (CLOB)';

-- ==================== 3. 表单快照表 ====================
CREATE TABLE sw_form_snapshot (
    id           VARCHAR2(36 CHAR)  PRIMARY KEY,
    form_id      VARCHAR2(36 CHAR)  NOT NULL,
    form_version NUMBER(10)          NOT NULL,
    definition   CLOB        NOT NULL,
    tenant_id    NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted      NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by    NUMBER(19),
    update_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by    NUMBER(19),
    version      NUMBER(19)       DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_snapshot               IS '表单版本快照表';
COMMENT ON COLUMN sw_form_snapshot.id             IS 'UUID 主键';
COMMENT ON COLUMN sw_form_snapshot.form_id        IS '关联 sw_form_def.id';
COMMENT ON COLUMN sw_form_snapshot.form_version   IS '快照版本号（与 sw_form_def.form_version 对齐）';
COMMENT ON COLUMN sw_form_snapshot.definition     IS '该版本的完整 definition CLOB 快照';

-- ==================== 4. 表单提交溯源表 ====================
CREATE TABLE sw_form_trace (
    id                 VARCHAR2(36 CHAR)  PRIMARY KEY,
    form_id            VARCHAR2(36 CHAR)  NOT NULL,
    record_id          VARCHAR2(36 CHAR)  NOT NULL,
    submit_user_id     NUMBER(19)       NOT NULL,
    submit_ip          VARCHAR2(200 CHAR),
    submit_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    device_fingerprint VARCHAR2(200 CHAR),
    user_agent         VARCHAR2(500 CHAR),
    tenant_id          NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted            NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by          NUMBER(19),
    update_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by          NUMBER(19),
    version            NUMBER(19)       DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_trace                   IS '表单提交溯源表';
COMMENT ON COLUMN sw_form_trace.id                 IS 'UUID 主键';
COMMENT ON COLUMN sw_form_trace.form_id            IS '关联 sw_form_def.id';
COMMENT ON COLUMN sw_form_trace.record_id          IS '动态宽表记录 UUID';
COMMENT ON COLUMN sw_form_trace.submit_user_id     IS '提交人（NUMBER(19)，指向 sys_user）';
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
--   PK           = NUMBER(19)（ASSIGN_ID 雪花算法）
--   无真实 DB 外键（应用层处理关系）
--   全归 Flyway 管理（非动态宽表）
-- ===================================================================

-- ==================== 1. 表单↔流程绑定表 ====================
-- 将表单（by form_key）绑定到一条 BPMN 流程定义（by process_def_key）。
-- 一个表单只能有一条启用绑定（active= 1），停用后可用新绑定替换。
create table sw_bpm_form_binding (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    form_key          VARCHAR2(200 CHAR)    not null,
    process_def_key   VARCHAR2(200 CHAR)    not null,
    active            NUMBER(1)         DEFAULT 1 NOT NULL
);

comment on table  sw_bpm_form_binding                is '表单↔流程绑定表';
comment on column sw_bpm_form_binding.form_key        is '表单业务标识（对应 FormSubmittedEvent.formKey）';
comment on column sw_bpm_form_binding.process_def_key is 'BPMN 流程定义 key（Flowable 部署用）';
comment on column sw_bpm_form_binding.active          is '是否启用：true=启用（唯一，同 form_key+tenant 仅一条）, false=停用';

-- 唯一索引：同租户下同表单只能有一条启用绑定
create unique index uk_sw_bpm_binding_active on sw_bpm_form_binding (
    case when active = 1 then tenant_id end,
    case when active = 1 then form_key end);

-- ==================== 2. 流程实例记录表 ====================
-- 记录我方发起的每个 Flowable 流程实例，供"我发起的"/监控查询。
-- 与 Flowable ACT_HI_PROCINST 保持 process_instance_id 映射。
create table sw_bpm_instance (
    id                   NUMBER(19)          not null primary key,
    create_time          timestamp       DEFAULT current_timestamp NOT NULL,
    create_by            NUMBER(19),
    update_time          timestamp       DEFAULT current_timestamp NOT NULL,
    update_by            NUMBER(19),
    deleted              NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id            NUMBER(19)          DEFAULT 0 NOT NULL,
    version              NUMBER(19)          DEFAULT 0 NOT NULL,
    process_instance_id  VARCHAR2(64 CHAR)     not null,
    process_def_key      VARCHAR2(200 CHAR)    not null,
    business_key         VARCHAR2(36 CHAR)     not null,
    form_key             VARCHAR2(200 CHAR)    not null,
    initiator_id         NUMBER(19)          not null,
    status               VARCHAR2(20 CHAR)     DEFAULT 'RUNNING' NOT NULL
);

comment on table  sw_bpm_instance                       is '流程实例记录表';
comment on column sw_bpm_instance.process_instance_id    is 'Flowable 流程实例 ID（对应 ACT_HI_PROCINST.ID_）';
comment on column sw_bpm_instance.process_def_key        is 'BPMN 流程定义 key';
comment on column sw_bpm_instance.business_key           is '业务键（= 表单动态宽表 recordId，VARCHAR2(4000 CHAR) 36）';
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
--   PK           = NUMBER(19)（ASSIGN_ID 雪花算法）
--   无真实 DB 外键（应用层处理关系）
--   全归 Flyway 管理（非动态宽表）
-- ===================================================================

create table sw_notify_message (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    recipient_id      NUMBER(19)          not null,
    title             VARCHAR2(200 CHAR)    not null,
    content           CLOB            not null,
    biz_type          VARCHAR2(30 CHAR)     not null,
    biz_id            VARCHAR2(64 CHAR),
    is_read           NUMBER(1)         DEFAULT 0 NOT NULL
);

comment on table  sw_notify_message               is '站内信通知表';
comment on column sw_notify_message.recipient_id  is '接收人用户 ID（指向 sys_user.id）';
comment on column sw_notify_message.title         is '通知标题';
comment on column sw_notify_message.content       is '通知内容';
comment on column sw_notify_message.biz_type      is '业务类型（NotifyBizType 枚举 name：WF_TODO / WF_APPROVED）';
comment on column sw_notify_message.biz_id        is '业务 ID（Flowable taskId / piId 等，VARCHAR2(4000 CHAR)）';
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
VALUES (10, current_timestamp, current_timestamp, 0, 0, 1, 'DictManage', '字典管理', 0, 1, 'dict', 'system/views/DictTypeList', 'system:dict:view', 'Collection', 10);

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
ALTER TABLE sw_form_config add table_name   VARCHAR2(200 CHAR);
ALTER TABLE sw_form_config add parent_table VARCHAR2(200 CHAR);

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
-- 背景：所有主库业务表已有 deleted 列（NUMBER(5) DEFAULT 0）， NOT NULL
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
--   第 7 项 sw_form_def 的唯一约束由 V7 inline UNIQUE（form_key VARCHAR2(100 CHAR)
--   NOT NULL UNIQUE）创建，PG 中该约束背书隐式索引 sw_form_def_form_key_key，
--   直接 DROP INDEX 触发 2BP01（cannot drop index ... because constraint ...
--   requires it），必须先 ALTER TABLE ... DROP CONSTRAINT 释放；
--   H2 允许直接 DROP INDEX，故 H2 侧 V13 不采用此写法。
--
-- 无需改动的唯一索引：
--   · sw_bpm_form_binding.uk_sw_bpm_binding_active
--     — 已有 WHERE active= 1，不屏蔽软删重建
--
-- ❌ 不碰动态宽表（sw_form_{nanoId} / sw_form_table_{nanoId}）
-- ❌ 不碰 ACT_*（Flowable 自管）
-- ❌ 不碰扩展库表
-- ===================================================================

-- ==================== 1. sys_tenant ====================
drop index uk_sys_tenant_code;
CREATE UNIQUE INDEX uk_sys_tenant_code ON sys_tenant (code, deleted);

-- ==================== 2. sys_user ====================
drop index uk_sys_user_username;
CREATE UNIQUE INDEX uk_sys_user_username ON sys_user (username, deleted);

-- ==================== 3. sys_role ====================
drop index uk_sys_role_tenant_code;
CREATE UNIQUE INDEX uk_sys_role_tenant_code ON sys_role (tenant_id, code, deleted);

-- ==================== 4. sys_user_role ====================
drop index uk_sys_user_role_tenant;
CREATE UNIQUE INDEX uk_sys_user_role_tenant ON sys_user_role (tenant_id, user_id, role_id, deleted);

-- ==================== 5. sys_role_menu ====================
drop index uk_sys_role_menu_tenant;
CREATE UNIQUE INDEX uk_sys_role_menu_tenant ON sys_role_menu (tenant_id, role_id, menu_id, deleted);

-- ==================== 6. sys_dict_type ====================
drop index uk_sys_dict_type_code;
CREATE UNIQUE INDEX uk_sys_dict_type_code ON sys_dict_type (code, deleted);

-- ==================== 7. sw_form_def (form_key) ====================
-- V7 inline UNIQUE 在 PG 中创建的是约束背书的隐式索引 sw_form_def_form_key_key，
-- 直接 DROP INDEX 会触发 2BP01（cannot drop index ... because constraint ...
-- requires it），必须先 DROP CONSTRAINT 释放该隐式索引；
-- H2 允许直接 DROP INDEX，故 H2 侧 V13 保持 DROP INDEX 不变。
CREATE UNIQUE INDEX uk_sw_form_def_form_key ON sw_form_def (form_key, deleted);

-- ==================== 8. sw_form_config (table_name) ====================
-- V12 用 ALTER TABLE ADD CONSTRAINT 创建的唯一约束
alter table sw_form_config drop constraint uk_sw_form_cfg_tname;
CREATE UNIQUE INDEX uk_sw_form_cfg_tname ON sw_form_config (table_name, deleted);

-- ==================== 9. sw_bpm_ext_datasource ====================
drop index uk_sw_bpm_ext_ds_name;
CREATE UNIQUE INDEX uk_sw_bpm_ext_ds_name ON sw_bpm_ext_datasource (name, deleted);

-- ==================== 校验说明 ====================
-- 全部索引保持原名（uk_xxx），仅改定义（复合列加上 deleted）。
-- 版本 V13 = 当前最高 V12 + 1。
-- 排除项理由：
--   ✅ sw_bpm_form_binding.uk_sw_bpm_binding_active — 已有 WHERE active= 1，不冲突
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
--   PK           = NUMBER(19)（ASSIGN_ID 雪花算法）
--   graph_json   = clob（H2）/ text（PG），存储 ProcessGraph CLOB
--   status       = DRAFT | PUBLISHED；本刀恒 DRAFT
-- ===================================================================

create table sw_bpm_process_def (
    id                   NUMBER(19)          not null primary key,
    create_time          timestamp       DEFAULT current_timestamp NOT NULL,
    create_by            NUMBER(19),
    update_time          timestamp       DEFAULT current_timestamp NOT NULL,
    update_by            NUMBER(19),
    deleted              NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id            NUMBER(19)          DEFAULT 0 NOT NULL,
    version              NUMBER(19)          DEFAULT 0 NOT NULL,
    process_key          VARCHAR2(200 CHAR)    not null,
    name                 VARCHAR2(200 CHAR)    not null,
    form_key             VARCHAR2(200 CHAR)    not null,
    def_version          NUMBER(10)             DEFAULT 1 NOT NULL,
    status               VARCHAR2(20 CHAR)     DEFAULT 'DRAFT' NOT NULL,
    deployment_id        VARCHAR2(64 CHAR),
    process_definition_id VARCHAR2(64 CHAR),
    graph_json           CLOB
);

comment on table  sw_bpm_process_def                 is '流程定义表（图模型存储）';
comment on column sw_bpm_process_def.process_key      is '流程业务 key（发布后冻结）';
comment on column sw_bpm_process_def.name             is '流程名称';
comment on column sw_bpm_process_def.form_key         is '绑定表单 formKey';
comment on column sw_bpm_process_def.def_version      is '定义版本号（默认 1）';
comment on column sw_bpm_process_def.status           is '状态：DRAFT（草稿）/ PUBLISHED（已发布）';
comment on column sw_bpm_process_def.deployment_id    is 'Flowable 部署 ID（cut B 回填）';
comment on column sw_bpm_process_def.process_definition_id is 'Flowable 流程定义 ID（cut B 回填）';
comment on column sw_bpm_process_def.graph_json       is '图 CLOB 文档（ProcessGraph 序列化）';

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
VALUES (11, current_timestamp, current_timestamp, 0, 0, 1, 'User', '用户管理', 0, 1, 'user', 'system/views/UserList', 'system:user:list', 'User', 10);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (12, current_timestamp, current_timestamp, 0, 0, 1, 'Role', '角色管理', 0, 1, 'role', 'system/views/RoleList', 'system:role:list', 'Avatar', 20);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (13, current_timestamp, current_timestamp, 0, 0, 1, 'Dept', '部门管理', 0, 1, 'dept', 'system/views/DeptList', 'system:dept:list', 'Collection', 30);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (14, current_timestamp, current_timestamp, 0, 0, 1, 'Post', '岗位管理', 0, 1, 'post', 'system/views/PostList', 'system:post:list', 'Tickets', 40);

-- ======== V16（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V16: 初始化文件存储记录表 (PostgreSQL)
-- ===================================================================
CREATE TABLE sw_storage_file (
    id              NUMBER(19)      NOT NULL,
    original_name   VARCHAR2(512 CHAR) NOT NULL,
    storage_key     VARCHAR2(512 CHAR) NOT NULL,
    storage_name    VARCHAR2(512 CHAR) NOT NULL,
    file_size       NUMBER(19)      DEFAULT 0 NOT NULL,
    content_type    VARCHAR2(255 CHAR) DEFAULT NULL,
    file_ext        VARCHAR2(32 CHAR)  DEFAULT NULL,
    provider_type   VARCHAR2(32 CHAR)  DEFAULT 'local' NOT NULL,
    bucket_name     VARCHAR2(255 CHAR) DEFAULT NULL,
    storage_url     CLOB DEFAULT NULL,
    create_time     TIMESTAMP    DEFAULT NULL,
    create_by       NUMBER(19)       DEFAULT NULL,
    update_time     TIMESTAMP    DEFAULT NULL,
    update_by       NUMBER(19)       DEFAULT NULL,
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    version         NUMBER(19)       DEFAULT NULL,
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
    id              NUMBER(19)      NOT NULL,
    job_name        VARCHAR2(128 CHAR) NOT NULL,
    job_group       VARCHAR2(128 CHAR) DEFAULT 'DEFAULT' NOT NULL,
    job_type        VARCHAR2(16 CHAR)  DEFAULT 'BEAN' NOT NULL,
    cron_expression VARCHAR2(128 CHAR) NOT NULL,
    status          VARCHAR2(16 CHAR)  DEFAULT 'NORMAL' NOT NULL,
    concurrent      NUMBER(5)     DEFAULT 0 NOT NULL,
    misfire_policy  NUMBER(5)     DEFAULT 0 NOT NULL,
    description     VARCHAR2(512 CHAR) DEFAULT NULL,
    bean_name       VARCHAR2(256 CHAR) DEFAULT NULL,
    bean_params     CLOB         DEFAULT NULL,
    flow_def_key    VARCHAR2(128 CHAR) DEFAULT NULL,
    form_data       CLOB         DEFAULT NULL,
    last_fire_time  TIMESTAMP    DEFAULT NULL,
    next_fire_time  TIMESTAMP    DEFAULT NULL,
    create_time     TIMESTAMP    DEFAULT NULL,
    create_by       NUMBER(19)       DEFAULT NULL,
    update_time     TIMESTAMP    DEFAULT NULL,
    update_by       NUMBER(19)       DEFAULT NULL,
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    version         NUMBER(19)       DEFAULT NULL,
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
COMMENT ON COLUMN sw_job_info.bean_params   IS 'Bean 方法参数（CLOB）';
COMMENT ON COLUMN sw_job_info.flow_def_key  IS '流程定义 Key（FLOW 类型必填）';
COMMENT ON COLUMN sw_job_info.form_data     IS '表单数据（CLOB）';
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
    id              NUMBER(19)      NOT NULL,
    job_id          NUMBER(19)      NOT NULL,
    job_name        VARCHAR2(128 CHAR) NOT NULL,
    job_group       VARCHAR2(128 CHAR) DEFAULT 'DEFAULT' NOT NULL,
    trigger_type    VARCHAR2(16 CHAR)  DEFAULT 'AUTO' NOT NULL,
    job_params      CLOB         DEFAULT NULL,
    exec_status     VARCHAR2(16 CHAR)  DEFAULT 'RUNNING' NOT NULL,
    start_time      TIMESTAMP    DEFAULT NULL,
    end_time        TIMESTAMP    DEFAULT NULL,
    duration        NUMBER(19)       DEFAULT NULL,
    result_msg      CLOB         DEFAULT NULL,
    exception_stack CLOB         DEFAULT NULL,
    create_time     TIMESTAMP    DEFAULT NULL,
    create_by       NUMBER(19)       DEFAULT NULL,
    update_time     TIMESTAMP    DEFAULT NULL,
    update_by       NUMBER(19)       DEFAULT NULL,
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    version         NUMBER(19)       DEFAULT NULL,
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
    id          NUMBER(19)       NOT NULL,
    user_id     NUMBER(19)       NOT NULL,
    token_hash  VARCHAR2(128 CHAR) NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    revoked     NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time TIMESTAMP    DEFAULT NULL,
    create_by   NUMBER(19)       DEFAULT NULL,
    update_time TIMESTAMP    DEFAULT NULL,
    update_by   NUMBER(19)       DEFAULT NULL,
    deleted     NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id   NUMBER(19)       DEFAULT 0 NOT NULL,
    version     NUMBER(19)       DEFAULT NULL,
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
    id              NUMBER(19) NOT NULL PRIMARY KEY,
    name            VARCHAR2(100 CHAR) NOT NULL,
    protocol_type   VARCHAR2(32 CHAR) NOT NULL,
    base_url        VARCHAR2(500 CHAR) NOT NULL,
    model_name      VARCHAR2(100 CHAR) NOT NULL,
    api_key_cipher  CLOB,
    temperature     NUMBER(4,2),
    max_tokens      NUMBER(10),
    top_p           NUMBER(4,2),
    timeout_seconds NUMBER(10) DEFAULT 30 NOT NULL,
    retry_count     NUMBER(10) DEFAULT 0 NOT NULL,
    enabled         NUMBER(5) DEFAULT 1 NOT NULL,
    remark          VARCHAR2(500 CHAR),
    create_time     TIMESTAMP,
    create_by       VARCHAR2(64 CHAR),
    update_time     TIMESTAMP,
    update_by       VARCHAR2(64 CHAR),
    deleted         NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19) DEFAULT 0 NOT NULL,
    version         NUMBER(19) DEFAULT 0 NOT NULL
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
-- input_schema（CLOB Schema 字符串，可能较长）：PG 用 TEXT（参照 V19 api_key_cipher 惯例）
-- ===================================================================
CREATE TABLE sw_agent_tool_internal (
    id              NUMBER(19) NOT NULL PRIMARY KEY,
    name            VARCHAR2(100 CHAR) NOT NULL,
    description     VARCHAR2(500 CHAR) NOT NULL,
    input_schema    CLOB,
    bean_name       VARCHAR2(100 CHAR) NOT NULL,
    method_name     VARCHAR2(100 CHAR) NOT NULL,
    enabled         NUMBER(5) DEFAULT 1 NOT NULL,
    remark          VARCHAR2(500 CHAR),
    create_time     TIMESTAMP,
    create_by       VARCHAR2(64 CHAR),
    update_time     TIMESTAMP,
    update_by       VARCHAR2(64 CHAR),
    deleted         NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19) DEFAULT 0 NOT NULL,
    version         NUMBER(19) DEFAULT 0 NOT NULL
);

CREATE TABLE sw_agent_tool_external (
    id              NUMBER(19) NOT NULL PRIMARY KEY,
    name            VARCHAR2(100 CHAR) NOT NULL,
    description     VARCHAR2(500 CHAR) NOT NULL,
    input_schema    CLOB,
    url             VARCHAR2(500 CHAR) NOT NULL,
    http_method     VARCHAR2(10 CHAR) DEFAULT 'POST' NOT NULL,
    timeout_seconds NUMBER(10) DEFAULT 30 NOT NULL,
    enabled         NUMBER(5) DEFAULT 1 NOT NULL,
    remark          VARCHAR2(500 CHAR),
    create_time     TIMESTAMP,
    create_by       VARCHAR2(64 CHAR),
    update_time     TIMESTAMP,
    update_by       VARCHAR2(64 CHAR),
    deleted         NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19) DEFAULT 0 NOT NULL,
    version         NUMBER(19) DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_agent_tool_internal_tenant_deleted ON sw_agent_tool_internal (tenant_id, deleted);
CREATE INDEX idx_sw_agent_tool_external_tenant_deleted ON sw_agent_tool_external (tenant_id, deleted);

COMMENT ON TABLE sw_agent_tool_internal IS 'M07 工具沙箱-内部工具白名单（bean 反射调用）';
COMMENT ON COLUMN sw_agent_tool_internal.input_schema IS 'CLOB Schema 字符串，描述入参结构';
COMMENT ON COLUMN sw_agent_tool_internal.bean_name IS 'Spring bean 名称（白名单值，安全边界）';
COMMENT ON COLUMN sw_agent_tool_internal.method_name IS '方法名（白名单值，约定签名 String→String）';
COMMENT ON TABLE sw_agent_tool_external IS 'M07 工具沙箱-外部 HTTP 工具白名单';
COMMENT ON COLUMN sw_agent_tool_external.input_schema IS 'CLOB Schema 字符串，描述入参结构';
COMMENT ON COLUMN sw_agent_tool_external.url IS '白名单 URL（完整 URL，含路径）';
COMMENT ON COLUMN sw_agent_tool_external.http_method IS 'HTTP 方法：GET/POST/PUT';
COMMENT ON COLUMN sw_agent_tool_external.timeout_seconds IS '请求超时（秒），默认 30';

-- ======== V21（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V21: 初始化 Agent 会话主表 (PostgreSQL)
-- M07 Step4 F04 对话交互：会话主表
-- PK = 雪花 ID（Java 层生成，MyBatis-Plus IdType.ASSIGN_ID）
-- status VARCHAR2(20 CHAR) 对齐 sw_bpm_instance 惯例（V8）
-- create_time 无 DEFAULT：由 Java 层（MetaObjectHandler）显式赋值（agent 模块 V19/V20 惯例）
-- ===================================================================
CREATE TABLE sw_agent_session (
    id                    NUMBER(19)      NOT NULL PRIMARY KEY,
    agent_model_config_id NUMBER(19)      NOT NULL,
    title                 VARCHAR2(500 CHAR),
    status                VARCHAR2(20 CHAR) DEFAULT 'ACTIVE' NOT NULL,
    create_time           TIMESTAMP   NOT NULL,
    create_by             VARCHAR2(64 CHAR),
    update_time           TIMESTAMP,
    update_by             VARCHAR2(64 CHAR),
    deleted               NUMBER(5)    DEFAULT 0 NOT NULL,
    tenant_id             NUMBER(19)      DEFAULT 0 NOT NULL,
    version               NUMBER(19)      DEFAULT 0 NOT NULL
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
-- role：'USER' / 'ASSISTANT' / 'SYSTEM'（VARCHAR2(4000 CHAR) + String，仓库惯例，不建 enum）
-- msg_order：本会话内消息顺序号（0-based，写入时由 Java 层计算 = 已有消息数）
-- content：大文本，PG 用 TEXT（agent 模块 V19/V20 惯例）
-- ===================================================================
CREATE TABLE sw_agent_message (
    id          NUMBER(19)      NOT NULL PRIMARY KEY,
    session_id  NUMBER(19)      NOT NULL,
    role        VARCHAR2(20 CHAR) NOT NULL,
    content     CLOB        NOT NULL,
    msg_order   NUMBER(10)         NOT NULL,
    create_time TIMESTAMP   NOT NULL,
    create_by   VARCHAR2(64 CHAR),
    update_time TIMESTAMP,
    update_by   VARCHAR2(64 CHAR),
    deleted     NUMBER(5)    DEFAULT 0 NOT NULL,
    tenant_id   NUMBER(19)      DEFAULT 0 NOT NULL,
    version     NUMBER(19)      DEFAULT 0 NOT NULL
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
-- tool_call_args / tool_call_result：CLOB 字符串，可能较长，PG 用 TEXT
-- latency_ms：工具执行耗时（毫秒），由 FunctionToolCallback lambda 包装计时
-- ===================================================================
CREATE TABLE sw_agent_tool_call_log (
    id               NUMBER(19)       NOT NULL PRIMARY KEY,
    session_id       NUMBER(19)       NOT NULL,
    tool_name        VARCHAR2(100 CHAR) NOT NULL,
    tool_call_args   CLOB,
    tool_call_result CLOB,
    latency_ms       NUMBER(19),
    create_time      TIMESTAMP    NOT NULL,
    create_by        VARCHAR2(64 CHAR),
    update_time      TIMESTAMP,
    update_by        VARCHAR2(64 CHAR),
    deleted          NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id        NUMBER(19)       DEFAULT 0 NOT NULL,
    version          NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_agent_tcl_session ON sw_agent_tool_call_log (session_id, deleted);

COMMENT ON TABLE sw_agent_tool_call_log IS 'M07 Agent 工具调用日志（F04 对话交互）';
COMMENT ON COLUMN sw_agent_tool_call_log.session_id IS '所属会话 id（sw_agent_session）';
COMMENT ON COLUMN sw_agent_tool_call_log.tool_name IS '工具名（白名单表 name）';
COMMENT ON COLUMN sw_agent_tool_call_log.tool_call_args IS '工具入参（CLOB 字符串）';
COMMENT ON COLUMN sw_agent_tool_call_log.tool_call_result IS '工具返回（CLOB 字符串）';
COMMENT ON COLUMN sw_agent_tool_call_log.latency_ms IS '工具执行耗时（毫秒）';

-- ======== V24（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V24: sw_agent_model_config 扩展多Key轮询/额度限流字段 (PostgreSQL)
-- M07-F01：group_key 归组 + sort 优先级 + locked_until 临时锁定 + quota_cooldown_seconds 冷却期
-- ===================================================================
ALTER TABLE sw_agent_model_config add group_key VARCHAR2(100 CHAR);
ALTER TABLE sw_agent_model_config add sort NUMBER(10) DEFAULT 0 NOT NULL;
ALTER TABLE sw_agent_model_config add locked_until TIMESTAMP;
ALTER TABLE sw_agent_model_config add quota_cooldown_seconds NUMBER(10) DEFAULT 60 NOT NULL;

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
-- 适配 agent 模块惯例（V19-V24：审计列在后、create_by VARCHAR2(64 CHAR)、大字段 TEXT）
-- 本表走 MyBatis-Plus 常规通道：@TableLogic + 租户拦截器自动处理 deleted/tenant_id
-- ===================================================================
CREATE TABLE sw_agent_graph_def (
    id           NUMBER(19) NOT NULL PRIMARY KEY,
    graph_key    VARCHAR2(100 CHAR) NOT NULL,
    name         VARCHAR2(200 CHAR) NOT NULL,
    def_version  NUMBER(10) DEFAULT 1 NOT NULL,
    status       VARCHAR2(20 CHAR) DEFAULT 'DRAFT' NOT NULL,
    graph_json   CLOB,
    create_time  TIMESTAMP,
    create_by    VARCHAR2(64 CHAR),
    update_time  TIMESTAMP,
    update_by    VARCHAR2(64 CHAR),
    deleted      NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id    NUMBER(19) DEFAULT 0 NOT NULL,
    version      NUMBER(19) DEFAULT 0 NOT NULL
);

CREATE UNIQUE INDEX uk_sw_agent_graph_key ON sw_agent_graph_def (tenant_id, graph_key);
CREATE INDEX idx_sw_agent_graph_tenant_deleted ON sw_agent_graph_def (tenant_id, deleted);

COMMENT ON TABLE  sw_agent_graph_def IS 'M07 Agent 图定义表（图设计器后端存储）';
COMMENT ON COLUMN sw_agent_graph_def.graph_key IS '图业务 key（服务端生成，发布后冻结）';
COMMENT ON COLUMN sw_agent_graph_def.name IS '图名称';
COMMENT ON COLUMN sw_agent_graph_def.def_version IS '定义版本号（每次发布递增）';
COMMENT ON COLUMN sw_agent_graph_def.status IS '状态：DRAFT（草稿）/ PUBLISHED（已发布）';
COMMENT ON COLUMN sw_agent_graph_def.graph_json IS '图 CLOB 文档（ProcessGraph 序列化，config/style 不透明透传）';

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
VALUES (15, current_timestamp, current_timestamp, 0, 0, 7, 'AgentGraphDef', '图定义管理', 0, 1, 'graph-def', 'agent/views/GraphDefList', 'agent:model:view', 'Share', 10);

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
    id                NUMBER(19)      NOT NULL PRIMARY KEY,
    graph_def_id      NUMBER(19)      NOT NULL,
    graph_def_version NUMBER(10)         NOT NULL,
    status            VARCHAR2(20 CHAR) NOT NULL,
    input             CLOB,
    result_text       CLOB,
    error_category    VARCHAR2(50 CHAR),
    error_message     CLOB,
    latency_ms        NUMBER(19),
    create_time       TIMESTAMP   NOT NULL,
    create_by         VARCHAR2(64 CHAR),
    update_time       TIMESTAMP,
    update_by         VARCHAR2(64 CHAR),
    deleted           NUMBER(5)    DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)      DEFAULT 0 NOT NULL,
    version           NUMBER(19)      DEFAULT 0 NOT NULL
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
-- variable_snapshot：该节点执行后的变量表快照（CLOB，Map<String,String>）
-- ===================================================================
CREATE TABLE sw_agent_graph_execution_node (
    id                NUMBER(19)       NOT NULL PRIMARY KEY,
    execution_id      NUMBER(19)       NOT NULL,
    node_seq          NUMBER(10)          NOT NULL,
    branch_id         VARCHAR2(64 CHAR)  NOT NULL,
    node_id           VARCHAR2(100 CHAR) NOT NULL,
    node_type         VARCHAR2(20 CHAR)  NOT NULL,
    node_latency_ms   NUMBER(19),
    variable_snapshot CLOB,
    create_time       TIMESTAMP    NOT NULL,
    create_by         VARCHAR2(64 CHAR),
    update_time       TIMESTAMP,
    update_by         VARCHAR2(64 CHAR),
    deleted           NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)       DEFAULT 0 NOT NULL,
    version           NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_agent_genode_exec ON sw_agent_graph_execution_node (execution_id, node_seq, deleted);

COMMENT ON TABLE sw_agent_graph_execution_node IS 'M07 Agent 图执行节点明细（Step12 执行轨迹）';
COMMENT ON COLUMN sw_agent_graph_execution_node.execution_id IS '所属执行记录 id（sw_agent_graph_execution）';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_seq IS '本次执行内全局访问步序（1-based）';
COMMENT ON COLUMN sw_agent_graph_execution_node.branch_id IS '并行分支标识（FORK 按出边顺序追加下标）';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_id IS '图节点 id';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_type IS '节点类型（START/LLM/TOOL/CONDITION/LOOP/FORK/JOIN/END）';
COMMENT ON COLUMN sw_agent_graph_execution_node.node_latency_ms IS '节点级耗时（毫秒）';
COMMENT ON COLUMN sw_agent_graph_execution_node.variable_snapshot IS '该节点执行后的变量表快照（CLOB）';

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
--   · hidden 列类型为 NUMBER(1)，使用 0 字面量（V6 惯例）
-- ===================================================================

-- 1. 文件存储（顶级菜单）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (16, current_timestamp, current_timestamp, 0, 0, 0, 'Storage', '文件管理', 0, 1, 'storage', 'storage/views/StorageList', 'storage:view', 'FolderOpened', 80);

-- 2. 定时任务（顶级目录）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (17, current_timestamp, current_timestamp, 0, 0, 0, 'Job', '定时任务', 0, 0, 'job', NULL, 'job:view', 'Clock', 90);

-- 3. 定时任务 → 任务管理
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (18, current_timestamp, current_timestamp, 0, 0, 17, 'JobList', '任务管理', 0, 1, 'job/list', 'job/views/JobList', 'job:list', 'List', 10);

-- 4. 定时任务 → 执行日志
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (19, current_timestamp, current_timestamp, 0, 0, 17, 'JobLog', '执行日志', 0, 1, 'job/log', 'job/views/JobLog', 'job:log', 'Document', 20);

-- ======== V30（来源: sw-bootstrap，副本 1 处已去重） ========
-- Smart-WorkFlow 角色部门关联表（H2/PostgreSQL 双端逐字一致）
-- 数据范围 CUSTOM（自定义部门集合）时，角色可见部门由本表承载；
-- 结构/风格对齐 V1 sys_user_role（同构关联表，含租户列与逻辑删除）。
create table sys_role_dept (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    role_id         NUMBER(19)          not null,
    dept_id         NUMBER(19)          not null
);
create unique index uk_sys_role_dept on sys_role_dept (role_id, dept_id);

-- ======== V31（来源: sw-bootstrap，副本 1 处已去重） ========
-- P24/I49：普通 admin seed 与 job/storage 方法权限。
-- 历史冲突必须显式失败：条件插入会触发既有主键/唯一约束，不得静默跳过、覆盖或夺取既有角色。
INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version,
                      name, code, sort, status, data_scope, built_in, remark)
SELECT 2, current_timestamp, current_timestamp, 0, 0, 0,
       '管理员', 'admin', 10, 1, 0, 0, 'P24 V31 conflict sentinel'
 from dual WHERE EXISTS (SELECT 1 FROM sys_role WHERE code = 'admin' AND deleted = 0)
   OR EXISTS (SELECT 1 FROM sys_role WHERE id = 2 AND deleted = 0);

INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version,
                      name, code, sort, status, data_scope, built_in, remark)
VALUES (2, current_timestamp, current_timestamp, 0, 0, 0,
        '管理员', 'admin', 10, 1, 0, 0, '系统初始化普通管理员角色');

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 200, current_timestamp, current_timestamp, 0, 0, 18, 'JobCreate', '任务新增', 2, '', '', 'job:create', '', 30, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 200);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 201, current_timestamp, current_timestamp, 0, 0, 18, 'JobUpdate', '任务修改', 2, '', '', 'job:update', '', 31, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 201);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 202, current_timestamp, current_timestamp, 0, 0, 18, 'JobDelete', '任务删除', 2, '', '', 'job:delete', '', 32, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 202);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 203, current_timestamp, current_timestamp, 0, 0, 18, 'JobPause', '任务暂停', 2, '', '', 'job:pause', '', 33, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 203);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 204, current_timestamp, current_timestamp, 0, 0, 18, 'JobResume', '任务恢复', 2, '', '', 'job:resume', '', 34, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 204);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 205, current_timestamp, current_timestamp, 0, 0, 18, 'JobTrigger', '任务触发', 2, '', '', 'job:trigger', '', 35, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 205);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 206, current_timestamp, current_timestamp, 0, 0, 16, 'StorageUpload', '文件上传', 2, '', '', 'storage:upload', '', 30, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 206);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 207, current_timestamp, current_timestamp, 0, 0, 16, 'StorageDelete', '文件删除', 2, '', '', 'storage:delete', '', 31, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 207);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 208, current_timestamp, current_timestamp, 0, 0, 16, 'StorageDownload', '文件下载', 2, '', '', 'storage:download', '', 32, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 208);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT COALESCE((SELECT MAX(id) FROM sys_role_menu), 0) + ROW_NUMBER() OVER (ORDER BY m.id), current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.code = 'admin' AND r.deleted = 0);

-- ======== V32（来源: sw-bootstrap，副本 1 处已去重） ========
CREATE TABLE sys_user_post (
    id NUMBER(19) NOT NULL,
    create_time TIMESTAMP,
    create_by NUMBER(19),
    update_time TIMESTAMP,
    update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    user_id NUMBER(19) NOT NULL,
    post_id NUMBER(19) NOT NULL
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
VALUES (209, current_timestamp, current_timestamp, 0, 0, 7, 'AgentModel', '大模型管理', 0, 1, 'model', 'agent/views/ModelList', 'agent:model:view', 'Cpu', 20);

-- 2. 智能体 → 大模型管理 → 新建/编辑/删除（按钮级，复用 agent:model:manage）
--    字段清单与列顺序对齐 V31 按钮行先例（hidden 在列清单末尾）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 210, current_timestamp, current_timestamp, 0, 0, 209, 'AgentModelManage', '模型新建/编辑/删除', 2, '', '', 'agent:model:manage', '', 30, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 210);

-- 3. 智能体 → 大模型管理 → 连通性测试（按钮级，agent:model:test）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 211, current_timestamp, current_timestamp, 0, 0, 209, 'AgentModelTest', '模型连通性测试', 2, '', '', 'agent:model:test', '', 31, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 211);

-- ======== V34（来源: sw-bootstrap，副本 1 处已去重） ========
-- P28/I36（D112）：用户组维护与成员绑定基础闭环 —— 主表 + 成员关系表。
-- 语义：租户内扁平虚拟用户组（无层级/无负责人），业务标识租户内稳定唯一；
--       用户-组多对多成员关系；全部逻辑删除；双方言（H2/PostgreSQL）逐字一致。
-- 唯一性采用 (tenant_id, deleted) 复合模式（对齐 V13/V32 逻辑删除唯一语义）：
--   同一租户内同一业务标识最多一条 deleted=0 生效行；deleted=1 历史行允许同 key 共存。

CREATE TABLE sys_user_group (
    id NUMBER(19) NOT NULL,
    create_time TIMESTAMP,
    create_by NUMBER(19),
    update_time TIMESTAMP,
    update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    group_code VARCHAR2(64 CHAR) NOT NULL,
    group_name VARCHAR2(64 CHAR) NOT NULL,
    status NUMBER(5) DEFAULT 0 NOT NULL,
    remark VARCHAR2(255 CHAR)
);
CREATE UNIQUE INDEX uk_sys_user_group_code ON sys_user_group (tenant_id, group_code, deleted);
CREATE INDEX idx_sys_user_group_name ON sys_user_group (tenant_id, group_name, deleted);
CREATE INDEX idx_sys_user_group_status ON sys_user_group (tenant_id, status, deleted);

CREATE TABLE sys_user_group_member (
    id NUMBER(19) NOT NULL,
    create_time TIMESTAMP,
    create_by NUMBER(19),
    update_time TIMESTAMP,
    update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    group_id NUMBER(19) NOT NULL,
    user_id NUMBER(19) NOT NULL
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
alter table sw_agent_message add (input_tokens  NUMBER(19), output_tokens NUMBER(19));

COMMENT ON COLUMN sw_agent_message.input_tokens IS '供应商返回的输入 Token 数（未知时为 NULL，不为 0）';
COMMENT ON COLUMN sw_agent_message.output_tokens IS '供应商返回的输出 Token 数（未知时为 NULL，不为 0）';

-- 2. sw_agent_graph_execution: 图执行级 Token 汇总
alter table sw_agent_graph_execution add (input_tokens  NUMBER(19), output_tokens NUMBER(19));

COMMENT ON COLUMN sw_agent_graph_execution.input_tokens IS '本次图执行全部 LLM 节点输入 Token 汇总（未知时不参与计算）';
COMMENT ON COLUMN sw_agent_graph_execution.output_tokens IS '本次图执行全部 LLM 节点输出 Token 汇总（未知时不参与计算）';

-- 3. sw_agent_graph_execution_node: 图执行节点级 Token
alter table sw_agent_graph_execution_node add (input_tokens  NUMBER(19), output_tokens NUMBER(19));

COMMENT ON COLUMN sw_agent_graph_execution_node.input_tokens IS '该节点 LLM 调用的输入 Token（非 LLM 节点或供应商未返回时为 NULL）';
COMMENT ON COLUMN sw_agent_graph_execution_node.output_tokens IS '该节点 LLM 调用的输出 Token（非 LLM 节点或供应商未返回时为 NULL）';

-- ======== V36（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: V36: 初始化 Agent 图调试会话及节点明细表 (PostgreSQL)
-- M07-F02-04 图单步调试闭环：调试会话主表 + 节点轨迹明细
-- sw_agent_graph_debug_session
--   graph_def_id/graph_def_version：调试时图定义快照锚点（同 V27 执行表惯例）
--   graph_json：调试时图 CLOB 快照（TEXT，含 elements/edges）
--   status：PAUSED（断点暂停）/ COMPLETED / FAILED / STOPPED（用户停止）/ EXPIRED（TTL 过期）
--   input/breakpoints/state_json/result_text/error_message：大文本，PG 用 TEXT（agent 模块 V19/V22/V27 惯例）
--   breakpoints：断点列表（CLOB 数组，元素为 nodeId）
--   state_json：可恢复解释器状态（CLOB，含 variables/activePoints/loopCounts/joinCounts/nextNodeIds 等）
--   latency_ms/expires_at/input_tokens/output_tokens：同 V27/V35 执行表语义，调试会话维度
--   expires_at：会话过期时间（TTL，到期后置为 EXPIRED）
-- sw_agent_graph_debug_node
--   debug_session_id：归属调试会话（sw_agent_graph_debug_session）
--   node_seq/branch_id/node_latency_ms/variable_snapshot/input_tokens/output_tokens：同 V28 节点表语义
-- ===================================================================
CREATE TABLE sw_agent_graph_debug_session (
    id                NUMBER(19)      NOT NULL PRIMARY KEY,
    graph_def_id      NUMBER(19)      NOT NULL,
    graph_def_version NUMBER(10)         NOT NULL,
    graph_json        CLOB,
    status            VARCHAR2(20 CHAR) NOT NULL,
    input             CLOB,
    breakpoints       CLOB,
    state_json        CLOB,
    result_text       CLOB,
    error_category    VARCHAR2(50 CHAR),
    error_message     CLOB,
    latency_ms        NUMBER(19),
    expires_at        TIMESTAMP,
    input_tokens      NUMBER(19),
    output_tokens     NUMBER(19),
    create_time       TIMESTAMP   NOT NULL,
    create_by         VARCHAR2(64 CHAR),
    update_time       TIMESTAMP,
    update_by         VARCHAR2(64 CHAR),
    deleted           NUMBER(5)    DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)      DEFAULT 0 NOT NULL,
    version           NUMBER(19)      DEFAULT 0 NOT NULL
);

CREATE INDEX idx_gexec_debug_graph   ON sw_agent_graph_debug_session (graph_def_id, deleted);
CREATE INDEX idx_gexec_debug_expires ON sw_agent_graph_debug_session (expires_at);

CREATE TABLE sw_agent_graph_debug_node (
    id                NUMBER(19)       NOT NULL PRIMARY KEY,
    debug_session_id  NUMBER(19)       NOT NULL,
    node_seq          NUMBER(10)          NOT NULL,
    branch_id         VARCHAR2(64 CHAR)  NOT NULL,
    node_id           VARCHAR2(100 CHAR) NOT NULL,
    node_type         VARCHAR2(20 CHAR)  NOT NULL,
    node_latency_ms   NUMBER(19),
    variable_snapshot CLOB,
    input_tokens      NUMBER(19),
    output_tokens     NUMBER(19),
    create_time       TIMESTAMP    NOT NULL,
    create_by         VARCHAR2(64 CHAR),
    update_time       TIMESTAMP,
    update_by         VARCHAR2(64 CHAR),
    deleted           NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)       DEFAULT 0 NOT NULL,
    version           NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE INDEX idx_genode_debug ON sw_agent_graph_debug_node (debug_session_id, node_seq, deleted);

COMMENT ON TABLE sw_agent_graph_debug_session IS 'M07-F02-04 Agent 图调试会话（单步调试闭环）';
COMMENT ON COLUMN sw_agent_graph_debug_session.graph_def_id IS '图定义 id（sw_agent_graph_def）';
COMMENT ON COLUMN sw_agent_graph_debug_session.graph_def_version IS '调试时图定义版本快照';
COMMENT ON COLUMN sw_agent_graph_debug_session.graph_json IS '调试时图 CLOB 快照';
COMMENT ON COLUMN sw_agent_graph_debug_session.status IS '调试状态：PAUSED/COMPLETED/FAILED/STOPPED/EXPIRED';
COMMENT ON COLUMN sw_agent_graph_debug_session.input IS '调试入参文本';
COMMENT ON COLUMN sw_agent_graph_debug_session.breakpoints IS '断点列表（CLOB 数组，元素为 nodeId）';
COMMENT ON COLUMN sw_agent_graph_debug_session.state_json IS '可恢复解释器状态（CLOB，含 variables/activePoints/loopCounts/joinCounts/next 等）';
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
COMMENT ON COLUMN sw_agent_graph_debug_node.variable_snapshot IS '该节点执行后的变量表快照（CLOB）';
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
VALUES (212, current_timestamp, current_timestamp, 0, 0, 7, 'AgentTool', '工具管理', 0, 1, 'tool', 'agent/views/ToolList', 'agent:tool:view', 'SetUp', 30);

-- 2. 智能体 → 工具管理 → 新建/编辑/删除/启停（按钮级，agent:tool:manage）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 213, current_timestamp, current_timestamp, 0, 0, 212, 'AgentToolManage', '工具新建/编辑/删除/启停', 2, '', '', 'agent:tool:manage', '', 1, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 213);

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
--     NUMBER(19) 主键（ASSIGN_ID 雪花）、无真实外键。
--   · 租户内模板代码唯一：仿 V13 sys_role(tenant_id, code, deleted)
--     复合唯一先例，显式含 tenant_id 与 deleted（支持软删重建）。
-- 注意：本文件注释中的占位符示例使用 ＄｛var｝ 全角写法，
--       避免 Flyway placeholder 解析把 ＄{var} 当作迁移变量。
-- ===================================================================

-- ==================== 1. 消息模板表 ====================
CREATE TABLE sw_notify_template (
    id                NUMBER(19) NOT NULL PRIMARY KEY,
    create_time       TIMESTAMP,
    create_by         VARCHAR2(64 CHAR),
    update_time       TIMESTAMP,
    update_by         VARCHAR2(64 CHAR),
    deleted           NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19) DEFAULT 0 NOT NULL,
    version           NUMBER(19) DEFAULT 0 NOT NULL,
    template_code     VARCHAR2(100 CHAR) NOT NULL,
    name              VARCHAR2(100 CHAR) NOT NULL,
    title_template    VARCHAR2(200 CHAR) NOT NULL,
    content_template  CLOB NOT NULL,
    enabled           NUMBER(5) DEFAULT 1 NOT NULL,
    remark            VARCHAR2(500 CHAR)
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
VALUES (215, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyInbox', '收件箱', 0, 1, 'inbox', 'notify/views/NotifyHome', 'notify:view', 'Bell', 10);

-- ==================== 4. 通知 → 消息模板（管理页） ====================
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
VALUES (216, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyTemplate', '消息模板', 0, 1, 'template', 'notify/views/NotifyTemplateList', 'notify:template:view', 'Tickets', 20);

-- ==================== 5. 消息模板按钮级权限（新建/编辑/删除/启停/预览/发送） ====================
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 217, current_timestamp, current_timestamp, 0, 0, 216, 'NotifyTemplateManage', '模板新建/编辑/删除/启停/预览/发送', 2, '', '', 'notify:template:manage', '', 1, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 217);

-- ======== V39（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V39: 通知批量发送页面与独立权限（M05-F01-01）— PostgreSQL 方言
--
-- 仅注册生产菜单/按钮资源；普通角色授权仍由管理员通过角色菜单管理完成。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 218, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyBatchSend', '批量发送', 0, 1,
       'batch-send', 'notify/views/NotifyBatchSend', 'notify:batch:send', 'Promotion', 30
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 218);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 219, current_timestamp, current_timestamp, 0, 0, 218, 'NotifyBatchSendAction', '批量发送操作', 2,
       '', '', 'notify:batch:send', '', 1, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 219);

-- ======== V40（来源: sw-basic/sw-basic-iot，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: IoT 设备与控制命令表 (PostgreSQL)
-- ===================================================================
-- 最小设备控制链：设备注册 / 状态 / 控制命令 / 执行结果。
-- 约定与 H2 版本对齐（8 基列 + NUMBER(19) PK + 无外键）。
-- ===================================================================

create table sw_iot_device (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    device_key        VARCHAR2(64 CHAR)     not null,
    name              VARCHAR2(200 CHAR)    not null,
    device_type       VARCHAR2(50 CHAR),
    status            VARCHAR2(20 CHAR)     DEFAULT 'OFFLINE' NOT NULL,
    last_online_time  timestamp
);

create index idx_sw_iot_device_key on sw_iot_device (tenant_id, device_key);

create table sw_iot_device_command (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    device_key        VARCHAR2(64 CHAR)     not null,
    command_key       VARCHAR2(64 CHAR)     not null,
    payload           VARCHAR2(1000 CHAR),
    status            VARCHAR2(20 CHAR)     DEFAULT 'PENDING' NOT NULL,
    result            VARCHAR2(1000 CHAR),
    approval_biz_id   VARCHAR2(64 CHAR)
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
ALTER TABLE sw_iot_device add product_id VARCHAR2(64 CHAR) DEFAULT 'unknown' NOT NULL;

-- 新增 device_name 字段（腾讯云设备名称，在产品内唯一）
ALTER TABLE sw_iot_device add device_name VARCHAR2(128 CHAR) DEFAULT 'unknown' NOT NULL;

-- 新增 tencent_status 字段（腾讯云在线状态）
ALTER TABLE sw_iot_device add tencent_status VARCHAR2(32 CHAR) DEFAULT 'offline';

-- 创建 product_id + device_name 唯一索引
create UNIQUE index uk_iot_device_product_device ON sw_iot_device (product_id, device_name, tenant_id);

-- ============================================================
-- sw_iot_device_command 表升级
-- ============================================================

-- 新增 product_id 字段
ALTER TABLE sw_iot_device_command add product_id VARCHAR2(64 CHAR) DEFAULT 'unknown' NOT NULL;

-- 新增 device_name 字段
ALTER TABLE sw_iot_device_command add device_name VARCHAR2(128 CHAR) DEFAULT 'unknown' NOT NULL;

-- 新增 command_type 字段（PROPERTY / ACTION）
ALTER TABLE sw_iot_device_command add command_type VARCHAR2(32 CHAR) DEFAULT 'PROPERTY';

-- 新增 semantic_mode 字段（DEFERRED / ONLINE_CONFIRM）
ALTER TABLE sw_iot_device_command add semantic_mode VARCHAR2(32 CHAR) DEFAULT 'DEFERRED';

-- 新增 idempotent_key 字段（幂等键，防止重复发送）
ALTER TABLE sw_iot_device_command add idempotent_key VARCHAR2(128 CHAR);

-- 新增 expiry_time 字段（命令过期时间）
ALTER TABLE sw_iot_device_command add expiry_time TIMESTAMP;

-- 新增 retry_count 字段（已尝试次数）
ALTER TABLE sw_iot_device_command add retry_count NUMBER(10) DEFAULT 0;

-- 新增 last_error 字段（最后失败原因）
ALTER TABLE sw_iot_device_command add last_error VARCHAR2(512 CHAR);

-- 新增 tencent_request_id 字段（腾讯云 RequestId）
ALTER TABLE sw_iot_device_command add tencent_request_id VARCHAR2(128 CHAR);

-- 新增 client_token 字段（腾讯云异步行为 ClientToken）
ALTER TABLE sw_iot_device_command add client_token VARCHAR2(128 CHAR);

-- 新增 device_output 字段（设备输出参数）
ALTER TABLE sw_iot_device_command add device_output CLOB;

-- 创建幂等键唯一索引
create UNIQUE index uk_iot_command_idempotent ON sw_iot_device_command (idempotent_key);

-- 创建按设备查询待发送命令的索引
create index idx_iot_command_status_device ON sw_iot_device_command (product_id, device_name, status);

-- 创建过期命令查询索引
create index idx_iot_command_expiry ON sw_iot_device_command (expiry_time, status);

-- ======== V43（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V43: 表单数据导入导出独立权限（P32 / M03-F04-02）— PostgreSQL 方言
--
-- 仅注册按钮权限资源（挂在低代码→表单设计菜单下）；
-- 普通角色授权仍由管理员通过角色菜单管理完成。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 230, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataTemplate', '下载模板', 0, 2,
       '', '', 'form:data:template', '', 1
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 230);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 231, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataImport', '数据导入', 0, 2,
       '', '', 'form:data:import', '', 2
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 231);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 232, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataExport', '数据导出', 0, 2,
       '', '', 'form:data:export', '', 3
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 232);

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
SELECT 20, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowTodo', '待办任务', 0, 1,
       'workflow/todo', 'workflow/views/TodoList', 'workflow:todo:view', 'Bell', 10
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 20);

-- 3. 已办任务
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 21, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowProcessed', '已办任务', 0, 1,
       'workflow/processed', 'workflow/views/ProcessedList', 'workflow:processed:view', 'Finished', 20
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 21);

-- 4. 流程监控（实例列表 + 结果/流转记录）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 22, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowInstances', '流程监控', 0, 1,
       'workflow/instances', 'workflow/views/ProcessInstanceList', 'workflow:instance:view', 'Monitor', 30
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 22);

-- 5. 流程定义
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 23, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowDefs', '流程定义', 0, 1,
       'workflow/defs', 'workflow/views/ProcessDefList', 'workflow:def:view', 'Setting', 40
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 23);

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
SELECT 300, current_timestamp, current_timestamp, 0, 0, p.id, 'UserCreate', '用户新增', 0, 2, '', '', 'system:user:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 301, current_timestamp, current_timestamp, 0, 0, p.id, 'UserUpdate', '用户修改', 0, 2, '', '', 'system:user:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 302, current_timestamp, current_timestamp, 0, 0, p.id, 'UserDelete', '用户删除', 0, 2, '', '', 'system:user:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:delete' AND m.deleted = 0);

-- -------------------- 角色管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 303, current_timestamp, current_timestamp, 0, 0, p.id, 'RoleCreate', '角色新增', 0, 2, '', '', 'system:role:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:role:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:role:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 304, current_timestamp, current_timestamp, 0, 0, p.id, 'RoleUpdate', '角色修改', 0, 2, '', '', 'system:role:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:role:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:role:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 305, current_timestamp, current_timestamp, 0, 0, p.id, 'RoleDelete', '角色删除', 0, 2, '', '', 'system:role:delete', '', 13
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
SELECT 306, current_timestamp, current_timestamp, 0, 0, p.id, 'FormDesignSave', '表单草稿保存', 0, 2, '', '', 'form:design:save', '', 21
FROM sys_menu p
WHERE p.permission = 'form:design' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'form:design:save' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 307, current_timestamp, current_timestamp, 0, 0, p.id, 'FormDesignPublish', '表单发布', 0, 2, '', '', 'form:design:publish', '', 22
FROM sys_menu p
WHERE p.permission = 'form:design' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'form:design:publish' AND m.deleted = 0);

-- -------------------- 流程侧按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 308, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefCreate', '流程创建', 0, 2, '', '', 'workflow:def:create', '', 11
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 309, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefSave', '流程修改', 0, 2, '', '', 'workflow:def:save', '', 12
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:save' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 310, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefPublish', '流程发布', 0, 2, '', '', 'workflow:def:publish', '', 13
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:publish' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 311, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefDelete', '流程删除', 0, 2, '', '', 'workflow:def:delete', '', 14
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
alter table sw_notify_message add channel VARCHAR2(40 CHAR) DEFAULT 'IN_APP' NOT NULL;
alter table sw_notify_message add delivery_status VARCHAR2(20 CHAR) DEFAULT 'SUCCESS' NOT NULL;
alter table sw_notify_message add external_message_id VARCHAR2(200 CHAR);
alter table sw_notify_message add failure_reason VARCHAR2(500 CHAR);
alter table sw_notify_message add idempotency_key VARCHAR2(200 CHAR);
create index idx_sw_notify_msg_delivery on sw_notify_message (tenant_id, delivery_status);
create unique index uk_sw_notify_msg_idempotency on sw_notify_message (tenant_id, idempotency_key);

-- ======== V49（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- P58：参与人快照、审批动作/意见、分支轨迹与抄送投递审计。
create table sw_bpm_participant_snapshot (
    id NUMBER(19) not null primary key, create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19), update_time timestamp DEFAULT current_timestamp NOT NULL, update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL, tenant_id NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null, node_key VARCHAR2(200 CHAR) not null, task_id VARCHAR2(64 CHAR) not null,
    participant_id VARCHAR2(64 CHAR) not null, participant_status VARCHAR2(20 CHAR) not null,
    invalid_reason VARCHAR2(500 CHAR)
);
create index idx_sw_bpm_participant_task on sw_bpm_participant_snapshot (tenant_id, task_id);

create table sw_bpm_approval_action (
    id NUMBER(19) not null primary key, create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19), update_time timestamp DEFAULT current_timestamp NOT NULL, update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL, tenant_id NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null, node_key VARCHAR2(200 CHAR) not null, task_id VARCHAR2(64 CHAR) not null,
    actor_id NUMBER(19) not null, action VARCHAR2(20 CHAR) not null, opinion_form_id VARCHAR2(200 CHAR),
    opinion_form_version VARCHAR2(64 CHAR), initialization_summary CLOB, opinion_data CLOB,
    settlement_status VARCHAR2(20 CHAR)
);
create unique index uk_sw_bpm_approval_task_actor on sw_bpm_approval_action (tenant_id, task_id, actor_id);

create table sw_bpm_branch_trace (
    id NUMBER(19) not null primary key, create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19), update_time timestamp DEFAULT current_timestamp NOT NULL, update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL, tenant_id NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null, node_key VARCHAR2(200 CHAR) not null, branch_id VARCHAR2(200 CHAR) not null,
    condition_version VARCHAR2(64 CHAR) not null, input_summary CLOB
);
create index idx_sw_bpm_branch_trace_instance on sw_bpm_branch_trace (tenant_id, process_instance_id);

create table sw_bpm_copy_record (
    id NUMBER(19) not null primary key, create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19), update_time timestamp DEFAULT current_timestamp NOT NULL, update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL, tenant_id NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null, node_key VARCHAR2(200 CHAR) not null, task_id VARCHAR2(64 CHAR),
    recipient_id VARCHAR2(64 CHAR) not null, delivery_status VARCHAR2(30 CHAR) not null, failure_reason VARCHAR2(500 CHAR)
);
create index idx_sw_bpm_copy_instance on sw_bpm_copy_record (tenant_id, process_instance_id);

-- ======== V50（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- P4：流程业务命令持久受理/队列 与 业务发起草稿。
create table sw_bpm_command (
    id NUMBER(19) not null primary key, create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19), update_time timestamp DEFAULT current_timestamp NOT NULL, update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL, tenant_id NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(19) DEFAULT 0 NOT NULL,
    command_key VARCHAR2(128 CHAR) not null, command_type VARCHAR2(32 CHAR) not null,
    channel VARCHAR2(16 CHAR) DEFAULT 'NORMAL' NOT NULL, status VARCHAR2(16 CHAR) DEFAULT 'PENDING' NOT NULL,
    payload CLOB, result CLOB, failure_reason VARCHAR2(1000 CHAR),
    retry_count NUMBER(10) DEFAULT 0 NOT NULL, next_retry_at timestamp,
    claimed_at timestamp, finished_at timestamp, initiator_id NUMBER(19)
);
create unique index uk_sw_bpm_command_key on sw_bpm_command (tenant_id, command_key);
create index idx_sw_bpm_command_dispatch on sw_bpm_command (status, channel, next_retry_at);

create table sw_bpm_draft (
    id NUMBER(19) not null primary key, create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19), update_time timestamp DEFAULT current_timestamp NOT NULL, update_by NUMBER(19),
    deleted NUMBER(5) DEFAULT 0 NOT NULL, tenant_id NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(19) DEFAULT 0 NOT NULL,
    title VARCHAR2(200 CHAR), form_key VARCHAR2(128 CHAR) not null, form_version NUMBER(19),
    process_def_key VARCHAR2(128 CHAR), payload CLOB,
    status VARCHAR2(20 CHAR) DEFAULT 'EDITING' NOT NULL, command_id NUMBER(19), submit_seq NUMBER(10) DEFAULT 0 NOT NULL,
    result_record_id VARCHAR2(36 CHAR), last_error VARCHAR2(1000 CHAR)
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
SELECT 312, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowP0Dispatch', 'P0紧急流程调用', 0, 2, '', '', 'workflow:p0:dispatch', '', 31
FROM sys_menu p
WHERE p.permission = 'workflow:def:view'
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 312 AND m.deleted = 0);

-- ======== V52（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- P4：表单提交幂等键（草稿/命令重试场景防重复落表单数据）。
alter table sw_form_trace add submit_idempotency_key VARCHAR2(128 CHAR);
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
SELECT 24, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyInstances', '我发起的', 0, 1,
       'workflow/my-instances', 'workflow/views/MyInstances', 'workflow:my:view', 'Document', 21
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 24);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 25, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyDrafts', '我的草稿', 0, 1,
       'workflow/my-drafts', 'workflow/views/MyDrafts', 'workflow:my:view', 'EditPen', 22
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 25);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 26, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyProcessed', '我的已办', 0, 1,
       'workflow/my-processed', 'workflow/views/MyProcessed', 'workflow:my:view', 'Finished', 23
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 26);

-- ======== V54（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- P4: 业务发起可见范围。空值表示当前租户内全部用户。
ALTER TABLE sw_form_def add visibility_scope CLOB;

-- ======== V55（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- P4 G4b：命令领取租约令牌 + 审批动作命令关联（与 H2 版本同语义）。
-- 1) sw_bpm_command.claim_token：每次 claimDue 生成一次性租约令牌；complete/reject/
--    failAndScheduleRetry 必须携带当前令牌方可写回。stale 回收后旧持有者在新持有者
--    仍 PROCESSING 期间的迟到写回因令牌不匹配被拒，不污染当前领取者。
-- 2) sw_bpm_approval_action.command_id：动作记录与受理命令关联；同一命令确认丢失
--    重投时据此定位自身已提交结果并恢复一致的可回查命令结果，不误报"已被处理"；
--    不同命令/意图的冲突仍确定性拒绝。同步 HTTP 入口该列为 null。

ALTER TABLE sw_bpm_command add claim_token VARCHAR2(64 CHAR);
ALTER TABLE sw_bpm_approval_action add command_id NUMBER(19);

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

create table sw_bpm_category (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    name              VARCHAR2(100 CHAR)    not null,
    sort_no           NUMBER(10)             DEFAULT 0 NOT NULL
);

ALTER TABLE sw_bpm_process_def add category_id NUMBER(19);

create table sw_bpm_urge_record (
    id                  NUMBER(19)          not null primary key,
    create_time         timestamp       DEFAULT current_timestamp NOT NULL,
    create_by           NUMBER(19),
    update_time         timestamp       DEFAULT current_timestamp NOT NULL,
    update_by           NUMBER(19),
    deleted             NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id           NUMBER(19)          DEFAULT 0 NOT NULL,
    version             NUMBER(19)          DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR)     not null,
    initiator_id        NUMBER(19)          not null,
    target_user_id      NUMBER(19),
    result              VARCHAR2(20 CHAR)     not null,
    detail              VARCHAR2(500 CHAR),
    cooldown_key        VARCHAR2(128 CHAR)
);

create index idx_sw_bpm_urge_instance ON sw_bpm_urge_record (process_instance_id);
create UNIQUE index uk_sw_bpm_urge_cooldown ON sw_bpm_urge_record (cooldown_key);

-- ======== V57（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V57: v0.0.2 OA — 通知发送尝试流水 (PostgreSQL)
-- ===================================================================
-- sw_notify_send_attempt：每次渠道投递（首次发送与失败重发）各写一行，
-- 保留原始失败、各次尝试与最新结果；消息行 delivery_status 恒为最新结果。
-- 全部幂等（IF NOT EXISTS），可重复执行；与 H2 版本同语义。
-- ===================================================================

create table sw_notify_send_attempt (
    id                  NUMBER(19)          not null primary key,
    create_time         timestamp       DEFAULT current_timestamp NOT NULL,
    create_by           NUMBER(19),
    update_time         timestamp       DEFAULT current_timestamp NOT NULL,
    update_by           NUMBER(19),
    deleted             NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id           NUMBER(19)          DEFAULT 0 NOT NULL,
    version             NUMBER(19)          DEFAULT 0 NOT NULL,
    message_id          NUMBER(19)          not null,
    attempt_no          NUMBER(10)             DEFAULT 1 NOT NULL,
    channel             VARCHAR2(32 CHAR),
    status              VARCHAR2(20 CHAR)     not null,
    failure_reason      VARCHAR2(500 CHAR),
    external_message_id VARCHAR2(200 CHAR)
);

create index idx_sw_notify_attempt_message ON sw_notify_send_attempt (message_id);

-- ======== V58（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V58: v0.0.2 OA — 工作台布局持久化 + 新页面菜单/权限 (H2/PostgreSQL 通用方言)
-- ===================================================================
-- 1) sys_user_workspace：用户级工作台布局与常用事项（租户+用户唯一，CLOB 存储）。
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

create table sys_user_workspace (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    user_id           NUMBER(19)          not null,
    layout_json       CLOB
);

create UNIQUE index uk_sys_user_workspace ON sys_user_workspace (tenant_id, user_id);

-- ==================== 菜单/权限种子 ====================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 320, current_timestamp, current_timestamp, 0, 0, 5, 'ProcessCatalog', '流程中心', 0, 1,
       'workflow/catalog', 'workflow/views/ProcessCatalog', 'workflow:catalog:view', 'Files', 24
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 320);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 321, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyCc', '抄送我的', 0, 1,
       'workflow/my-cc', 'workflow/views/MyCc', 'workflow:cc:view', 'Message', 25
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 321);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 322, current_timestamp, current_timestamp, 0, 0, 24, 'WorkflowUrge', '催办', 0, 2, '', '', 'workflow:urge', '', 1
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 322);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 323, current_timestamp, current_timestamp, 0, 0, 320, 'CatalogManage', '分类/事项管理', 0, 2, '', '', 'workflow:catalog:manage', '', 1
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 323);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 324, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyRecord', '发送记录', 0, 1,
       'record', 'notify/views/NotifyRecordList', 'notify:record:view', 'List', 30
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 324);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 325, current_timestamp, current_timestamp, 0, 0, 324, 'NotifyRecordResend', '失败重发', 0, 2, '', '', 'notify:record:resend', '', 1
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 325);

-- ======== V59（来源: sw-basic/sw-basic-iot，副本 1 处已去重） ========
-- ===================================================================
-- Smart-WorkFlow :: P21 IoT 平台化扩展 (PostgreSQL)
-- ===================================================================
-- 连接配置 / 产品与物模型版本 / 设备扩展 / Topic / 受控脚本 / 事件规则 /
-- 消息与运行记录 / 流程触发幂等 / 统一命令。
-- 约定：8 基列 + NUMBER(19) PK + 无外键，与既有 sw_iot_* 对齐。
-- ===================================================================

-- 1. 连接配置（独立于设备，可复用；凭证仅密文）
create table sw_iot_connection (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    code              VARCHAR2(64 CHAR)     not null,
    name              VARCHAR2(200 CHAR)    not null,
    conn_type         VARCHAR2(20 CHAR)     not null,
    enabled           NUMBER(5)        DEFAULT 1 NOT NULL,
    health_status     VARCHAR2(20 CHAR)     DEFAULT 'UNKNOWN' NOT NULL,
    last_check_time   timestamp,
    last_check_result VARCHAR2(500 CHAR),
    host              VARCHAR2(200 CHAR),
    port              NUMBER(10),
    use_tls           NUMBER(5)        DEFAULT 0 NOT NULL,
    client_id_prefix  VARCHAR2(64 CHAR),
    username          VARCHAR2(128 CHAR),
    password_cipher   VARCHAR2(512 CHAR),
    keepalive         NUMBER(10)             DEFAULT 60 NOT NULL,
    clean_session     NUMBER(5)        DEFAULT 1 NOT NULL,
    reconnect_min_sec NUMBER(10)             DEFAULT 1 NOT NULL,
    reconnect_max_sec NUMBER(10)             DEFAULT 60 NOT NULL,
    region            VARCHAR2(64 CHAR),
    endpoint          VARCHAR2(200 CHAR),
    secret_ref        VARCHAR2(256 CHAR),
    ext_json          CLOB
);
create unique index uk_sw_iot_conn_code on sw_iot_connection (tenant_id, code);

-- 2. 产品（承载统一物模型）
create table sw_iot_product (
    id                 NUMBER(19)          not null primary key,
    create_time        timestamp       DEFAULT current_timestamp NOT NULL,
    create_by          NUMBER(19),
    update_time        timestamp       DEFAULT current_timestamp NOT NULL,
    update_by          NUMBER(19),
    deleted            NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id          NUMBER(19)          DEFAULT 0 NOT NULL,
    version            NUMBER(19)          DEFAULT 0 NOT NULL,
    code               VARCHAR2(64 CHAR)     not null,
    name               VARCHAR2(200 CHAR)    not null,
    conn_id            NUMBER(19),
    conn_type          VARCHAR2(20 CHAR)     DEFAULT 'MQTT' NOT NULL,
    model_status       VARCHAR2(20 CHAR)     DEFAULT 'DRAFT' NOT NULL,
    published_model_id NUMBER(19),
    description        VARCHAR2(500 CHAR)
);
create unique index uk_sw_iot_product_code on sw_iot_product (tenant_id, code);

-- 3. 物模型版本（草稿→校验/发布→归档；content_json = properties/events/actions）
create table sw_iot_thing_model (
    id           NUMBER(19)          not null primary key,
    create_time  timestamp       DEFAULT current_timestamp NOT NULL,
    create_by    NUMBER(19),
    update_time  timestamp       DEFAULT current_timestamp NOT NULL,
    update_by    NUMBER(19),
    deleted      NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id    NUMBER(19)          DEFAULT 0 NOT NULL,
    version      NUMBER(19)          DEFAULT 0 NOT NULL,
    product_id   NUMBER(19)          not null,
    model_version NUMBER(10)            not null,
    status       VARCHAR2(20 CHAR)     DEFAULT 'DRAFT' NOT NULL,
    content_json CLOB,
    publish_time timestamp
);
create unique index uk_sw_iot_model_ver on sw_iot_thing_model (tenant_id, product_id, model_version);

-- 4. 设备扩展列（保留既有腾讯字段）
alter table sw_iot_device add connection_id      NUMBER(19);
alter table sw_iot_device add product_ref_id     NUMBER(19);
alter table sw_iot_device add manage_status      VARCHAR2(20 CHAR) DEFAULT 'DRAFT' NOT NULL;
alter table sw_iot_device add process_access_enabled NUMBER(5) DEFAULT 0 NOT NULL;
alter table sw_iot_device add last_report_time   timestamp;
alter table sw_iot_device add last_command_time  timestamp;
alter table sw_iot_device add labels             VARCHAR2(500 CHAR);
alter table sw_iot_device add ext_json           CLOB;

-- 5. Topic 配置（订阅/发布、方向、QoS、载荷映射）
create table sw_iot_topic (
    id            NUMBER(19)          not null primary key,
    create_time   timestamp       DEFAULT current_timestamp NOT NULL,
    create_by     NUMBER(19),
    update_time   timestamp       DEFAULT current_timestamp NOT NULL,
    update_by     NUMBER(19),
    deleted       NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id     NUMBER(19)          DEFAULT 0 NOT NULL,
    version       NUMBER(19)          DEFAULT 0 NOT NULL,
    conn_id       NUMBER(19)          not null,
    product_id    NUMBER(19),
    topic         VARCHAR2(200 CHAR)    not null,
    direction     VARCHAR2(10 CHAR)     DEFAULT 'UP' NOT NULL,
    qos           NUMBER(10)             DEFAULT 1 NOT NULL,
    retain        NUMBER(5)        DEFAULT 0 NOT NULL,
    payload_type  VARCHAR2(20 CHAR)     DEFAULT 'RAW' NOT NULL,
    mapping_json  CLOB,
    enabled       NUMBER(5)        DEFAULT 1 NOT NULL,
    remark        VARCHAR2(500 CHAR)
);
create unique index uk_sw_iot_topic on sw_iot_topic (tenant_id, topic);

-- 6. 受控脚本与版本
create table sw_iot_script (
    id               NUMBER(19)          not null primary key,
    create_time      timestamp       DEFAULT current_timestamp NOT NULL,
    create_by        NUMBER(19),
    update_time      timestamp       DEFAULT current_timestamp NOT NULL,
    update_by        NUMBER(19),
    deleted          NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id        NUMBER(19)          DEFAULT 0 NOT NULL,
    version          NUMBER(19)          DEFAULT 0 NOT NULL,
    code             VARCHAR2(64 CHAR)     not null,
    name             VARCHAR2(200 CHAR)    not null,
    language         VARCHAR2(10 CHAR)     not null,
    trigger_type     VARCHAR2(20 CHAR)     DEFAULT 'MESSAGE' NOT NULL,
    binding_json     CLOB,
    input_schema     CLOB,
    output_schema    CLOB,
    status           VARCHAR2(20 CHAR)     DEFAULT 'DRAFT' NOT NULL,
    current_version  NUMBER(10)             DEFAULT 1 NOT NULL,
    published_version NUMBER(10),
    timeout_ms       NUMBER(10)             DEFAULT 5000 NOT NULL
);
create unique index uk_sw_iot_script_code on sw_iot_script (tenant_id, code);

create table sw_iot_script_version (
    id           NUMBER(19)          not null primary key,
    create_time  timestamp       DEFAULT current_timestamp NOT NULL,
    create_by    NUMBER(19),
    update_time  timestamp       DEFAULT current_timestamp NOT NULL,
    update_by    NUMBER(19),
    deleted      NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id    NUMBER(19)          DEFAULT 0 NOT NULL,
    version      NUMBER(19)          DEFAULT 0 NOT NULL,
    script_id    NUMBER(19)          not null,
    script_version NUMBER(10)           not null,
    source_code  CLOB,
    status       VARCHAR2(20 CHAR)     DEFAULT 'DRAFT' NOT NULL,
    publish_time timestamp
);
create unique index uk_sw_iot_script_ver on sw_iot_script_version (tenant_id, script_id, script_version);

create table sw_iot_script_exec (
    id             NUMBER(19)          not null primary key,
    create_time    timestamp       DEFAULT current_timestamp NOT NULL,
    create_by      NUMBER(19),
    update_time    timestamp       DEFAULT current_timestamp NOT NULL,
    update_by      NUMBER(19),
    deleted        NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id      NUMBER(19)          DEFAULT 0 NOT NULL,
    version        NUMBER(19)          DEFAULT 0 NOT NULL,
    script_id      NUMBER(19)          not null,
    script_version NUMBER(10)             not null,
    trigger_source VARCHAR2(20 CHAR),
    trigger_ref    VARCHAR2(128 CHAR),
    device_ref     VARCHAR2(128 CHAR),
    status         VARCHAR2(20 CHAR)     not null,
    input_json     CLOB,
    output_json    CLOB,
    error          VARCHAR2(1000 CHAR),
    duration_ms    NUMBER(19),
    side_effect    NUMBER(5)        DEFAULT 0 NOT NULL,
    idempotent_key VARCHAR2(128 CHAR)
);
create index idx_sw_iot_exec_script on sw_iot_script_exec (tenant_id, script_id, create_time);

-- 7. 事件规则（阈值/变化/事件/上下线；防抖/冷却；流程映射）
create table sw_iot_event_rule (
    id                   NUMBER(19)          not null primary key,
    create_time          timestamp       DEFAULT current_timestamp NOT NULL,
    create_by            NUMBER(19),
    update_time          timestamp       DEFAULT current_timestamp NOT NULL,
    update_by            NUMBER(19),
    deleted              NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id            NUMBER(19)          DEFAULT 0 NOT NULL,
    version              NUMBER(19)          DEFAULT 0 NOT NULL,
    code                 VARCHAR2(64 CHAR)     not null,
    name                 VARCHAR2(200 CHAR)    not null,
    device_id            NUMBER(19)          not null,
    rule_type            VARCHAR2(20 CHAR)     not null,
    condition_json       CLOB,
    debounce_ms          NUMBER(19)          DEFAULT 0 NOT NULL,
    cooldown_ms          NUMBER(19)          DEFAULT 0 NOT NULL,
    continuous_count     NUMBER(10)             DEFAULT 1 NOT NULL,
    script_id            NUMBER(19),
    process_template_key VARCHAR2(128 CHAR),
    form_mapping_json    CLOB,
    process_enabled      NUMBER(5)        DEFAULT 0 NOT NULL,
    status               VARCHAR2(20 CHAR)     DEFAULT 'DRAFT' NOT NULL,
    rule_version         NUMBER(10)             DEFAULT 1 NOT NULL,
    last_fired_time      timestamp
);
create unique index uk_sw_iot_rule_code on sw_iot_event_rule (tenant_id, code);
create index idx_sw_iot_rule_device on sw_iot_event_rule (tenant_id, device_id);

-- 8. 运行记录
create table sw_iot_message_log (
    id            NUMBER(19)          not null primary key,
    create_time   timestamp       DEFAULT current_timestamp NOT NULL,
    create_by     NUMBER(19),
    update_time   timestamp       DEFAULT current_timestamp NOT NULL,
    update_by     NUMBER(19),
    deleted       NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id     NUMBER(19)          DEFAULT 0 NOT NULL,
    version       NUMBER(19)          DEFAULT 0 NOT NULL,
    conn_id       NUMBER(19),
    device_id     NUMBER(19),
    topic         VARCHAR2(200 CHAR),
    direction     VARCHAR2(10 CHAR)     DEFAULT 'UP' NOT NULL,
    message_id    VARCHAR2(128 CHAR),
    dedup_key     VARCHAR2(128 CHAR),
    payload       CLOB,
    payload_type  VARCHAR2(20 CHAR),
    parse_status  VARCHAR2(20 CHAR)     DEFAULT 'RECEIVED' NOT NULL,
    parse_error   VARCHAR2(500 CHAR),
    qos           NUMBER(10)             DEFAULT 0 NOT NULL
);
create index idx_sw_iot_msg_dedup on sw_iot_message_log (tenant_id, dedup_key);
create index idx_sw_iot_msg_topic on sw_iot_message_log (tenant_id, topic, create_time);

create table sw_iot_property_record (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    device_id       NUMBER(19)          not null,
    property_id     VARCHAR2(64 CHAR)     not null,
    value_json      VARCHAR2(1000 CHAR),
    pre_value_json  VARCHAR2(1000 CHAR),
    report_time     timestamp       not null
);
create index idx_sw_iot_prop_dev on sw_iot_property_record (tenant_id, device_id, property_id, report_time);

create table sw_iot_event_record (
    id          NUMBER(19)          not null primary key,
    create_time timestamp       DEFAULT current_timestamp NOT NULL,
    create_by   NUMBER(19),
    update_time timestamp       DEFAULT current_timestamp NOT NULL,
    update_by   NUMBER(19),
    deleted     NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id   NUMBER(19)          DEFAULT 0 NOT NULL,
    version     NUMBER(19)          DEFAULT 0 NOT NULL,
    device_id   NUMBER(19)          not null,
    event_id    VARCHAR2(64 CHAR)     not null,
    payload     CLOB,
    occur_time  timestamp       not null
);
create index idx_sw_iot_event_dev on sw_iot_event_record (tenant_id, device_id, occur_time);

-- 9. 流程触发幂等记录
create table sw_iot_process_trigger (
    id                  NUMBER(19)          not null primary key,
    create_time         timestamp       DEFAULT current_timestamp NOT NULL,
    create_by           NUMBER(19),
    update_time         timestamp       DEFAULT current_timestamp NOT NULL,
    update_by           NUMBER(19),
    deleted             NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id           NUMBER(19)          DEFAULT 0 NOT NULL,
    version             NUMBER(19)          DEFAULT 0 NOT NULL,
    rule_id             NUMBER(19),
    script_id           NUMBER(19),
    device_id           NUMBER(19),
    idempotent_key      VARCHAR2(128 CHAR)    not null,
    status              VARCHAR2(20 CHAR)     DEFAULT 'PENDING' NOT NULL,
    process_instance_id VARCHAR2(64 CHAR),
    form_snapshot       CLOB,
    error               VARCHAR2(500 CHAR),
    trigger_time        timestamp       not null
);
create unique index uk_sw_iot_trigger_key on sw_iot_process_trigger (tenant_id, idempotent_key);

-- 11. 遗留腾讯身份列放宽
alter table sw_iot_device modify (product_id null);
alter table sw_iot_device modify (device_name null);

-- 10. 统一命令（Provider 无关；区分 Broker 接收 / 设备回复 / 业务执行）
create table sw_iot_command (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    device_id       NUMBER(19)          not null,
    conn_id         NUMBER(19),
    provider        VARCHAR2(20 CHAR)     DEFAULT 'MQTT' NOT NULL,
    capability_type VARCHAR2(20 CHAR)     not null,
    capability_id   VARCHAR2(64 CHAR),
    params_json     CLOB,
    status          VARCHAR2(20 CHAR)     DEFAULT 'PENDING' NOT NULL,
    result_json     CLOB,
    error           VARCHAR2(500 CHAR),
    idempotent_key  VARCHAR2(128 CHAR),
    source_type     VARCHAR2(20 CHAR)     DEFAULT 'MANUAL' NOT NULL,
    source_ref      VARCHAR2(128 CHAR),
    flow_instance_id VARCHAR2(64 CHAR),
    flow_task_id    VARCHAR2(64 CHAR),
    qos             NUMBER(10)             DEFAULT 1 NOT NULL,
    sent_time       timestamp,
    reply_time      timestamp,
    timeout_ms      NUMBER(10)             DEFAULT 30000 NOT NULL
);
create index idx_sw_iot_cmd_dev on sw_iot_command (tenant_id, device_id, create_time);
create index idx_sw_iot_cmd_flow on sw_iot_command (tenant_id, flow_instance_id);

-- ======== V60（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V60: P21 IoT 接入 — 流程模板 IoT 接入开关（PostgreSQL）
--
-- 变更：sw_bpm_process_def 新增 iot_access_enabled（默认 0）。
-- 仅「已发布 + iot_access_enabled」的流程模板允许被 IoT 事件规则/受控脚本
-- 发起流程；未开启的模板在规则配置与触发时均被明确拒绝。
-- 幂等：IF NOT EXISTS。
-- ===================================================================

alter table sw_bpm_process_def add iot_access_enabled NUMBER(1) DEFAULT 0 NOT NULL;

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
SELECT 330, current_timestamp, current_timestamp, 0, 0, 8, 'IotConnectionList', '连接配置', 0, 1,
       'iot/connections', 'iot/views/IotConnectionList', 'iot:connection:manage', 'Link', 61
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 330);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 331, current_timestamp, current_timestamp, 0, 0, 8, 'IotProductList', '产品物模型', 0, 1,
       'iot/products', 'iot/views/IotProductList', 'iot:product:manage', 'Goods', 62
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 331);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 332, current_timestamp, current_timestamp, 0, 0, 8, 'IotDeviceList', '设备管理', 0, 1,
       'iot/devices-manage', 'iot/views/IotDeviceList', 'iot:device:manage', 'Monitor', 63
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 332);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 333, current_timestamp, current_timestamp, 0, 0, 8, 'IotTopicList', 'Topic 配置', 0, 1,
       'iot/topics', 'iot/views/IotTopicList', 'iot:topic:manage', 'ChatLineSquare', 64
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 333);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 334, current_timestamp, current_timestamp, 0, 0, 8, 'IotScriptList', '受控脚本', 0, 1,
       'iot/scripts', 'iot/views/IotScriptList', 'iot:script:manage', 'EditPen', 65
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 334);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 335, current_timestamp, current_timestamp, 0, 0, 8, 'IotRuleList', '事件规则', 0, 1,
       'iot/rules', 'iot/views/IotRuleList', 'iot:rule:manage', 'Bell', 66
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 335);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 336, current_timestamp, current_timestamp, 0, 0, 8, 'IotRuntimeLogs', '运行记录', 0, 1,
       'iot/runtime', 'iot/views/IotRuntimeLogs', 'iot:runtime:view', 'DataLine', 67
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 336);

-- ======== V62（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- ===================================================================
-- V62: P21 IoT 接入 — 流程模板 IoT 接入开关（bpm 模块链，PostgreSQL）
-- 与主库 V60 同构幂等：bpm 隔离链（如 p4overlap 测试库）亦能获得该列。
-- ===================================================================

-- Oracle duplicate add skipped: alter table sw_bpm_process_def add iot_access_enabled NUMBER(1) DEFAULT 0 NOT NULL;

-- ======== V63（来源: sw-biz/sw-bpm/sw-bpm-process,sw-bootstrap，副本 2 处已去重） ========
-- ===================================================================
-- V63: P21 A6 — 流程模板设备动作配置（PostgreSQL/H2 通用幂等）
-- iot_device_action_json: {"enabled","deviceSource":"FIXED|FORM_FIELD|VARIABLE",
--   "deviceId","deviceField","variableName","commandKey","paramField","failurePolicy"}
-- ===================================================================
alter table sw_bpm_process_def add iot_device_action_json CLOB;

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
SELECT 337, current_timestamp, current_timestamp, 0, 0, 8, 'IotFlowActions', '流程设备动作', 0, 1,
       'iot/flow-actions', 'iot/views/IotFlowActions', 'workflow:def:publish', 'Setting', 68
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 337);

-- ======== V66（来源: sw-basic/sw-basic-iot，副本 1 处已去重） ========
-- P21 修正：统一命令端到端关联标识与 IoT 行为审计。
alter table sw_iot_command add correlation_id VARCHAR2(128 CHAR);
alter table sw_iot_event_record add correlation_id VARCHAR2(128 CHAR);
alter table sw_iot_script_exec add correlation_id VARCHAR2(128 CHAR);

create table sw_iot_audit_record (
    id               NUMBER(19)          not null primary key,
    create_time      timestamp       DEFAULT current_timestamp NOT NULL,
    create_by        NUMBER(19),
    update_time      timestamp       DEFAULT current_timestamp NOT NULL,
    update_by        NUMBER(19),
    deleted          NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id        NUMBER(19)          DEFAULT 0 NOT NULL,
    version          NUMBER(19)          DEFAULT 0 NOT NULL,
    action           VARCHAR2(64 CHAR)     not null,
    object_type      VARCHAR2(64 CHAR)     not null,
    object_id        VARCHAR2(128 CHAR)    not null,
    result           VARCHAR2(32 CHAR)     not null,
    actor_id         NUMBER(19)          not null,
    system_identity  VARCHAR2(128 CHAR)    not null,
    action_time      timestamp       DEFAULT current_timestamp NOT NULL,
    correlation_id   VARCHAR2(128 CHAR)    not null,
    detail           VARCHAR2(1000 CHAR)
);
create index idx_sw_iot_audit_lookup
    on sw_iot_audit_record (tenant_id, action, object_type, object_id, action_time);
create index idx_sw_iot_audit_correlation
    on sw_iot_audit_record (tenant_id, correlation_id);
create index idx_sw_iot_event_correlation
    on sw_iot_event_record (tenant_id, correlation_id);
create index idx_sw_iot_exec_correlation
    on sw_iot_script_exec (tenant_id, correlation_id);

-- ======== V67（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V67: P60 I1 组织与权限底座 — PostgreSQL/H2 通用方言
--
-- 内容：
--   1. sys_user_post 增加任职部门 dept_id（岗位在部门内承担，方向 §3.1）：
--      DEFAULT 0 起步，再按用户主部门回填，0 仅保留给 NOT NULL
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
ALTER TABLE sys_user_post add dept_id NUMBER(19) DEFAULT 0 NOT NULL;

UPDATE sys_user_post
SET dept_id = COALESCE(
    (SELECT u.dept_id FROM sys_user u
      WHERE u.id = sys_user_post.user_id AND u.deleted = 0),
    0)
WHERE dept_id = 0;

CREATE INDEX idx_sys_user_post_dept ON sys_user_post (tenant_id, dept_id, deleted);

-- -------------------- 2. 参与人展示名快照 --------------------
ALTER TABLE sw_bpm_participant_snapshot add participant_name VARCHAR2(100 CHAR);

-- -------------------- 3. 部门管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 340, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptCreate', '部门新增', 0, 2, '', '', 'system:dept:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 341, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptUpdate', '部门修改', 0, 2, '', '', 'system:dept:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 342, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptDelete', '部门删除', 0, 2, '', '', 'system:dept:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:delete' AND m.deleted = 0);

-- -------------------- 4. 岗位管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 343, current_timestamp, current_timestamp, 0, 0, p.id, 'PostCreate', '岗位新增', 0, 2, '', '', 'system:post:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 344, current_timestamp, current_timestamp, 0, 0, p.id, 'PostUpdate', '岗位修改', 0, 2, '', '', 'system:post:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 345, current_timestamp, current_timestamp, 0, 0, p.id, 'PostDelete', '岗位删除', 0, 2, '', '', 'system:post:delete', '', 13
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
SELECT 350, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataSubmit', '表单填报提交', 0, 2,
       '', '', 'form:data:submit', '', 11
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 350);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 351, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataEdit', '记录编辑', 0, 2,
       '', '', 'form:data:edit', '', 12
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 351);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 352, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataDelete', '记录删除', 0, 2,
       '', '', 'form:data:delete', '', 13
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 352);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 353, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataQuery', '记录查询', 0, 2,
       '', '', 'form:data:query', '', 14
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 353);

-- ======== V69（来源: sw-biz/sw-biz-form/sw-biz-form-biz，副本 1 处已去重） ========
-- ===================================================================
-- P60 I2 低代码表单收口 :: 列表配置 / 外部数据源查询契约 / 生命周期审计
-- ===================================================================

-- ==================== 1. 表单列表展示配置 ====================
CREATE TABLE sw_form_list_config (
    id           VARCHAR2(36 CHAR)  PRIMARY KEY,
    form_id      VARCHAR2(36 CHAR)  NOT NULL UNIQUE,
    config_json  CLOB         NOT NULL,
    tenant_id    NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted      NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by    NUMBER(19),
    update_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by    NUMBER(19),
    version      NUMBER(19)       DEFAULT 0 NOT NULL
);

-- ==================== 2. 受控外部数据源查询契约（版本化） ====================
CREATE TABLE sw_form_ext_query (
    id             VARCHAR2(36 CHAR)  PRIMARY KEY,
    datasource_id  NUMBER(19)       NOT NULL,
    query_key      VARCHAR2(64 CHAR)  NOT NULL,
    query_version  NUMBER(10)          DEFAULT 1 NOT NULL,
    sql_text       CLOB         NOT NULL,
    output_fields  CLOB         NOT NULL,
    enabled        NUMBER(5)     DEFAULT 1 NOT NULL,
    tenant_id      NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted        NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by      NUMBER(19),
    update_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by      NUMBER(19),
    version        NUMBER(19)       DEFAULT 0 NOT NULL,
    CONSTRAINT uk_sw_form_ext_query UNIQUE (tenant_id, query_key, query_version)
);

-- ==================== 3. 表单生命周期审计 ====================
CREATE TABLE sw_form_lifecycle_audit (
    id           VARCHAR2(36 CHAR)  PRIMARY KEY,
    form_id      VARCHAR2(36 CHAR)  NOT NULL,
    action       VARCHAR2(20 CHAR)  NOT NULL,
    reason       VARCHAR2(500 CHAR),
    operator_id  NUMBER(19),
    tenant_id    NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted      NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by    NUMBER(19),
    update_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by    NUMBER(19),
    version      NUMBER(19)       DEFAULT 0 NOT NULL
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

create table sw_bpm_process_def_version (
    id                    NUMBER(19)          not null primary key,
    create_time           timestamp       DEFAULT current_timestamp NOT NULL,
    create_by             NUMBER(19),
    update_time           timestamp       DEFAULT current_timestamp NOT NULL,
    update_by             NUMBER(19),
    deleted               NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id             NUMBER(19)          DEFAULT 0 NOT NULL,
    version               NUMBER(19)          DEFAULT 0 NOT NULL,
    def_id                NUMBER(19)          not null,
    graph_version         NUMBER(10)             not null,
    status                VARCHAR2(20 CHAR)     DEFAULT 'PUBLISHED' NOT NULL,
    name                  VARCHAR2(200 CHAR)    not null,
    form_key              VARCHAR2(200 CHAR),
    form_version          VARCHAR2(64 CHAR),
    function_versions     CLOB,
    graph_json            CLOB,
    deployment_id         VARCHAR2(64 CHAR),
    process_definition_id VARCHAR2(64 CHAR),
    published_by          NUMBER(19),
    published_at          timestamp
);

create UNIQUE index uk_sw_bpm_def_version
    ON sw_bpm_process_def_version (tenant_id, def_id, graph_version);
create index idx_sw_bpm_def_version_status
    ON sw_bpm_process_def_version (tenant_id, def_id, status);

ALTER TABLE sw_bpm_process_def add published_version NUMBER(10);
ALTER TABLE sw_bpm_instance add def_version NUMBER(10);

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
--        detail             动作说明 CLOB（取消原因、顺序、附件等）
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

ALTER TABLE sw_bpm_approval_action add target_user_id NUMBER(19);
ALTER TABLE sw_bpm_approval_action add proxy_for_user_id NUMBER(19);
ALTER TABLE sw_bpm_approval_action add round_no NUMBER(10);
ALTER TABLE sw_bpm_approval_action add related_task_id VARCHAR2(64 CHAR);
ALTER TABLE sw_bpm_approval_action add detail CLOB;

create table sw_bpm_authorize_rule (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    principal_id      NUMBER(19)          not null,
    agent_id          NUMBER(19)          not null,
    scope_type        VARCHAR2(20 CHAR)     DEFAULT 'GLOBAL' NOT NULL,
    process_def_key   VARCHAR2(200 CHAR),
    node_key          VARCHAR2(200 CHAR),
    business_key      VARCHAR2(200 CHAR),
    condition_json    CLOB,
    start_at          timestamp,
    end_at            timestamp,
    status            VARCHAR2(20 CHAR)     DEFAULT 'ACTIVE' NOT NULL,
    detail            VARCHAR2(500 CHAR)
);

create index idx_sw_bpm_authorize_agent
    ON sw_bpm_authorize_rule (tenant_id, agent_id, status);

create table sw_bpm_communication (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR)   not null,
    node_key          VARCHAR2(200 CHAR)    not null,
    task_id           VARCHAR2(64 CHAR),
    round_no          NUMBER(10)             DEFAULT 1 NOT NULL,
    initiator_id      NUMBER(19)          not null,
    receiver_id       NUMBER(19)          not null,
    message           CLOB,
    reply_message     CLOB,
    status            VARCHAR2(20 CHAR)     DEFAULT 'PENDING' NOT NULL,
    reply_time        timestamp
);

create index idx_sw_bpm_comm_task
    ON sw_bpm_communication (tenant_id, task_id);
create UNIQUE index uk_sw_bpm_comm_instance_receiver
    ON sw_bpm_communication (tenant_id, process_instance_id, task_id, receiver_id);

create table sw_bpm_sign_record (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR)   not null,
    node_key          VARCHAR2(200 CHAR)    not null,
    task_id           VARCHAR2(64 CHAR)     not null,
    sign_type         VARCHAR2(20 CHAR)     not null,
    mode_type         VARCHAR2(10 CHAR)     DEFAULT 'SERIAL' NOT NULL,
    operator_id       NUMBER(19)          not null,
    participant_id    NUMBER(19)          not null,
    seq_no            NUMBER(10)             DEFAULT 0 NOT NULL,
    sign_status       VARCHAR2(20 CHAR)     DEFAULT 'PENDING' NOT NULL,
    result_status     VARCHAR2(20 CHAR),
    cancel_reason     VARCHAR2(500 CHAR),
    original_task_id  VARCHAR2(64 CHAR),
    original_status   VARCHAR2(30 CHAR),
    detail            CLOB
);

create index idx_sw_bpm_sign_task
    ON sw_bpm_sign_record (tenant_id, task_id);

create table sw_bpm_consensus_vote (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR)   not null,
    node_key          VARCHAR2(200 CHAR)    not null,
    task_id           VARCHAR2(64 CHAR)     not null,
    actor_id          NUMBER(19)          not null,
    outcome           VARCHAR2(20 CHAR)     not null
);

create UNIQUE index uk_sw_bpm_vote_task_actor
    ON sw_bpm_consensus_vote (tenant_id, task_id, actor_id);
create index idx_sw_bpm_vote_node
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

ALTER TABLE sw_bpm_approval_action add opinion_form_snapshot CLOB;

create table sw_bpm_task_deadline (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR)   not null,
    node_key          VARCHAR2(200 CHAR)    not null,
    task_id           VARCHAR2(64 CHAR)     not null,
    due_at            timestamp       not null,
    remind_fired      NUMBER(5)        DEFAULT 0 NOT NULL,
    escalation_fired  NUMBER(5)        DEFAULT 0 NOT NULL,
    auto_action       VARCHAR2(20 CHAR),
    auto_action_config CLOB,
    run_state         VARCHAR2(20 CHAR)     DEFAULT 'PENDING' NOT NULL,
    result_status     VARCHAR2(20 CHAR),
    last_reason       VARCHAR2(500 CHAR),
    handled_at        timestamp
);

create UNIQUE index uk_sw_bpm_deadline_task
    ON sw_bpm_task_deadline (tenant_id, task_id);
create index idx_sw_bpm_deadline_due
    ON sw_bpm_task_deadline (run_state, due_at);

create table sw_bpm_node_function (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    func_key          VARCHAR2(128 CHAR)    not null,
    func_version      NUMBER(10)             not null,
    func_type         VARCHAR2(32 CHAR)     not null,
    impl_bean         VARCHAR2(128 CHAR)    not null,
    config            CLOB,
    allowed_nodes     CLOB,
    timeout_ms        NUMBER(19)          DEFAULT 5000 NOT NULL,
    failure_strategy  VARCHAR2(20 CHAR)     DEFAULT 'BLOCK' NOT NULL,
    enabled           NUMBER(5)        DEFAULT 1 NOT NULL
);

create UNIQUE index uk_sw_bpm_node_func
    ON sw_bpm_node_function (tenant_id, func_key, func_version);


-- I3 内建节点函数注册（func_tenant_admins / func_audit_trail v1；全局）
INSERT INTO sw_bpm_node_function (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes, timeout_ms, failure_strategy, enabled) SELECT 9001,'func_tenant_admins',1,'RESOLVE_PARTICIPANTS','func_tenant_admins','{"input":"context","output":"List<userId>","maxItems":100}','[]',5000,'FRAMEWORK',1  from dual WHERE NOT EXISTS (SELECT 1 FROM sw_bpm_node_function WHERE id = 9001);
INSERT INTO sw_bpm_node_function (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes, timeout_ms, failure_strategy, enabled) SELECT 9002,'func_audit_trail',1,'HANDLE_RESULT','func_audit_trail','{"input":"nodeResult","output":"summaryResultVariables"}','[]',5000,'FRAMEWORK',1  from dual WHERE NOT EXISTS (SELECT 1 FROM sw_bpm_node_function WHERE id = 9002);

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
SELECT 354, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDesigner', '流程设计器页', 0, 2,
       '', '', 'workflow:def:design', '', 21
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 354);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 355, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDefValidate', '流程草稿校验', 0, 2,
       '', '', 'workflow:def:validate', '', 22
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 355);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 356, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDefSuspend', '发布版本挂起/激活', 0, 2,
       '', '', 'workflow:def:suspend', '', 23
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 356);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 357, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskTransfer', '任务转办', 0, 2,
       '', '', 'workflow:task:transfer', '', 31
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 357);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 358, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskDelegate', '任务委托', 0, 2,
       '', '', 'workflow:task:delegate', '', 32
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 358);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 359, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskAuthorize', '授权代理规则', 0, 2,
       '', '', 'workflow:task:authorize', '', 33
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 359);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 360, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskAddSign', '任务加签/补签', 0, 2,
       '', '', 'workflow:task:add-sign', '', 34
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 360);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 361, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskWithdraw', '发起人撤回', 0, 2,
       '', '', 'workflow:task:withdraw', '', 35
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 361);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 362, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskCommunicate', '沟通征询', 0, 2,
       '', '', 'workflow:task:communicate', '', 36
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 362);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 363, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskDiscard', '实例废弃', 0, 2,
       '', '', 'workflow:task:discard', '', 37
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 363);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 364, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskManage', '时限与自动动作管理', 0, 2,
       '', '', 'workflow:task:manage', '', 38
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 364);

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
drop index uk_sw_bpm_approval_task_actor;
create unique index uk_sw_bpm_approval_task_actor_action
    on sw_bpm_approval_action (tenant_id, task_id, actor_id, action);

-- ======== V75（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V75 (I3 G13a/G13b): 节点函数调用审计（§4.9 运行必须完整审计）。
-- 每次参与人解析/结果处理调用（成功或失败）各写一行：函数身份、调用位置、
-- 结果或错误码、幂等键与耗时；生产任意脚本入口仍不存在。
create table sw_bpm_node_function_audit (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    func_key VARCHAR2(128 CHAR) not null,
    func_version NUMBER(10) not null,
    func_type VARCHAR2(32 CHAR) not null,
    process_instance_id VARCHAR2(64 CHAR),
    node_key VARCHAR2(128 CHAR),
    idempotent_key VARCHAR2(128 CHAR),
    actor_id NUMBER(19),
    outcome VARCHAR2(32 CHAR) not null,
    error_code NUMBER(10),
    summary CLOB,
    duration_ms NUMBER(19)
);
create index idx_sw_bpm_fn_audit_instance on sw_bpm_node_function_audit (process_instance_id);

-- 内建结果函数注册：func_result_echo v1（白名单变量写回证据链）
INSERT INTO sw_bpm_node_function (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes, timeout_ms, failure_strategy, enabled) SELECT 9003,'func_result_echo',1,'HANDLE_RESULT','func_result_echo','{"input":"nodeResult","output":"summaryResultVariables"}','[]',5000,'FRAMEWORK',1  from dual WHERE NOT EXISTS (SELECT 1 FROM sw_bpm_node_function WHERE id = 9003);

-- ======== V76（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V76 (I4 §3.1): 动态并行分支冻结快照。
-- 进入节点时冻结来源部门、负责人与汇聚策略；运行中表单或组织变化不改写本表。
-- 无效对象（失效部门/负责人缺失/跨租户）落 CANCELED 行并记录原因，不静默跳过。
create table sw_bpm_dynamic_branch (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null,
    node_key VARCHAR2(128 CHAR) not null,
    branch_index NUMBER(10) not null,
    source_type VARCHAR2(32 CHAR),
    source_value VARCHAR2(512 CHAR),
    dept_ids VARCHAR2(512 CHAR),
    leader_id NUMBER(19),
    converge_mode VARCHAR2(16 CHAR) not null,
    status VARCHAR2(20 CHAR) not null,
    task_id VARCHAR2(64 CHAR),
    cancel_reason VARCHAR2(512 CHAR)
);
create index idx_sw_bpm_dyn_branch_instance on sw_bpm_dynamic_branch (process_instance_id, node_key);
create unique index uk_sw_bpm_dyn_branch on sw_bpm_dynamic_branch (tenant_id, process_instance_id, node_key, branch_index);

-- ======== V77（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V77 (I4 §3.2): 流程模板中心。
-- 模板是创建流程定义的受控来源：不直接改写已发布定义、运行实例或历史；
-- 派生定义通过 sw_bpm_process_def.source_template_id/version 保持可追溯。
-- 分级授权：scope_type=GLOBAL 全局可见；DEPT 仅 scope_dept_id 同部门可见/可复制。
create table sw_bpm_process_template (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    name VARCHAR2(200 CHAR) not null,
    category VARCHAR2(100 CHAR),
    description VARCHAR2(500 CHAR),
    form_key VARCHAR2(200 CHAR) not null,
    graph_json CLOB,
    template_version NUMBER(10) DEFAULT 1 NOT NULL,
    status VARCHAR2(20 CHAR) DEFAULT 'ENABLED' NOT NULL,
    scope_type VARCHAR2(20 CHAR) DEFAULT 'GLOBAL' NOT NULL,
    scope_dept_id NUMBER(19),
    source_def_id NUMBER(19),
    source_def_version NUMBER(10)
);
create index idx_sw_bpm_tpl_category on sw_bpm_process_template (category);
create index idx_sw_bpm_tpl_scope on sw_bpm_process_template (scope_type, scope_dept_id);

alter table sw_bpm_process_def add source_template_id NUMBER(19);
alter table sw_bpm_process_def add source_template_version NUMBER(10);

-- ======== V78（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V78 (I4 §3.3): 实例级运营干预审计。挂起/恢复/终止/迁移办理人逐项授权并记录
-- 操作者、原因、对象、前后状态与受影响任务；与定义级挂起/激活互不混同。
create table sw_bpm_instance_intervention (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null,
    action VARCHAR2(20 CHAR) not null,
    operator_id NUMBER(19) not null,
    reason VARCHAR2(500 CHAR),
    before_state VARCHAR2(20 CHAR),
    after_state VARCHAR2(20 CHAR),
    from_assignee NUMBER(19),
    to_assignee NUMBER(19),
    affected_tasks NUMBER(10),
    intervened_at timestamp not null
);
create index idx_sw_bpm_itv_instance on sw_bpm_instance_intervention (process_instance_id);

-- ======== V79（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V79 (I4 §3.6): 流程交接批次与逐项清单。
-- 只迁移选定范围内尚未完成的可办理任务；代理规则显式勾选且通过校验才随迁；
-- 抄送/已办/历史意见/已过期规则不迁移；历史办理人与意见零改写。
create table sw_bpm_handover (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    from_user_id NUMBER(19) not null,
    to_user_id NUMBER(19) not null,
    scope_def_keys VARCHAR2(1000 CHAR),
    include_proxy_rules NUMBER(1) DEFAULT 0 NOT NULL,
    status VARCHAR2(20 CHAR) not null,
    total_items NUMBER(10),
    migrated_items NUMBER(10),
    failed_items NUMBER(10),
    operator_id NUMBER(19) not null,
    executed_at timestamp not null
);
create index idx_sw_bpm_handover_users on sw_bpm_handover (from_user_id, to_user_id);

create table sw_bpm_handover_item (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    handover_id NUMBER(19) not null,
    task_id VARCHAR2(64 CHAR),
    process_instance_id VARCHAR2(64 CHAR),
    item_type VARCHAR2(20 CHAR) not null,
    before_assignee NUMBER(19),
    after_assignee NUMBER(19),
    result VARCHAR2(30 CHAR) not null,
    fail_reason VARCHAR2(500 CHAR),
    rule_id NUMBER(19)
);
create index idx_sw_bpm_handover_item on sw_bpm_handover_item (handover_id);

-- ======== V80（来源: sw-biz/sw-biz-openapi/sw-biz-openapi-biz，副本 1 处已去重） ========
-- V1 (I4 §3.4): 开放接口底座 —— 应用身份（绑定租户/授权范围/代理用户）、
-- 防重放 nonce、幂等键登记、出站回调日志。
create table sw_openapi_app (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    app_id VARCHAR2(64 CHAR) not null,
    app_name VARCHAR2(200 CHAR) not null,
    secret_hash VARCHAR2(128 CHAR) not null,
    scopes VARCHAR2(200 CHAR) not null,
    status VARCHAR2(20 CHAR) DEFAULT 'ENABLED' NOT NULL,
    act_as_user_id NUMBER(19) not null,
    callback_url VARCHAR2(500 CHAR),
    callback_secret_hash VARCHAR2(128 CHAR)
);
create unique index uk_sw_openapi_app on sw_openapi_app (app_id);

create table sw_openapi_nonce (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    app_id VARCHAR2(64 CHAR) not null,
    nonce VARCHAR2(128 CHAR) not null,
    expire_at timestamp not null
);
create unique index uk_sw_openapi_nonce on sw_openapi_nonce (app_id, nonce);

create table sw_openapi_idempotency (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    app_id VARCHAR2(64 CHAR) not null,
    idem_key VARCHAR2(200 CHAR) not null,
    result_ref VARCHAR2(64 CHAR)
);
create unique index uk_sw_openapi_idem on sw_openapi_idempotency (app_id, idem_key);

create table sw_openapi_callback_log (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    app_id VARCHAR2(64 CHAR) not null,
    event VARCHAR2(50 CHAR) not null,
    biz_ref VARCHAR2(64 CHAR) not null,
    url VARCHAR2(500 CHAR),
    attempt NUMBER(10) not null,
    status VARCHAR2(20 CHAR) not null,
    response_summary VARCHAR2(500 CHAR),
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
 from dual where not exists (select 1 from sw_openapi_app where app_id = 'i4-demo-app');

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
select 365, current_timestamp, current_timestamp, 0, 0, 7, 'BatchApproval', '批量审批', 0, 1,
       'workflow/batch-approval', 'workflow/views/BatchApproval', 'workflow:task:batch', 'Checked', 33
 from dual where not exists (select 1 from sys_menu where id = 365);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 366, current_timestamp, current_timestamp, 0, 0, 7, 'TaskHandover', '流程交接', 0, 1,
       'workflow/handover', 'workflow/views/TaskHandover', 'workflow:handover:manage', 'Position', 34
 from dual where not exists (select 1 from sys_menu where id = 366);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 367, current_timestamp, current_timestamp, 0, 0, 7, 'ProcessAnalytics', '流程分析', 0, 1,
       'workflow/analytics', 'workflow/views/ProcessAnalytics', 'workflow:monitor:view', 'TrendCharts', 35
 from dual where not exists (select 1 from sys_menu where id = 367);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 368, current_timestamp, current_timestamp, 0, 0, 7, 'InstanceMonitor', '实例监控干预', 0, 1,
       'workflow/monitor', 'workflow/views/InstanceMonitor', 'workflow:monitor:view', 'Monitor', 31
 from dual where not exists (select 1 from sys_menu where id = 368);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 369, current_timestamp, current_timestamp, 0, 0, 368, 'InstanceMonitorManage', '监控干预操作', 0, 2,
       '', '', 'workflow:monitor:manage', '', 1
 from dual where not exists (select 1 from sys_menu where id = 369);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 370, current_timestamp, current_timestamp, 0, 0, 7, 'TemplateCenter', '流程模板中心', 0, 1,
       'workflow/templates', 'workflow/views/TemplateCenter', 'workflow:template:list', 'Files', 32
 from dual where not exists (select 1 from sys_menu where id = 370);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 371, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateView', '模板查看', 0, 2,
       '', '', 'workflow:template:view', '', 1
 from dual where not exists (select 1 from sys_menu where id = 371);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 372, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateCreate', '模板新建', 0, 2,
       '', '', 'workflow:template:create', '', 2
 from dual where not exists (select 1 from sys_menu where id = 372);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 373, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateSave', '模板编辑', 0, 2,
       '', '', 'workflow:template:save', '', 3
 from dual where not exists (select 1 from sys_menu where id = 373);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 374, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateCopy', '模板复制', 0, 2,
       '', '', 'workflow:template:copy', '', 4
 from dual where not exists (select 1 from sys_menu where id = 374);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 375, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateDelete', '模板删除', 0, 2,
       '', '', 'workflow:template:delete', '', 5
 from dual where not exists (select 1 from sys_menu where id = 375);

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

drop index uk_sw_form_def_form_key;
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
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    provider        VARCHAR2(20 CHAR)     not null,
    enabled         NUMBER(5)        DEFAULT 0 NOT NULL,
    app_id          VARCHAR2(200 CHAR)    not null,
    app_secret_enc  VARCHAR2(1000 CHAR)   not null,
    extra_config    CLOB,
    redirect_path   VARCHAR2(500 CHAR)
);
create unique index uk_sys_sso_provider_config
    on sys_sso_provider_config (tenant_id, provider, deleted);

create table sys_sso_user_binding (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    provider        VARCHAR2(20 CHAR)     not null,
    external_id     VARCHAR2(200 CHAR)    not null,
    external_digest VARCHAR2(64 CHAR)     not null,
    user_id         NUMBER(19)          not null,
    bind_status     VARCHAR2(20 CHAR)     DEFAULT 'ACTIVE' NOT NULL
);
create unique index uk_sys_sso_binding_external
    on sys_sso_user_binding (provider, tenant_id, external_id, deleted);
create unique index uk_sys_sso_binding_user
    on sys_sso_user_binding (provider, tenant_id, user_id, deleted);
create index idx_sys_sso_binding_user_all on sys_sso_user_binding (user_id);

create table sys_sso_auth_state (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    state_value     VARCHAR2(128 CHAR)    not null,
    provider        VARCHAR2(20 CHAR)     not null,
    nonce           VARCHAR2(128 CHAR),
    redirect_path   VARCHAR2(500 CHAR),
    consumed        NUMBER(5)        DEFAULT 0 NOT NULL,
    expire_at       timestamp       not null
);
create unique index uk_sys_sso_state_value on sys_sso_auth_state (state_value);
create index idx_sys_sso_state_expire on sys_sso_auth_state (expire_at);

create table sys_sso_audit_record (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    provider        VARCHAR2(20 CHAR)     not null,
    event_type      VARCHAR2(40 CHAR)     not null,
    result          VARCHAR2(20 CHAR)     not null,
    actor_id        NUMBER(19),
    local_user_id   NUMBER(19),
    external_digest VARCHAR2(64 CHAR),
    detail          VARCHAR2(1000 CHAR)
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
select 0, current_timestamp, current_timestamp, 0, 0, 0,
       '默认租户', 'tenant-0', 0, 'I5 收口：系统默认租户（种子账号所属租户）'
from dual
where not exists (select 1 from sys_tenant where id = 0);

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
alter table sw_notify_template add event_type VARCHAR2(40 CHAR) DEFAULT 'SYSTEM' NOT NULL;
alter table sw_notify_template add channel VARCHAR2(40 CHAR) DEFAULT 'IN_APP' NOT NULL;
alter table sw_notify_template add variables_allowed VARCHAR2(1000 CHAR);
alter table sw_notify_template add jump_ref VARCHAR2(200 CHAR);

-- ---------- 2. 模板版本追加（发布后不可改写） ----------
create table sw_notify_template_version (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    template_id       NUMBER(19)          not null,
    template_version  NUMBER(10)             DEFAULT 1 NOT NULL,
    event_type        VARCHAR2(40 CHAR)     DEFAULT 'SYSTEM' NOT NULL,
    channel           VARCHAR2(40 CHAR)     DEFAULT 'IN_APP' NOT NULL,
    title_template    VARCHAR2(200 CHAR)    not null,
    content_template  CLOB            not null,
    variables_allowed VARCHAR2(1000 CHAR),
    jump_ref          VARCHAR2(200 CHAR),
    status            VARCHAR2(20 CHAR)     DEFAULT 'RELEASED' NOT NULL
);
create unique index uk_sw_notify_tpl_ver on sw_notify_template_version (tenant_id, template_id, template_version);
create index idx_sw_notify_tpl_ver_template on sw_notify_template_version (template_id);

-- ---------- 3. 通知规则 ----------
create table sw_notify_rule (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    rule_code         VARCHAR2(100 CHAR)    not null,
    name              VARCHAR2(100 CHAR)    not null,
    event_type        VARCHAR2(40 CHAR)     not null,
    channel_priority  VARCHAR2(200 CHAR)    DEFAULT 'IN_APP' NOT NULL,
    recipient_rule    VARCHAR2(500 CHAR)    not null,
    required_flag     NUMBER(5)        DEFAULT 0 NOT NULL,
    failure_policy    VARCHAR2(20 CHAR)     DEFAULT 'RETRY' NOT NULL,
    enabled           NUMBER(5)        DEFAULT 1 NOT NULL,
    remark            VARCHAR2(500 CHAR)
);
create unique index uk_sw_notify_rule_code on sw_notify_rule (tenant_id, rule_code, deleted);
create index idx_sw_notify_rule_event on sw_notify_rule (tenant_id, event_type);

-- ---------- 4. 用户订阅（可选偏好） ----------
create table sw_notify_subscription (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    user_id           NUMBER(19)          not null,
    event_type        VARCHAR2(40 CHAR)     not null,
    channel           VARCHAR2(40 CHAR)     DEFAULT 'IN_APP' NOT NULL,
    enabled           NUMBER(5)        DEFAULT 1 NOT NULL
);
create unique index uk_sw_notify_sub on sw_notify_subscription (tenant_id, user_id, event_type, channel, deleted);

-- ---------- 5. 租户级渠道启停（秘密不入库） ----------
create table sw_notify_channel_config (
    id                NUMBER(19)          not null primary key,
    create_time       timestamp       DEFAULT current_timestamp NOT NULL,
    create_by         NUMBER(19),
    update_time       timestamp       DEFAULT current_timestamp NOT NULL,
    update_by         NUMBER(19),
    deleted           NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id         NUMBER(19)          DEFAULT 0 NOT NULL,
    version           NUMBER(19)          DEFAULT 0 NOT NULL,
    channel           VARCHAR2(40 CHAR)     not null,
    enabled           NUMBER(5)        DEFAULT 0 NOT NULL,
    sender_display    VARCHAR2(200 CHAR),
    config_summary    VARCHAR2(500 CHAR)
);
create unique index uk_sw_notify_channel on sw_notify_channel_config (tenant_id, channel, deleted);

-- ---------- 6. 消息行统一业务身份与投递状态扩展 ----------
alter table sw_notify_message add event_type VARCHAR2(40 CHAR) DEFAULT 'SYSTEM' NOT NULL;
alter table sw_notify_message add occurrence_no NUMBER(19) DEFAULT 1 NOT NULL;
alter table sw_notify_message add template_id NUMBER(19);
alter table sw_notify_message add template_version NUMBER(10);
alter table sw_notify_message add link_type VARCHAR2(32 CHAR);
alter table sw_notify_message add link_id VARCHAR2(64 CHAR);
alter table sw_notify_message add retry_count NUMBER(10) DEFAULT 0 NOT NULL;
alter table sw_notify_message add next_retry_time timestamp;
alter table sw_notify_message add failure_class VARCHAR2(40 CHAR);
alter table sw_notify_message add receipt_digest VARCHAR2(200 CHAR);

-- 一次业务通知稳定身份：租户+事件类型+业务对象+发生次序+接收人+渠道
-- （biz_id 为空的系统通告不受唯一键约束，保持可重复广播语义）
create unique index uk_sw_notify_msg_identity on sw_notify_message (tenant_id, event_type, biz_id, occurrence_no, recipient_id, channel);
create index idx_sw_notify_msg_retry on sw_notify_message (tenant_id, event_type, biz_id);

-- ---------- 7. 尝试流水扩展 ----------
alter table sw_notify_send_attempt add failure_class VARCHAR2(40 CHAR);
alter table sw_notify_send_attempt add started_at timestamp;
alter table sw_notify_send_attempt add finished_at timestamp;

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
SELECT 218, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyRule', '通知规则', 0, 1, 'rule', 'notify/views/NotifyRuleList', 'notify:rule:view', 'Setting', 30
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 218);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 219, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyChannel', '渠道配置', 0, 1, 'channel', 'notify/views/NotifyChannelList', 'notify:channel:view', 'Link', 40
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 219);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 220, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyPreference', '订阅偏好', 0, 1, 'preference', 'notify/views/NotifyPreference', 'notify:preference', 'ToggleOn', 40
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 220);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 221, current_timestamp, current_timestamp, 0, 0, 218, 'NotifyRuleManage', '规则新建/编辑/启停/删除', 2, '', '', 'notify:rule:manage', '', 1, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 221);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 222, current_timestamp, current_timestamp, 0, 0, 219, 'NotifyChannelManage', '渠道启停/重试策略', 2, '', '', 'notify:channel:manage', '', 1, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 222);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 223, current_timestamp, current_timestamp, 0, 0, 0, 'NotifyRecordDetail', '记录必要详情（含正文与尝试流水，独立权限并审计）', 2, '', '', 'notify:record:detail', '', 2, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 223);

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
    id              NUMBER(19)       not null primary key,
    create_time     timestamp    DEFAULT current_timestamp NOT NULL,
    update_time     timestamp    DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_by       NUMBER(19),
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    version         NUMBER(19)       DEFAULT 0 NOT NULL,
    user_id         NUMBER(19)       not null,
    provider        VARCHAR2(40 CHAR)  not null,
    subject_cipher  VARCHAR2(500 CHAR) not null,
    subject_digest  VARCHAR2(128 CHAR) not null,
    bind_status     VARCHAR2(20 CHAR)  DEFAULT 'ACTIVE' NOT NULL
);

create unique index uk_sw_notify_subject_binding
    on sw_notify_subject_binding (tenant_id, user_id, provider, deleted);
create index idx_sw_notify_subject_provider
    on sw_notify_subject_binding (tenant_id, provider, deleted);

-- ======== V92（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V92 (I6): sw_notify 模板标志列统一为 NUMBER(1)（双方言同版本身份）
-- 修复真实 PostgreSQL 行为缺陷：V38 起模板 enabled 为 NUMBER(5)，
-- 而实体（MyBatis-Plus NUMBER(1) 字段）在 PG 写入 NUMBER(1) 触发类型错误。
-- H2 因宽松类型转换未在单库测试中暴露；G2e-T 真实 PG 发送链暴露。
-- 幂等：仅当列仍为 NUMBER(5) 时执行转换；默认值沿用 1→1 语义。
-- ===================================================================


-- ======== V93（来源: sw-basic/sw-basic-notify/sw-basic-notify-biz，副本 1 处已去重） ========
-- ===================================================================
-- V93 (I6): sw_notify 规则/订阅/渠道标志列统一为 NUMBER(1)（双方言同版本身份）
-- ===================================================================
-- V92 只覆盖 sw_notify_template.enabled。同一实体口径（MyBatis-Plus NUMBER(1)
-- 字段）在 sw_notify_rule / sw_notify_subscription / sw_notify_channel_config
-- 上仍为 NUMBER(5)，真实 PostgreSQL 对 NUMBER(5) 列与 NUMBER(1) 参数/值的比较和
-- 写入都直接报错（operator does not exist: NUMBER(5) = NUMBER(1) /
-- column "enabled" is of type NUMBER(5) but expression is of type NUMBER(1)），
-- 命中规则解析、规则启停、订阅偏好保存等生产路径。
-- H2 对 NUMBER(5) 与 NUMBER(1) 的比较做隐式转换，单库测试不会暴露该差异。
-- 幂等：仅当列仍为 NUMBER(5) 时转换；默认值按 1→1、0→0 语义保持。
-- ===================================================================


-- ======== V94（来源: sw-bootstrap，副本 1 处已去重） ========
-- V94: V011-BUG-002 — 租户级工作台卡片类型与低代码元数据契约
-- 卡片类型只保存安全 renderer_key；renderer_key 由 Web 注册表解析，不是可执行路径。

create table sys_workspace_card_type (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          DEFAULT 0 NOT NULL,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    type_code       VARCHAR2(64 CHAR)     not null,
    display_name    VARCHAR2(100 CHAR)    not null,
    renderer_key    VARCHAR2(64 CHAR)     not null,
    metadata_json   CLOB            DEFAULT '{}' NOT NULL,
    default_span    NUMBER(5)        DEFAULT 1 NOT NULL,
    default_order   NUMBER(10)         DEFAULT 1 NOT NULL,
    status          NUMBER(5)        DEFAULT 0 NOT NULL
);

create UNIQUE index uk_sys_workspace_card_type
    ON sys_workspace_card_type (tenant_id, type_code, deleted);

INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9401, current_timestamp, current_timestamp, 0, 0, 0, 'todo', '我的待办', 'todo', '{}', 1, 2, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'todo' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9402, current_timestamp, current_timestamp, 0, 0, 0, 'stats', '统计概览', 'stats', '{}', 2, 1, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'stats' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9403, current_timestamp, current_timestamp, 0, 0, 0, 'favoriteItems', '快捷发起', 'favorites', '{}', 1, 3, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'favoriteItems' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9404, current_timestamp, current_timestamp, 0, 0, 0, 'activity', '业务动态', 'activity', '{}', 1, 4, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'activity' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9405, current_timestamp, current_timestamp, 0, 0, 0, 'efficiency', '流程效能', 'efficiency', '{}', 1, 5, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'efficiency' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9406, current_timestamp, current_timestamp, 0, 0, 0, 'drafts', '草稿', 'drafts', '{}', 1, 6, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'drafts' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9407, current_timestamp, current_timestamp, 0, 0, 0, 'messages', '消息', 'messages', '{}', 1, 7, 0
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'messages' AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 326, current_timestamp, current_timestamp, 0, 0, 5, 'WorkspaceCardTypes', '工作台卡片', 0, 1,
       'workflow/workspace-card-types', 'workflow/views/WorkspaceCardTypeList', 'workflow:workspace:manage', 'Grid', 26
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 326);

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
UPDATE sys_menu SET hidden = 1, update_time = current_timestamp WHERE id = 4 AND hidden = 0;

-- ======== V96（来源: sw-bootstrap，副本 1 处已去重） ========
-- Phase 4 可靠业务事件（BAO-05）：IoT 流程触发的恢复身份列 + OpenAPI 回调持久任务。
-- 说明：IoT 触发原有 PENDING/SUCCESS/FAILED 状态与唯一幂等键不变，本迁移补齐恢复所需的
--       发起目标身份（process_template_key 等）与重试预算列；回调任务表把“回调意图”持久化，
--       使业务提交后进程退出仍可恢复投递（原实现只在进程内重试 3 次后仅留日志）。

alter table sw_iot_process_trigger add process_template_key VARCHAR2(128 CHAR);
alter table sw_iot_process_trigger add trigger_source VARCHAR2(20 CHAR);
alter table sw_iot_process_trigger add configured_by NUMBER(19);
alter table sw_iot_process_trigger add retry_count NUMBER(10) DEFAULT 0 NOT NULL;
alter table sw_iot_process_trigger add next_retry_time timestamp;
create index idx_sw_iot_trigger_recovery on sw_iot_process_trigger (status, next_retry_time);

create table sw_openapi_callback_task (
    id              NUMBER(19)       not null primary key,
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    version         NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time     timestamp    DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp    DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    app_id          VARCHAR2(64 CHAR)  not null,
    event           VARCHAR2(50 CHAR)  not null,
    biz_ref         VARCHAR2(64 CHAR)  not null,
    status          VARCHAR2(20 CHAR)  DEFAULT 'PENDING' NOT NULL,
    attempts        NUMBER(10)          DEFAULT 0 NOT NULL,
    next_retry_time timestamp,
    last_error      VARCHAR2(500 CHAR),
    delivered_at    timestamp
);
create unique index uk_sw_openapi_cb_task on sw_openapi_callback_task (tenant_id, app_id, event, biz_ref);
create index idx_sw_openapi_cb_task_due on sw_openapi_callback_task (status, next_retry_time);

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
SELECT 400, current_timestamp, current_timestamp, 0, 0, 10, 'DictType', '字典类型', 0, 1, 'system/dict', 'system/views/DictTypeList', 'system:dict:view', 'Collection', 10
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 400);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 401, current_timestamp, current_timestamp, 0, 0, 10, 'DictData', '字典数据管理', 0, 1, 'system/dict-data', 'system/views/DictDataList', 'system:dict:view', 'Document', 20
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 401);

-- 4) 011：菜单管理入口
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 410, current_timestamp, current_timestamp, 0, 0, 1, 'MenuManage', '菜单管理', 0, 1, 'system/menu', 'system/views/MenuList', 'system:menu:manage', 'Menu', 60
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 410);

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
create table sw_bpm_process_favorite (
    id           NUMBER(19)       NOT NULL,
    create_time  timestamp    DEFAULT current_timestamp NOT NULL,
    update_time  timestamp    DEFAULT current_timestamp NOT NULL,
    deleted      NUMBER(5)     DEFAULT 0 NOT NULL,
    version      NUMBER(19)       DEFAULT 0 NOT NULL,
    tenant_id    NUMBER(19)       DEFAULT 0 NOT NULL,
    user_id      NUMBER(19)       NOT NULL,
    process_key  VARCHAR2(64 CHAR)  NOT NULL,
    process_name VARCHAR2(128 CHAR),
    CONSTRAINT pk_sw_bpm_process_favorite PRIMARY KEY (id),
    CONSTRAINT uk_sw_bpm_process_favorite UNIQUE (tenant_id, user_id, process_key)
);
create index idx_sw_bpm_process_favorite_user
    ON sw_bpm_process_favorite (tenant_id, user_id, update_time DESC);

-- ======== V100（来源: sw-bootstrap，副本 1 处已去重） ========
-- ===================================================================
-- V100: V012-BUG-010 —— 流程主题生成规则与实例主题
--
-- 1) sw_bpm_process_def.theme_rule：流程系统级设置的主题生成规则
--    （占位符 {TIMESTAMP}/{YYYYMMDD}/{YYYYMMDDHHMMSS}/{SEQ}，其余为字面量）。
-- 2) sw_bpm_instance.theme：发起时按规则生成的实例主题（待办/已办/详情展示）。
-- 3) sw_bpm_theme_seq：每流程自增序号计数器（{SEQ} 行锁自增，事务内一致）。
-- 幂等：add / create table。
-- ===================================================================
ALTER TABLE sw_bpm_process_def add theme_rule VARCHAR2(200 CHAR);
ALTER TABLE sw_bpm_instance add theme VARCHAR2(200 CHAR);

create table sw_bpm_theme_seq (
    process_key VARCHAR2(64 CHAR) NOT NULL,
    next_val    NUMBER(19)      DEFAULT 1 NOT NULL,
    CONSTRAINT pk_sw_bpm_theme_seq PRIMARY KEY (process_key)
);

-- ======== V101（来源: sw-biz/sw-bpm/sw-bpm-process，副本 1 处已去重） ========
-- V012-BUG-010（bpm 隔离测试链）：主题规则/实例主题/自增计数表
-- Oracle duplicate add skipped: ALTER TABLE sw_bpm_process_def add theme_rule VARCHAR2(200 CHAR);
-- Oracle duplicate add skipped: ALTER TABLE sw_bpm_instance add theme VARCHAR2(200 CHAR);
-- Oracle duplicate create skipped: sw_bpm_theme_seq

-- ======== V102（来源: sw-bootstrap，副本 1 处已去重） ========
-- V012-BUG-009 修正：收藏表补 BaseEntity 审计列（create_by/update_by）
ALTER TABLE sw_bpm_process_favorite add create_by NUMBER(19);
ALTER TABLE sw_bpm_process_favorite add update_by NUMBER(19);

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
SELECT 420, current_timestamp, current_timestamp, 0, 0, 1, 'SsoConfig', 'SSO 配置管理', 0, 1, 'system/sso-config', 'system/views/SsoConfig', 'system:sso:config:list', 'Key', 65
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 420);

-- 按钮权限行（menu_type=2）：编辑/启停/凭据更新分别授权（凭据更新不隐式混入普通编辑权限）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 421, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigEdit', 'SSO 配置编辑', 0, 2, '', '', 'system:sso:config:edit', '', 10
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 421);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 422, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigEnable', 'SSO 配置启停', 0, 2, '', '', 'system:sso:config:enable', '', 20
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 422);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 423, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigSecret', 'SSO 应用凭据更新', 0, 2, '', '', 'system:sso:config:secret', '', 30
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 423);

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

alter table sys_sso_auth_state add config_digest VARCHAR2(128 CHAR);


-- ======== 来源: V0.1.1__form_local_transaction_actions.sql ========
-- P62 首事务阶段：低代码本地事务动作（动作定义/版本/调用/预占/台账 + C1 策略）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-local-transaction-actions.md 与 ADR-P62-001。
-- 基线：V0.1.0__baseline_seed.sql（0.1.3）；本文件为基线后的前向增量迁移，新建库自动执行。

-- ==================== 1. 事务动作定义 ====================
CREATE TABLE sw_form_txn_action (
    id              VARCHAR2(36 CHAR)  PRIMARY KEY,
    form_id         VARCHAR2(36 CHAR)  NOT NULL,
    action_key      VARCHAR2(100 CHAR) NOT NULL,
    name            VARCHAR2(200 CHAR) NOT NULL,
    action_type     VARCHAR2(20 CHAR)  NOT NULL,
    status          VARCHAR2(20 CHAR)  DEFAULT 'DRAFT' NOT NULL,
    current_version NUMBER(10),
    config_json     CLOB,
    description     VARCHAR2(500 CHAR),
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    version         NUMBER(19)       DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_txn_action            IS '低代码事务动作定义（草稿/发布态）';
COMMENT ON COLUMN sw_form_txn_action.id          IS 'UUID 主键';
COMMENT ON COLUMN sw_form_txn_action.form_id     IS '关联 sw_form_def.id（动作绑定表单模型）';
COMMENT ON COLUMN sw_form_txn_action.action_key  IS '动作业务标识（租户+表单内唯一）';
COMMENT ON COLUMN sw_form_txn_action.action_type IS 'RESERVE/CONFIRM/RELEASE/ADJUST';
COMMENT ON COLUMN sw_form_txn_action.status      IS 'DRAFT(草稿)/PUBLISHED(已发布)/DISABLED(已停用)';
COMMENT ON COLUMN sw_form_txn_action.current_version IS '当前已发布版本号（未发布为空）';
COMMENT ON COLUMN sw_form_txn_action.config_json IS '动作声明配置（字段绑定/约束/预占 TTL，CLOB）';

CREATE UNIQUE INDEX uk_sw_form_txn_action_key ON sw_form_txn_action (tenant_id, form_id, action_key);

-- ==================== 2. 动作发布版本（不可变快照） ====================
CREATE TABLE sw_form_txn_action_version (
    id           VARCHAR2(36 CHAR) PRIMARY KEY,
    action_id    VARCHAR2(36 CHAR) NOT NULL,
    version_no   NUMBER(10)         NOT NULL,
    form_version NUMBER(10)         NOT NULL,
    config_json  CLOB       NOT NULL,
    published_by NUMBER(19),
    published_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    tenant_id    NUMBER(19)      DEFAULT 0 NOT NULL,
    deleted      NUMBER(5)    DEFAULT 0 NOT NULL,
    create_time  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by    NUMBER(19),
    update_time  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by    NUMBER(19),
    version      NUMBER(19)      DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_txn_action_version             IS '动作发布版本快照（不可变；已受理调用/预占固定原版本）';
COMMENT ON COLUMN sw_form_txn_action_version.action_id    IS '关联 sw_form_txn_action.id';
COMMENT ON COLUMN sw_form_txn_action_version.version_no   IS '动作版本号（发布递增）';
COMMENT ON COLUMN sw_form_txn_action_version.form_version IS '发布时绑定的表单版本号';

CREATE UNIQUE INDEX uk_sw_form_txn_action_ver ON sw_form_txn_action_version (tenant_id, action_id, version_no);

-- ==================== 3. 动作调用记录（稳定幂等身份与结果回查） ====================
CREATE TABLE sw_form_txn_invocation (
    id              VARCHAR2(36 CHAR)  PRIMARY KEY,
    action_id       VARCHAR2(36 CHAR)  NOT NULL,
    action_version  NUMBER(10)          NOT NULL,
    invocation_key  VARCHAR2(200 CHAR) NOT NULL,
    request_hash    VARCHAR2(64 CHAR)  NOT NULL,
    biz_record_id   VARCHAR2(64 CHAR),
    status          VARCHAR2(20 CHAR)  NOT NULL,
    error_code      NUMBER(10),
    error_msg       VARCHAR2(500 CHAR),
    result_json     CLOB,
    duration_ms     NUMBER(19),
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    version         NUMBER(19)       DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_txn_invocation                 IS '事务动作调用记录（幂等键 + 请求指纹 + 结果回查）';
COMMENT ON COLUMN sw_form_txn_invocation.action_version   IS '调用命中的动作版本（版本固定语义）';
COMMENT ON COLUMN sw_form_txn_invocation.invocation_key   IS '调用幂等键（租户+动作内唯一）';
COMMENT ON COLUMN sw_form_txn_invocation.request_hash     IS '请求指纹（同键不同输入判冲突）';
COMMENT ON COLUMN sw_form_txn_invocation.status           IS 'SUCCEEDED/REJECTED/CONFLICT/FAILED';
COMMENT ON COLUMN sw_form_txn_invocation.create_by        IS '调用身份（操作者）';

CREATE UNIQUE INDEX uk_sw_form_txn_inv_key ON sw_form_txn_invocation (tenant_id, action_id, invocation_key);
CREATE INDEX idx_sw_form_txn_inv_time ON sw_form_txn_invocation (tenant_id, action_id, create_time);

-- ==================== 4. 预占凭据（生命周期：ACTIVE→CONFIRMED/RELEASED/EXPIRED） ====================
CREATE TABLE sw_form_txn_reservation (
    id                   VARCHAR2(36 CHAR)  PRIMARY KEY,
    action_id            VARCHAR2(36 CHAR)  NOT NULL,
    action_version       NUMBER(10)          NOT NULL,
    form_id              VARCHAR2(36 CHAR)  NOT NULL,
    record_id            VARCHAR2(64 CHAR)  NOT NULL,
    biz_keys_json        CLOB,
    quantity             NUMBER(20,6) NOT NULL,
    status               VARCHAR2(20 CHAR)  DEFAULT 'ACTIVE' NOT NULL,
    expires_at           TIMESTAMP    NOT NULL,
    reserve_invocation_id VARCHAR2(36 CHAR),
    settle_invocation_id  VARCHAR2(36 CHAR),
    settled_at           TIMESTAMP,
    tenant_id            NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted              NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by            NUMBER(19),
    update_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by            NUMBER(19),
    version              NUMBER(19)       DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_txn_reservation                     IS '预占凭据（确认/释放/过期竞争以条件更新裁决）';
COMMENT ON COLUMN sw_form_txn_reservation.record_id            IS '目标动态宽表记录 id';
COMMENT ON COLUMN sw_form_txn_reservation.biz_keys_json        IS '声明业务键快照（物料/库位等，CLOB）';
COMMENT ON COLUMN sw_form_txn_reservation.status               IS 'ACTIVE/CONFIRMED/RELEASED/EXPIRED';
COMMENT ON COLUMN sw_form_txn_reservation.expires_at           IS '预占过期时刻（过期后不可确认）';

CREATE INDEX idx_sw_form_txn_res_expire ON sw_form_txn_reservation (tenant_id, status, expires_at);
CREATE INDEX idx_sw_form_txn_res_record ON sw_form_txn_reservation (tenant_id, form_id, record_id);

-- ==================== 5. 事务台账（每次效果一行，勾稽复算） ====================
CREATE TABLE sw_form_txn_ledger (
    id              VARCHAR2(36 CHAR)   PRIMARY KEY,
    action_id       VARCHAR2(36 CHAR)   NOT NULL,
    action_version  NUMBER(10)           NOT NULL,
    invocation_id   VARCHAR2(36 CHAR)   NOT NULL,
    reservation_id  VARCHAR2(36 CHAR),
    entry_type      VARCHAR2(20 CHAR)   NOT NULL,
    form_id         VARCHAR2(36 CHAR)   NOT NULL,
    record_id       VARCHAR2(64 CHAR)   NOT NULL,
    quantity        NUMBER(20,6) NOT NULL,
    balance_after   NUMBER(20,6),
    reserved_after  NUMBER(20,6),
    biz_keys_json   CLOB,
    tenant_id       NUMBER(19)        DEFAULT 0 NOT NULL,
    deleted         NUMBER(5)      DEFAULT 0 NOT NULL,
    create_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    version         NUMBER(19)        DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_txn_ledger                 IS '事务动作台账（RESERVE/CONFIRM/RELEASE/EXPIRE/ADJUST 每效果一行）';
COMMENT ON COLUMN sw_form_txn_ledger.entry_type       IS 'RESERVE/CONFIRM/RELEASE/EXPIRE/ADJUST';
COMMENT ON COLUMN sw_form_txn_ledger.balance_after    IS '动作后余额（同事务行锁下读取，供勾稽）';
COMMENT ON COLUMN sw_form_txn_ledger.reserved_after   IS '动作后有效预占（供勾稽）';

CREATE INDEX idx_sw_form_txn_ledger_action ON sw_form_txn_ledger (tenant_id, action_id, create_time);
CREATE INDEX idx_sw_form_txn_ledger_res ON sw_form_txn_ledger (tenant_id, reservation_id);

-- ==================== 6. C1 关键数据保护策略（表单模型级声明） ====================
CREATE TABLE sw_form_c1_policy (
    id          VARCHAR2(36 CHAR) PRIMARY KEY,
    form_id     VARCHAR2(36 CHAR) NOT NULL,
    enabled     NUMBER(5)    DEFAULT 0 NOT NULL,
    policy_json CLOB,
    applied_at  TIMESTAMP,
    tenant_id   NUMBER(19)      DEFAULT 0 NOT NULL,
    deleted     NUMBER(5)    DEFAULT 0 NOT NULL,
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by   NUMBER(19),
    update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by   NUMBER(19),
    version     NUMBER(19)      DEFAULT 0 NOT NULL
);

COMMENT ON TABLE  sw_form_c1_policy              IS 'C1 关键数据保护策略（受保护字段/约束，随模型声明并发布冻结）';
COMMENT ON COLUMN sw_form_c1_policy.enabled       IS '1=启用 C1 保护（普通写入口拒绝受保护字段）';
COMMENT ON COLUMN sw_form_c1_policy.policy_json   IS '受保护字段列表/余额与预占字段/非负约束（CLOB）';

CREATE UNIQUE INDEX uk_sw_form_c1_policy_form ON sw_form_c1_policy (tenant_id, form_id);

-- ======== 来源: V0.1.2__tiered_command_semantics.sql ========
-- P62 分级执行与统一命令：命令统一身份 / 准入截止 / 效果权威账本（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-tiered-execution-unified-command.md 与 ADR-P62-002。
-- 全部 IF NOT EXISTS 可重复执行；既有行新列保持 NULL，旧语义与旧消费者不受影响。

-- 1) 统一逻辑命令身份与载荷指纹（跨通道复用同一操作身份；允许由既有物理记录映射）
ALTER TABLE sw_bpm_command add logical_command_id VARCHAR2(200 CHAR);
ALTER TABLE sw_bpm_command add payload_fingerprint VARCHAR2(64 CHAR);

-- 2) 受理时冻结的形态/完成点/准入截止（改配置只影响新受理对象）
ALTER TABLE sw_bpm_command add tier VARCHAR2(30 CHAR);
ALTER TABLE sw_bpm_command add completion_point VARCHAR2(200 CHAR);
ALTER TABLE sw_bpm_command add deadline_at timestamp;
ALTER TABLE sw_bpm_command add overdue_at timestamp;

create UNIQUE index uk_sw_bpm_command_logical ON sw_bpm_command (tenant_id, logical_command_id);
create index idx_sw_bpm_command_deadline ON sw_bpm_command (status, deadline_at);

-- 3) 效果权威账本（sw_bpm_command_effect）：与业务效果同事务写入；
--    命令未完成（如提交后崩溃/完成记录未写）时，恢复路径据本表确定结果，不再猜测。
create table sw_bpm_command_effect (
    command_id          NUMBER(19)       NOT NULL PRIMARY KEY,
    logical_command_id  VARCHAR2(200 CHAR),
    claim_token         VARCHAR2(64 CHAR),
    result_json         CLOB,
    biz_ref             VARCHAR2(200 CHAR),
    tenant_id           NUMBER(19)       DEFAULT 0 NOT NULL,
    create_time         timestamp    DEFAULT CURRENT_TIMESTAMP NOT NULL
);
create index idx_sw_bpm_command_effect_logical ON sw_bpm_command_effect (logical_command_id);

COMMENT ON COLUMN sw_bpm_command.logical_command_id IS '统一逻辑命令身份（跨通道同一操作；物理记录可映射）';
COMMENT ON COLUMN sw_bpm_command.payload_fingerprint IS '载荷指纹（同身份不同载荷必须拒绝）';
COMMENT ON COLUMN sw_bpm_command.tier IS '执行形态/等级（受理时冻结）';
COMMENT ON COLUMN sw_bpm_command.completion_point IS '业务完成点声明（受理时冻结）';
COMMENT ON COLUMN sw_bpm_command.deadline_at IS '准入截止（受理时按 1—300s 冻结）';
COMMENT ON COLUMN sw_bpm_command.overdue_at IS '执行中超过截止的标记时间（不据此判失败）';
COMMENT ON TABLE  sw_bpm_command_effect IS '命令效果权威账本（与业务效果同事务写入，供确定恢复）';

-- ======== 来源: V0.1.3__batch_command.sql ========
-- P62 分级执行与统一命令：后台批量（S3）——批次受理 / 稳定项键 / 逐项持久结果（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-tiered-execution-unified-command.md（U02/U07）。
-- 全部 IF NOT EXISTS 可重复执行；批次重放返回原批次，同项同键异载荷拒绝，重复消费不重做已成功项。

-- 1) 批次受理（同租户批次键唯一：重放返回原批次）
create table sw_bpm_command_batch (
    id              NUMBER(19)        NOT NULL PRIMARY KEY,
    create_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)      DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)        DEFAULT 0 NOT NULL,
    version         NUMBER(19)        DEFAULT 0 NOT NULL,
    batch_key       VARCHAR2(128 CHAR)  NOT NULL,
    action_id       VARCHAR2(64 CHAR)   NOT NULL,
    action_version  NUMBER(10),
    status          VARCHAR2(20 CHAR)   DEFAULT 'PENDING' NOT NULL,
    total_count     NUMBER(10)       DEFAULT 0 NOT NULL,
    succeeded_count NUMBER(10)       DEFAULT 0 NOT NULL,
    failed_count    NUMBER(10)       DEFAULT 0 NOT NULL,
    command_id      NUMBER(19),
    initiator_id    NUMBER(19)
);
create UNIQUE index uk_sw_bpm_command_batch_key ON sw_bpm_command_batch (tenant_id, batch_key);
create index idx_sw_bpm_command_batch_cmd ON sw_bpm_command_batch (command_id);

-- 2) 批次项（批次内稳定项键唯一；逐项独立事务与持久结果，可独立追踪与恢复）
create table sw_bpm_command_batch_item (
    id              NUMBER(19)        NOT NULL PRIMARY KEY,
    create_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)      DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)        DEFAULT 0 NOT NULL,
    version         NUMBER(19)        DEFAULT 0 NOT NULL,
    batch_id        NUMBER(19)        NOT NULL,
    item_key        VARCHAR2(128 CHAR)  NOT NULL,
    record_id       VARCHAR2(64 CHAR),
    quantity        VARCHAR2(40 CHAR),
    status          VARCHAR2(20 CHAR)   DEFAULT 'PENDING' NOT NULL,
    invocation_id   VARCHAR2(64 CHAR),
    error_code      NUMBER(10),
    error_msg       VARCHAR2(500 CHAR),
    attempt_count   NUMBER(10)       DEFAULT 0 NOT NULL
);
create UNIQUE index uk_sw_bpm_command_batch_item_key ON sw_bpm_command_batch_item (batch_id, item_key);
create index idx_sw_bpm_command_batch_item_status ON sw_bpm_command_batch_item (batch_id, status);

COMMENT ON TABLE  sw_bpm_command_batch IS '后台批量命令批次（P62 后台批量形态；受理时冻结动作与版本）';
COMMENT ON COLUMN sw_bpm_command_batch.batch_key IS '批次键（同租户唯一；批次重放返回原批次）';
COMMENT ON COLUMN sw_bpm_command_batch.action_version IS '受理时冻结的动作发布版本';
COMMENT ON COLUMN sw_bpm_command_batch.status IS 'PENDING/PROCESSING/COMPLETED/PARTIALLY_FAILED';
COMMENT ON TABLE  sw_bpm_command_batch_item IS '批量命令逐项持久结果（逐项独立事务；稳定项键幂等）';
COMMENT ON COLUMN sw_bpm_command_batch_item.item_key IS '批次内稳定项键（幂等键 BATCH:{batchKey}:{itemKey} 的组成）';
COMMENT ON COLUMN sw_bpm_command_batch_item.status IS 'PENDING/SUCCEEDED/REJECTED';

-- ======== 来源: V0.1.4__resource_assurance.sql ========
-- P62 资源保障与多租户公平：资源策略 / 占用计数 / 拒绝审计 / 命令资源冻结字段（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-resource-assurance.md 与 ADR-P62-003。
-- 全部 IF NOT EXISTS 可重复执行；既有命令行新列保持 NULL——旧在途无资源字段不计费、不参与资源
-- 会计（方向固化的可追踪兼容规则）；新策略默认关闭（enabled= 0），仅显式启用并通过启用检查后
-- 新受理才进入资源会计与额度裁决。

-- 1) 命令资源冻结字段（受理时冻结；资源策略对在途对象同构冻结，减配只影响新受理）
ALTER TABLE sw_bpm_command add resource_class VARCHAR2(10 CHAR);
ALTER TABLE sw_bpm_command add resource_units NUMBER(10);
ALTER TABLE sw_bpm_command add resource_segment VARCHAR2(20 CHAR);
ALTER TABLE sw_bpm_command add policy_version NUMBER(10);
ALTER TABLE sw_bpm_command add resource_released_at timestamp;

create index idx_sw_bpm_command_resource_open ON sw_bpm_command (resource_released_at, status);

-- 2) 资源策略（版本化；同租户同版本唯一；启用须通过启用检查并审计）
create table sw_bpm_resource_policy (
    id                          NUMBER(19)        NOT NULL PRIMARY KEY,
    create_time                 timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by                   NUMBER(19),
    update_time                 timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by                   NUMBER(19),
    deleted                     NUMBER(5)      DEFAULT 0 NOT NULL,
    tenant_id                   NUMBER(19)        DEFAULT 0 NOT NULL,
    version                     NUMBER(19)        DEFAULT 0 NOT NULL,
    policy_version              NUMBER(10)       NOT NULL,
    status                      VARCHAR2(20 CHAR)   DEFAULT 'DRAFT' NOT NULL,
    enabled                     NUMBER(1)       DEFAULT 0 NOT NULL,
    stop_acceptance             NUMBER(1)       DEFAULT 0 NOT NULL,
    global_max_outstanding      NUMBER(10)       NOT NULL,
    tenant_max_outstanding      NUMBER(10)       NOT NULL,
    prod_reserved               NUMBER(10)       NOT NULL,
    oa_reserved                 NUMBER(10)       NOT NULL,
    shared_capacity             NUMBER(10)       NOT NULL,
    tenant_rate_per_sec         NUMBER(10)       NOT NULL,
    tenant_burst                NUMBER(10)       NOT NULL,
    realtime_global_concurrency NUMBER(10)       NOT NULL,
    realtime_tenant_concurrency NUMBER(10)       NOT NULL,
    batch_slice_items           NUMBER(10)       DEFAULT 25 NOT NULL,
    batch_poll_claim_limit      NUMBER(10)       DEFAULT 1 NOT NULL,
    remark                      VARCHAR2(500 CHAR)
);
create UNIQUE index uk_sw_bpm_resource_policy_version ON sw_bpm_resource_policy (tenant_id, policy_version);
create index idx_sw_bpm_resource_policy_status ON sw_bpm_resource_policy (status);

-- 3) 占用计数（原子条件更新的准入加速器；权威事实=命令/批次项/引擎队列持久行，可重建勾稽。
--    跨策略版本共享同一套计数，不按版本分账——方向明确禁止「每版本各自可超额的一套计数」）
create table sw_bpm_resource_usage (
    id              NUMBER(19)        NOT NULL PRIMARY KEY,
    create_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)      DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)        DEFAULT 0 NOT NULL,
    version         NUMBER(19)        DEFAULT 0 NOT NULL,
    scope           VARCHAR2(16 CHAR)   NOT NULL,
    scope_key       NUMBER(19)        DEFAULT 0 NOT NULL,
    segment         VARCHAR2(20 CHAR)   NOT NULL,
    outstanding     NUMBER(19)        DEFAULT 0 NOT NULL
);
create UNIQUE index uk_sw_bpm_resource_usage_scope ON sw_bpm_resource_usage (scope, scope_key, segment);

-- 4) 拒绝审计（REQUIRES_NEW 独立短事务写入；拒绝不产生业务效果但留可查证据）
create table sw_bpm_resource_reject_log (
    id              NUMBER(19)        NOT NULL PRIMARY KEY,
    create_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)      DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)        DEFAULT 0 NOT NULL,
    version         NUMBER(19)        DEFAULT 0 NOT NULL,
    policy_version  NUMBER(10),
    resource_class  VARCHAR2(10 CHAR),
    reject_scope    VARCHAR2(20 CHAR)   NOT NULL,
    requested_units NUMBER(10)       DEFAULT 0 NOT NULL,
    reason_code     VARCHAR2(80 CHAR),
    detail          VARCHAR2(500 CHAR),
    command_key     VARCHAR2(200 CHAR)
);
create index idx_sw_bpm_resource_reject_tenant_time ON sw_bpm_resource_reject_log (tenant_id, create_time);

-- 5) 全局计数种子行（幂等；固定低位 id 不与雪花 id 冲突；租户行按需惰性建立）
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910001, 0, 'GLOBAL', 0, 'TOTAL', 0  from dual WHERE NOT EXISTS
    (SELECT 1 FROM sw_bpm_resource_usage WHERE scope = 'GLOBAL' AND scope_key = 0 AND segment = 'TOTAL');
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910002, 0, 'GLOBAL', 0, 'PROD_RESERVED', 0  from dual WHERE NOT EXISTS
    (SELECT 1 FROM sw_bpm_resource_usage WHERE scope = 'GLOBAL' AND scope_key = 0 AND segment = 'PROD_RESERVED');
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910003, 0, 'GLOBAL', 0, 'OA_RESERVED', 0  from dual WHERE NOT EXISTS
    (SELECT 1 FROM sw_bpm_resource_usage WHERE scope = 'GLOBAL' AND scope_key = 0 AND segment = 'OA_RESERVED');
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910004, 0, 'GLOBAL', 0, 'SHARED', 0  from dual WHERE NOT EXISTS
    (SELECT 1 FROM sw_bpm_resource_usage WHERE scope = 'GLOBAL' AND scope_key = 0 AND segment = 'SHARED');

COMMENT ON COLUMN sw_bpm_command.resource_class IS '资源类别（PROD/OA/BULK；受理时冻结；NULL=旧对象不参与资源会计）';
COMMENT ON COLUMN sw_bpm_command.resource_units IS '占用工作单位数（批量=项数，其余=1；受理时冻结）';
COMMENT ON COLUMN sw_bpm_command.resource_segment IS '占用容量段（PROD_RESERVED/OA_RESERVED/SHARED；受理时冻结）';
COMMENT ON COLUMN sw_bpm_command.policy_version IS '受理时冻结的资源策略版本（跨版本共享计数）';
COMMENT ON COLUMN sw_bpm_command.resource_released_at IS '占用释放时间（单位回收事实；NULL=仍占用）';
COMMENT ON TABLE  sw_bpm_resource_policy IS 'P62 资源策略（版本化额度与并发预算；新策略默认关闭，启用须通过启用检查）';
COMMENT ON COLUMN sw_bpm_resource_policy.status IS 'DRAFT/ACTIVE/RETIRED（同租户至多一个 ACTIVE）';
COMMENT ON COLUMN sw_bpm_resource_policy.enabled IS '资源会计与准入裁决总开关（默认 FALSE=零行为变化）';
COMMENT ON COLUMN sw_bpm_resource_policy.stop_acceptance IS '停新受理开关（TRUE 时新受理明确拒绝，已有工作按原合同结算）';
COMMENT ON COLUMN sw_bpm_resource_policy.global_max_outstanding IS '持久未完成工作量全局上限';
COMMENT ON COLUMN sw_bpm_resource_policy.tenant_max_outstanding IS '每租户持久未完成工作量上限';
COMMENT ON COLUMN sw_bpm_resource_policy.prod_reserved IS '生产保留容量（不可被新增低等级工作占满）';
COMMENT ON COLUMN sw_bpm_resource_policy.oa_reserved IS '普通 OA 保留容量（不可被批量占满）';
COMMENT ON COLUMN sw_bpm_resource_policy.shared_capacity IS '共享容量（生产/OA/批量竞争段）';
COMMENT ON COLUMN sw_bpm_resource_policy.tenant_rate_per_sec IS '每租户工作单位速率上限（单位/s）';
COMMENT ON COLUMN sw_bpm_resource_policy.tenant_burst IS '每租户突发额（工作单位）';
COMMENT ON COLUMN sw_bpm_resource_policy.realtime_global_concurrency IS '实时受控动作全局并发上限';
COMMENT ON COLUMN sw_bpm_resource_policy.realtime_tenant_concurrency IS '实时受控动作单租户并发上限';
COMMENT ON COLUMN sw_bpm_resource_policy.batch_slice_items IS '批量命令单次执行切片项数（让出调度线程，保留其他工作推进机会）';
COMMENT ON COLUMN sw_bpm_resource_policy.batch_poll_claim_limit IS '每轮调度批量命令领取上限';
COMMENT ON TABLE  sw_bpm_resource_usage IS 'P62 资源占用计数（scope: GLOBAL/TENANT；segment: TOTAL/PROD_RESERVED/OA_RESERVED/SHARED）';
COMMENT ON TABLE  sw_bpm_resource_reject_log IS 'P62 资源拒绝审计（独立短事务；拒绝范围/额度/原因可查）';

-- ======== 来源: V0.1.5__p63_dynamic_branch_semantics.sql ========
-- V0.1.5 (P63): 动态并行语义 v2。
-- 语义版本 2：按对象身份生成分支（USER=人、DEPT=部门→负责人），同负责人不同部门保持独立分支；
-- 轮次以多实例根 executionId 标识（同 execution 恢复复用、退回重入新轮次，旧轮次行保留）；
-- source_refs 保留全部来源位置（表格行 id / 字段引用）。
-- semantic_version IS NULL = 旧语义行（按负责人合并），行为不变。
ALTER TABLE sw_bpm_dynamic_branch add semantic_version NUMBER(5);
ALTER TABLE sw_bpm_dynamic_branch add object_type VARCHAR2(16 CHAR);
ALTER TABLE sw_bpm_dynamic_branch add object_id VARCHAR2(64 CHAR);
ALTER TABLE sw_bpm_dynamic_branch add round_no NUMBER(19);
ALTER TABLE sw_bpm_dynamic_branch add execution_id VARCHAR2(64 CHAR);
ALTER TABLE sw_bpm_dynamic_branch add source_refs CLOB;
drop index uk_sw_bpm_dyn_branch;
CREATE UNIQUE INDEX uk_sw_bpm_dyn_branch
    ON sw_bpm_dynamic_branch (tenant_id, process_instance_id, node_key, round_no, branch_index);

-- ======== 来源: V0.1.6__p63_iot_command_reservation.sql ========
-- V0.1.6 (P63): 流程成功结束后的一次性 IoT 预约意图。
-- 冻结租户/定义/实例/表单记录/设备目标/命令与参数/预约时刻（显式时区）/迟到窗口；
-- 状态机：PENDING → DISPATCHING → DISPATCHED；CANCELED / EXPIRED / FAILED 为确定终态。
-- 同一租户同一流程实例至多一条预约意图（同一次成功完成的幂等身份）。
create table sw_iot_command_reservation (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null,
    process_def_key VARCHAR2(128 CHAR),
    def_version NUMBER(10),
    form_key VARCHAR2(128 CHAR),
    record_id VARCHAR2(36 CHAR),
    device_key VARCHAR2(128 CHAR),
    product_id VARCHAR2(128 CHAR),
    device_name VARCHAR2(128 CHAR),
    command_key VARCHAR2(128 CHAR),
    command_type VARCHAR2(16 CHAR),
    payload CLOB,
    due_at_utc timestamp not null,
    timezone_id VARCHAR2(64 CHAR) not null,
    due_local_text VARCHAR2(64 CHAR),
    late_window_seconds NUMBER(10) not null,
    status VARCHAR2(20 CHAR) not null,
    command_id NUMBER(19),
    reject_reason VARCHAR2(512 CHAR),
    cancel_by NUMBER(19),
    cancel_reason VARCHAR2(512 CHAR),
    cancel_time timestamp
);
create index idx_sw_iot_reservation_status on sw_iot_command_reservation (status, due_at_utc);
create unique index uk_sw_iot_reservation_instance on sw_iot_command_reservation (tenant_id, process_instance_id);

-- ======== 来源: V0.1.7__p64_orchestration_data_action.sql ========
-- V0.1.7 (P64 阶段Ⅰ 数据到动作): 节点业务表单 / Trigger 判断 / 关联动作三张新表。
-- 全部为追加新表，不 ALTER 既有表；旧图（无 variables/triggers/节点表单绑定）零行为，
-- 旧定义与运行实例按其 def_version 冻结图运行（ADR-P64-001 §6）。
-- 回退：新代码回滚后旧代码不读写三张新表，数据保留，不通过破坏性 DDL 删除事实。

-- 1) 任务级节点业务表单数据：一行 = 一个任务的数据身份（ADR-P64-001 §1）。
-- status: DRAFT / SUBMITTED；仅合法最终提交（APPROVE/DISAPPROVE 同事务）进入 SUBMITTED，
-- REJECT/RETURN 不产生有效提交；round_no = 实例 RETURN 动作数 + 1（退回前旧轮不混入新轮读取）。
create table sw_bpm_task_form_data (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null,
    process_def_key VARCHAR2(128 CHAR),
    node_key VARCHAR2(200 CHAR) not null,
    task_id VARCHAR2(64 CHAR) not null,
    round_no NUMBER(19) DEFAULT 0 NOT NULL,
    form_key VARCHAR2(128 CHAR) not null,
    form_version NUMBER(19) not null,
    status VARCHAR2(16 CHAR) not null,
    data_text CLOB,
    submitted_by NUMBER(19),
    submit_time timestamp
);
create unique index uk_sw_bpm_task_form_data on sw_bpm_task_form_data (tenant_id, task_id);
create index idx_sw_bpm_task_form_data_instance on sw_bpm_task_form_data (tenant_id, process_instance_id, node_key, round_no);

-- 2) Trigger 判断执行记录：一次评估一行（ADR-P64-001 §3）。
-- status: MATCHED / UNMATCHED / FAILED；disposition: EMPTY_COLLECTION / OVER_LIMIT /
-- BLOCK / IGNORE；snapshot_text 为本次判断共用的一致数据快照（≤1MiB）；可诊断、不冒充成功。
create table sw_bpm_trigger_exec (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    process_instance_id VARCHAR2(64 CHAR) not null,
    process_def_key VARCHAR2(128 CHAR),
    def_version NUMBER(10),
    trigger_id VARCHAR2(64 CHAR) not null,
    event_type VARCHAR2(32 CHAR) not null,
    node_key VARCHAR2(200 CHAR),
    round_no NUMBER(19) DEFAULT 0 NOT NULL,
    exec_key VARCHAR2(200 CHAR) not null,
    status VARCHAR2(20 CHAR) not null,
    result_value VARCHAR2(512 CHAR),
    result_type VARCHAR2(16 CHAR),
    matched_branch_id VARCHAR2(64 CHAR),
    disposition VARCHAR2(32 CHAR),
    error_text CLOB,
    snapshot_text CLOB,
    duration_ms NUMBER(19)
);
create unique index uk_sw_bpm_trigger_exec on sw_bpm_trigger_exec (tenant_id, exec_key);
create index idx_sw_bpm_trigger_exec_instance on sw_bpm_trigger_exec (tenant_id, process_instance_id);

-- 3) 动作意图与目标实例关联：一次派发项一行（ADR-P64-001 §4）。
-- status: INTENT_SUBMITTED / STARTED / FAILED；command_key 与 sw_bpm_command 幂等键一致；
-- target_record_id/target_instance_id 由消费事务回填，构成 业务单据→触发→动作→关联实例 回查链。
create table sw_bpm_action_ref (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    exec_id NUMBER(19) not null,
    process_instance_id VARCHAR2(64 CHAR) not null,
    trigger_id VARCHAR2(64 CHAR),
    action_id VARCHAR2(64 CHAR) not null,
    action_type VARCHAR2(24 CHAR) not null,
    item_key VARCHAR2(128 CHAR) not null,
    item_summary CLOB,
    payload_json CLOB,
    command_key VARCHAR2(200 CHAR) not null,
    command_id NUMBER(19),
    target_def_key VARCHAR2(128 CHAR) not null,
    target_form_key VARCHAR2(128 CHAR),
    target_record_id VARCHAR2(64 CHAR),
    target_instance_id VARCHAR2(64 CHAR),
    status VARCHAR2(20 CHAR) not null,
    error_text CLOB
);
create unique index uk_sw_bpm_action_ref on sw_bpm_action_ref (tenant_id, command_key);
create index idx_sw_bpm_action_ref_instance on sw_bpm_action_ref (tenant_id, process_instance_id);
create index idx_sw_bpm_action_ref_exec on sw_bpm_action_ref (tenant_id, exec_id);

-- ======== 来源: V0.1.8__p64_phase2_parent_child.sql ========
-- V0.1.8 (P64 阶段Ⅱ 人员与父子协作): 子流程派发批次 / 批次项两张新表。
-- 全部为追加新表，不 ALTER 既有表；无 CHILD 动作的旧图零行为，
-- 旧定义与运行实例按其 def_version 冻结图运行（ADR-P64-001 §6 回退口径不变）。
-- 回退：新代码回滚后旧代码不读写两张新表，数据保留，不通过破坏性 DDL 删除事实。

-- 1) 子流程派发批次：一次 CHILD 动作派发一批（主方向 §3.3 派发冻结）。
-- status: WAITING（等待结算）/ SETTLED（已结算推进一次）/ BLOCKED（失败/冲突阻断，可诊断）/
--         CANCELLED（父取消/退回终止写回推进权）。
-- wait_policy: ALL / ANY / COUNT / NONE（NONE=派发意图提交即结算）；wait_count 仅 COUNT 有效。
-- parent_depth: 父链深度（根=0，子=父+1；默认上限 3、硬上限 8）；
-- root_instance_id: 业务根链实例（累计实例护栏计数锚）。
-- source_record_id/source_record_version: 派发时冻结的父主表单记录身份与版本（稳定行契约）。
create table sw_bpm_child_batch (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    batch_key VARCHAR2(240 CHAR) not null,
    parent_instance_id VARCHAR2(64 CHAR) not null,
    parent_def_key VARCHAR2(128 CHAR),
    root_instance_id VARCHAR2(64 CHAR),
    parent_depth NUMBER(10) DEFAULT 0 NOT NULL,
    trigger_id VARCHAR2(64 CHAR) not null,
    action_id VARCHAR2(64 CHAR) not null,
    round_no NUMBER(19) DEFAULT 0 NOT NULL,
    wait_policy VARCHAR2(16 CHAR) not null,
    wait_count NUMBER(10),
    expected_count NUMBER(10) DEFAULT 0 NOT NULL,
    source_record_id VARCHAR2(64 CHAR),
    source_record_version NUMBER(19),
    status VARCHAR2(20 CHAR) not null,
    settled_count NUMBER(10) DEFAULT 0 NOT NULL,
    block_reason CLOB,
    settled_at timestamp,
    config_json CLOB
);
create unique index uk_sw_bpm_child_batch on sw_bpm_child_batch (tenant_id, batch_key);
create index idx_sw_bpm_child_batch_parent on sw_bpm_child_batch (tenant_id, parent_instance_id);
create index idx_sw_bpm_child_batch_root on sw_bpm_child_batch (tenant_id, root_instance_id);

-- 2) 子流程批次项：一个预期子流程一行（冻结来源行身份与版本）。
-- status: DISPATCHED（已派发待子完成）/ WRITTEN（有效完成且所需回写已提交，计成功数）/
--         CONFLICT（回写冲突挂起，可诊断、有权恢复不重复已生效结果）/
--         LATE（批次已结算后的迟到完成，独立留痕不覆盖已用快照）/
--         REFUSED（父取消/退回后失去写回推进权，留痕）/ FAILED（发起链失败且不可恢复）/ CANCELLED。
-- source_row_id/source_row_version: 稳定来源行身份与派发时版本（父表排序改变不错行）；
-- source_rows_json: 本项冻结的授权来源行集合 [{rowId, version}]（分组项含多行；行级回写只接受集合内行）；
-- writeback_json/writeback_source: 回写值与来源（子实例/节点/轮次/任务可追溯）。
create table sw_bpm_child_item (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    batch_id NUMBER(19) not null,
    item_key VARCHAR2(128 CHAR) not null,
    action_ref_id NUMBER(19),
    source_row_id VARCHAR2(64 CHAR),
    source_row_version NUMBER(19),
    source_rows_json CLOB,
    source_summary CLOB,
    target_def_key VARCHAR2(128 CHAR),
    target_form_key VARCHAR2(128 CHAR),
    target_record_id VARCHAR2(64 CHAR),
    target_instance_id VARCHAR2(64 CHAR),
    status VARCHAR2(20 CHAR) not null,
    writeback_json CLOB,
    writeback_time timestamp,
    writeback_source VARCHAR2(200 CHAR),
    error_text CLOB
);
create unique index uk_sw_bpm_child_item on sw_bpm_child_item (tenant_id, batch_id, item_key);
create index idx_sw_bpm_child_item_target on sw_bpm_child_item (tenant_id, target_record_id);
create index idx_sw_bpm_child_item_batch on sw_bpm_child_item (tenant_id, batch_id);

-- ======== 来源: V0.1.9__p64_position_delegate.sql ========
-- V0.1.9 (P64 阶段Ⅱ 人员与父子协作): 后台源岗位→受托岗位委托关系表（组织域通用配置）。
-- 追加新表，不 ALTER 既有表；未配置委托关系时岗位解析沿 P63 原义（源岗位有效人员）。
-- scope_type: ORG（显式组织默认范围）/ DEPT（精确部门范围，优先于 ORG）；dept_id 仅 DEPT 有效。
-- status: ENABLED / DISABLED（启停、审计；每个源岗位/适用范围最多一条有效关系，
--         同优先级重叠由服务层配置校验拒绝——自委托/循环/跨租户/越权映射同样在配置与生效时拒绝）。
-- 回退：新代码回滚后旧代码不读写本表，配置数据保留，不通过破坏性 DDL 删除事实。
create table sys_post_delegate (
    id NUMBER(19) not null primary key,
    create_time timestamp DEFAULT current_timestamp NOT NULL,
    create_by NUMBER(19),
    update_time timestamp DEFAULT current_timestamp NOT NULL,
    update_by NUMBER(19),
    deleted NUMBER(10) DEFAULT 0 NOT NULL,
    tenant_id NUMBER(19) DEFAULT 0 NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    source_post_id NUMBER(19) not null,
    target_post_id NUMBER(19) not null,
    scope_type VARCHAR2(16 CHAR) not null,
    dept_id NUMBER(19),
    status VARCHAR2(16 CHAR) not null,
    remark VARCHAR2(500 CHAR)
);
create index idx_sys_post_delegate_source on sys_post_delegate (tenant_id, source_post_id, status);
create index idx_sys_post_delegate_dept on sys_post_delegate (tenant_id, dept_id);

-- ======== 来源: V0.1.10__p64_approval_form_type.sql ========
-- BF20261013-020：低代码表单分类型（元数据区分）。
-- form_type：BUSINESS（业务表单，存量默认）/ APPROVAL_COMMENT（审批意见表单，流程节点审批意见专用，
-- 在「流程管理 → 审批意见表单」单独维护；流程节点选择器只列该类型）。

ALTER TABLE sw_form_def add form_type VARCHAR2(20 CHAR) DEFAULT 'BUSINESS' NOT NULL;

COMMENT ON COLUMN sw_form_def.form_type IS '表单类型：BUSINESS(业务表单)/APPROVAL_COMMENT(审批意见表单，流程节点专用)';

-- ======== 来源: V20261015190000__application_center.sql ========
-- ===================================================================
-- 应用中心与表单分类统一（L）：应用（一级分类）→ 目录（二级分类）→ 表单
--
-- 背景：既有一级分类是流程事项分类（sw_bpm_category），表单分类由前端按事项
-- formKey 派生（同 key 取首项，属偶然结果）；无业务应用/目录实体。
-- 本迁移：
--   1. 新增 sw_form_app / sw_form_app_dir / sw_form_app_user 三表；
--      sw_form_def 增加 app_id / dir_id 归属列（目录可空=直接归属应用）。
--   2. 存量承接（幂等条件均为确定性判定，不取首项、不自动复制业务数据）：
--      a) 每个有效分类承接为一个应用（id = 'lc' || category.id，legacy_category_id 保留追溯）；
--      b) 表单归属：该 formKey 的全部有效事项（deleted=0）关联的有效分类去重后
--         恰为一个 → 归属该分类承接应用；为零个（分类缺失且无冲突）→ 归属本租户
--         「未分类」承接应用（id = 'uncat-t' || tenant_id，仅在该租户存在此类表单时创建）；
--         多于一个（跨分类共用表单=多归属歧义）→ 不自动转换，app_id 保持 NULL，
--         保留原可用路径，由正式冲突清单交 Planner 裁决后才转换。
--      c) 承接应用可见范围 = ALL（本租户全部用户），以保留既有合法访问；
--         数据读取仍受既有表单/数据权限约束，应用可见性不放大数据范围。
--   3. 回退：新代码回滚后旧代码不读写本三表与两列，存量数据保持原表原义，
--      不通过破坏性 DDL 删除事实。
-- 方言：H2/PostgreSQL 双份；本文件为 PostgreSQL 版（COMMENT ON），H2 版无 comment。
-- ===================================================================

-- ==================== 1. 应用表 ====================
CREATE TABLE sw_form_app (
    id                  VARCHAR2(36 CHAR)  PRIMARY KEY,
    tenant_id           NUMBER(19)       DEFAULT 0 NOT NULL,
    name                VARCHAR2(100 CHAR) NOT NULL,
    icon                VARCHAR2(64 CHAR),
    color               VARCHAR2(32 CHAR),
    sort_no             NUMBER(10)          DEFAULT 0 NOT NULL,
    visible_scope_type  VARCHAR2(20 CHAR)  DEFAULT 'SPECIFIED' NOT NULL,
    status              VARCHAR2(20 CHAR)  DEFAULT 'ENABLED' NOT NULL,
    source              VARCHAR2(20 CHAR)  DEFAULT 'MANUAL' NOT NULL,
    legacy_category_id  NUMBER(19),
    deleted             NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by           NUMBER(19),
    update_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by           NUMBER(19),
    version             NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_form_app_tenant ON sw_form_app (tenant_id, deleted, sort_no);

COMMENT ON TABLE  sw_form_app                    IS '业务应用（表单一级分类）';
COMMENT ON COLUMN sw_form_app.id                 IS '应用 ID（承接存量分类为 lc<categoryId>，未分类承接为 uncat-t<tenantId>，新建为 UUID）';
COMMENT ON COLUMN sw_form_app.name               IS '应用名称';
COMMENT ON COLUMN sw_form_app.icon               IS '图标标识（前端图标名，可空取默认）';
COMMENT ON COLUMN sw_form_app.color              IS '主题色（可空取默认）';
COMMENT ON COLUMN sw_form_app.sort_no            IS '排序号（升序）';
COMMENT ON COLUMN sw_form_app.visible_scope_type IS '可见范围：ALL=本租户全部用户（显式选择）/ SPECIFIED=指定用户（关联集合空=无人可见）';
COMMENT ON COLUMN sw_form_app.status             IS '状态：ENABLED=启用 / DISABLED=停用（阻止新增入口，保留数据与历史）';
COMMENT ON COLUMN sw_form_app.source             IS '来源：MANUAL=后台新建 / LEGACY_CATEGORY=存量分类承接 / LEGACY_UNCATEGORIZED=存量未分类承接';
COMMENT ON COLUMN sw_form_app.legacy_category_id IS '承接来源分类 ID（source=LEGACY_CATEGORY 时有效，可追溯）';

-- ==================== 2. 应用目录表（应用内二级分类，单层，只属于一个应用） ====================
CREATE TABLE sw_form_app_dir (
    id          VARCHAR2(36 CHAR)  PRIMARY KEY,
    tenant_id   NUMBER(19)       DEFAULT 0 NOT NULL,
    app_id      VARCHAR2(36 CHAR)  NOT NULL,
    name        VARCHAR2(100 CHAR) NOT NULL,
    sort_no     NUMBER(10)          DEFAULT 0 NOT NULL,
    deleted     NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by   NUMBER(19),
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by   NUMBER(19),
    version     NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_form_app_dir_app ON sw_form_app_dir (tenant_id, app_id, deleted, sort_no);

COMMENT ON TABLE  sw_form_app_dir      IS '应用目录（应用内二级分类，单层）';
COMMENT ON COLUMN sw_form_app_dir.app_id IS '所属应用 ID（目录只属于一个应用）';

-- ==================== 3. 应用指定用户表（可见范围 = SPECIFIED 时生效） ====================
CREATE TABLE sw_form_app_user (
    id          VARCHAR2(36 CHAR) PRIMARY KEY,
    tenant_id   NUMBER(19)      DEFAULT 0 NOT NULL,
    app_id      VARCHAR2(36 CHAR) NOT NULL,
    user_id     NUMBER(19)      NOT NULL,
    deleted     NUMBER(5)    DEFAULT 0 NOT NULL,
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by   NUMBER(19),
    update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by   NUMBER(19),
    version     NUMBER(19)      DEFAULT 0 NOT NULL
);

-- 唯一键不带 deleted：关联行是纯配置关系，可见范围重配走物理删除重建
-- （与 sys_role_menu 同一口径，避免逻辑删行占用唯一键导致「移除后再加入」撞键）。
CREATE UNIQUE INDEX uk_sw_form_app_user ON sw_form_app_user (tenant_id, app_id, user_id);

COMMENT ON TABLE  sw_form_app_user         IS '应用指定可见用户（SPECIFIED 范围的关联集合）';
COMMENT ON COLUMN sw_form_app_user.user_id IS '用户 ID（sys_user.id）';

-- ==================== 4. 表单归属列 ====================
ALTER TABLE sw_form_def add app_id VARCHAR2(36 CHAR);
ALTER TABLE sw_form_def add dir_id VARCHAR2(36 CHAR);

CREATE INDEX idx_sw_form_def_app ON sw_form_def (tenant_id, app_id, deleted);

COMMENT ON COLUMN sw_form_def.app_id IS '归属应用 ID（sw_form_app.id；NULL=承接歧义对象，未转换）';
COMMENT ON COLUMN sw_form_def.dir_id IS '归属目录 ID（sw_form_app_dir.id；NULL=直接归属应用）';

-- ==================== 5. 存量承接：分类 → 应用 ====================
INSERT INTO sw_form_app (id, tenant_id, name, icon, color, sort_no, visible_scope_type, status, source,
                         legacy_category_id, deleted, create_time, update_time, version)
SELECT 'lc' || c.id, c.tenant_id, c.name, 'Folder', '#7e306b', c.sort_no, 'ALL', 'ENABLED', 'LEGACY_CATEGORY',
       c.id, 0, current_timestamp, current_timestamp, 0
FROM sw_bpm_category c
WHERE c.deleted = 0;

-- ==================== 6. 存量承接：唯一分类表单归属 ====================
UPDATE sw_form_def f SET app_id = (
    SELECT 'lc' || MIN(pd.category_id)
    FROM sw_bpm_process_def pd
    JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
    WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0
)
WHERE f.deleted = 0
  AND (SELECT COUNT(DISTINCT pd.category_id)
       FROM sw_bpm_process_def pd
       JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
       WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0) = 1;

-- ==================== 7. 存量承接：未分类应用（按需创建） ====================
INSERT INTO sw_form_app (id, tenant_id, name, icon, color, sort_no, visible_scope_type, status, source,
                         legacy_category_id, deleted, create_time, update_time, version)
SELECT 'uncat-t' || f.tenant_id, f.tenant_id, '未分类', 'Folder', '#909399', 9999, 'ALL', 'ENABLED',
       'LEGACY_UNCATEGORIZED', NULL, 0, current_timestamp, current_timestamp, 0
FROM sw_form_def f
WHERE f.deleted = 0
  AND (SELECT COUNT(DISTINCT pd.category_id)
       FROM sw_bpm_process_def pd
       JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
       WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0) = 0
GROUP BY f.tenant_id;

-- ==================== 8. 存量承接：分类缺失表单归属未分类 ====================
UPDATE sw_form_def f SET app_id = 'uncat-t' || f.tenant_id
WHERE f.deleted = 0 AND f.app_id IS NULL
  AND (SELECT COUNT(DISTINCT pd.category_id)
       FROM sw_bpm_process_def pd
       JOIN sw_bpm_category c ON c.id = pd.category_id AND c.deleted = 0
       WHERE pd.form_key = f.form_key AND pd.tenant_id = f.tenant_id AND pd.deleted = 0) = 0;

-- ======== 来源: V0.1.7__bugfix017_menu_and_app_scope.sql ========
-- ===================================================================
-- 0.1.7 Bug 修复列车（1.0.7bug 清单）
-- 1) 后台菜单调整（0.1.7 #6）：定时任务并入系统管理；应用管理上提为顶级第二项
-- 2) 应用可见范围多维度（0.1.7 #1）：新增 sw_form_app_scope（USER/DEPT/ROLE/POST/TENANT），
--    存量指定用户迁移为 USER 维度；sw_form_app_user 保留为历史只读，不再写入
-- ===================================================================

-- ======== 1. 菜单调整（按 id 定点 UPDATE，幂等） ========
-- 定时任务（顶级目录 id=17）并入系统管理（id=1），组内排序 90
UPDATE sys_menu SET parent_id = 1, sort = 90 WHERE id = 17 AND deleted = 0;
-- 应用管理（id=9130）上提为顶级菜单，排序 15 = 系统管理(10) 与 表单管理(20) 之间的第二项
UPDATE sys_menu SET parent_id = 0, sort = 15 WHERE id = 9130 AND deleted = 0;

-- ======== 2. 应用可见范围多维度表 ========
CREATE TABLE sw_form_app_scope (
    id          VARCHAR2(36 CHAR) PRIMARY KEY,
    tenant_id   NUMBER(19)      DEFAULT 0 NOT NULL,
    app_id      VARCHAR2(36 CHAR) NOT NULL,
    scope_type  VARCHAR2(20 CHAR) NOT NULL,
    scope_id    VARCHAR2(64 CHAR) NOT NULL,
    deleted     NUMBER(5)    DEFAULT 0 NOT NULL,
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by   NUMBER(19),
    update_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by   NUMBER(19),
    version     NUMBER(19)      DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_form_app_scope_app ON sw_form_app_scope (tenant_id, app_id, deleted);

-- 存量「指定用户」迁移为 USER 维度（沿用源行 id 保证唯一；关联行是纯配置关系）
INSERT INTO sw_form_app_scope (id, tenant_id, app_id, scope_type, scope_id,
                               create_time, update_time, deleted, version)
SELECT id, tenant_id, app_id, 'USER', CAST(user_id AS VARCHAR2(64 CHAR)),
       create_time, update_time, deleted, version
FROM sw_form_app_user;

-- ======== 来源: V0.1.8__tenant_username_unique.sql ========
-- 0.2.0 T020：用户名按租户唯一（DR02），登录解析见 LoginUserResolver
drop index uk_sys_user_username;
create UNIQUE index uk_sys_user_tenant_username ON sys_user (tenant_id, username, deleted);

-- ======== 来源: V0.1.9__tenant_admin_identity.sql ========
-- T020 / DR02：稳定管理员关联和创建请求去重。只回填唯一、同租户的有效授权关系。
ALTER TABLE sys_tenant add admin_user_id NUMBER(19);
ALTER TABLE sys_tenant add creation_request_id VARCHAR2(64 CHAR);
CREATE UNIQUE INDEX uk_sys_tenant_creation_request ON sys_tenant (creation_request_id);

UPDATE sys_tenant t
SET admin_user_id = (
    SELECT MIN(u.id)
    FROM sys_user u
    JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = t.id AND ur.deleted = 0
    JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = t.id AND r.deleted = 0 AND r.status = 1
    WHERE u.tenant_id = t.id AND u.deleted = 0 AND u.status = 0
      AND ((t.id = 0 AND r.code = 'superadmin') OR (t.id <> 0 AND r.code = 'admin'))
    HAVING COUNT(DISTINCT u.id) = 1
)
WHERE t.deleted = 0;

-- 租户管理恢复为真实授权菜单；仅绑定租户 0 的超级管理员角色，不进入普通管理员模板。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9600, current_timestamp, current_timestamp, 0, 0, 1, 'SystemTenant', '租户管理', 0, 1, 'system/tenant', 'system/views/TenantList', 'system:tenant:list', 'OfficeBuilding', 60
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9600)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 1 AND name = 'SystemTenant' AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT 9601, current_timestamp, current_timestamp, 0, 0, 0, r.id, 9600
FROM sys_role r
WHERE r.tenant_id = 0 AND r.code = 'superadmin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 9600 AND deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9601)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = r.id AND menu_id = 9600 AND tenant_id = 0 AND deleted = 0);

-- ======== 来源: V0.1.10__tenant_status_audit.sql ========
create table sys_tenant_status_audit (
    id              NUMBER(19)          not null primary key,
    create_time     timestamp       DEFAULT current_timestamp NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp       DEFAULT current_timestamp NOT NULL,
    update_by       NUMBER(19),
    deleted         NUMBER(5)        DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19)          not null,
    version         NUMBER(19)          DEFAULT 0 NOT NULL,
    action          VARCHAR2(20 CHAR)     not null,
    old_status      NUMBER(5),
    new_status      NUMBER(5),
    expire_time     timestamp,
    reason          VARCHAR2(500 CHAR)    not null,
    operator_id     NUMBER(19)          not null,
    occurred_at     timestamp       not null,
    result          VARCHAR2(200 CHAR)    not null
);

create index idx_sys_tenant_status_audit_tenant_time
    on sys_tenant_status_audit (tenant_id, occurred_at);

create unique index uk_sys_tenant_status_audit_expiry
    on sys_tenant_status_audit (tenant_id, action, expire_time);
