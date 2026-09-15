-- ===================================================================
-- V71 (bpm 分链): P60 I3 人工审批与自研流程设计器 — 动作语义与生命周期表
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
ALTER TABLE sw_bpm_approval_action ADD COLUMN IF NOT EXISTS detail clob;

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
    condition_json    clob,
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
    message           clob,
    reply_message     clob,
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
    detail            clob
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
