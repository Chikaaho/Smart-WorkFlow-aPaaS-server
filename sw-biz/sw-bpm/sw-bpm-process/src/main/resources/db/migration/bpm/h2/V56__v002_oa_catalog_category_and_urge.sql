-- ===================================================================
-- V56: v0.0.2 OA — 流程中心分类 + 事项归属 + 催办记录 (H2)
-- ===================================================================
-- 1) sw_bpm_category：单层流程分类（名称/排序），租户隔离，逻辑删除。
-- 2) sw_bpm_process_def.category_id：事项归属分类（NULL=未分类兜底）。
-- 3) sw_bpm_urge_record：催办记录（发起人对运行中实例催办当前实际待办人）；
--    result=ACCEPTED 行的 create_time 用于 10 分钟冷却判定；
--    cooldown_key 仅 ACCEPTED 行非空，唯一索引提供并发重复受理的数据库级兜底。
-- 全部幂等（IF NOT EXISTS），可重复执行；与 PostgreSQL 版本同语义。
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
