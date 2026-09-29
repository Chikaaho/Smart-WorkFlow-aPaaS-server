-- ===================================================================
-- Smart-WorkFlow :: BPM 模块元数据表 (H2)
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
-- H2 注意：
--   无 COMMENT ON 支持
--   H2 不支持 PostgreSQL 的 partial unique index（WHERE 条件索引），
--   故采用生成列 active_key + 唯一索引等价实现「仅 active=true 唯一」语义：
--   active=true 时 active_key = 'tenant_id:form_key'（非空 → 唯一约束生效），
--   active=false 时 active_key = NULL（H2 唯一索引允许多个 NULL 共存）。
-- ===================================================================

-- ==================== 1. 表单↔流程绑定表 ====================
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
    active            boolean         not null default true,
    active_key        varchar(265)    generated always as (case when active then (cast(tenant_id as varchar(64)) || ':' || form_key) end)
);

-- 生成列唯一索引：等价于 PG 的 partial unique index（tenant_id, form_key) 条件仅限 active=true
create unique index uk_sw_bpm_binding_active on sw_bpm_form_binding (active_key);

-- ==================== 2. 流程实例记录表 ====================
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

create index idx_sw_bpm_inst_process_inst on sw_bpm_instance (process_instance_id);
create index idx_sw_bpm_inst_business_key on sw_bpm_instance (business_key);
create index idx_sw_bpm_inst_tenant_status on sw_bpm_instance (tenant_id, status);
