-- ===================================================================
-- V70 (bpm 分链): P60 I3 人工审批与自研流程设计器 — 定义版本与发布冻结
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
    function_versions     clob,
    graph_json            clob,
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
