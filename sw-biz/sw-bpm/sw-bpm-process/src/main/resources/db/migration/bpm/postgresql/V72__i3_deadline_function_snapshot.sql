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
