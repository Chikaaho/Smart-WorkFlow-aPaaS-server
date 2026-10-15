-- P62 资源保障与多租户公平：资源策略 / 占用计数 / 拒绝审计 / 命令资源冻结字段（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-resource-assurance.md 与 ADR-P62-003。
-- 全部 IF NOT EXISTS 可重复执行；既有命令行新列保持 NULL——旧在途无资源字段不计费、不参与资源
-- 会计（方向固化的可追踪兼容规则）；新策略默认关闭（enabled=false），仅显式启用并通过启用检查后
-- 新受理才进入资源会计与额度裁决。

-- 1) 命令资源冻结字段（受理时冻结；资源策略对在途对象同构冻结，减配只影响新受理）
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS resource_class varchar(10);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS resource_units integer;
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS resource_segment varchar(20);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS policy_version integer;
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS resource_released_at timestamp;

CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_resource_open ON sw_bpm_command (resource_released_at, status);

-- 2) 资源策略（版本化；同租户同版本唯一；启用须通过启用检查并审计）
CREATE TABLE IF NOT EXISTS sw_bpm_resource_policy (
    id                          bigint        NOT NULL PRIMARY KEY,
    create_time                 timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by                   bigint,
    update_time                 timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by                   bigint,
    deleted                     smallint      NOT NULL DEFAULT 0,
    tenant_id                   bigint        NOT NULL DEFAULT 0,
    version                     bigint        NOT NULL DEFAULT 0,
    policy_version              integer       NOT NULL,
    status                      varchar(20)   NOT NULL DEFAULT 'DRAFT',
    enabled                     boolean       NOT NULL DEFAULT FALSE,
    stop_acceptance             boolean       NOT NULL DEFAULT FALSE,
    global_max_outstanding      integer       NOT NULL,
    tenant_max_outstanding      integer       NOT NULL,
    prod_reserved               integer       NOT NULL,
    oa_reserved                 integer       NOT NULL,
    shared_capacity             integer       NOT NULL,
    tenant_rate_per_sec         integer       NOT NULL,
    tenant_burst                integer       NOT NULL,
    realtime_global_concurrency integer       NOT NULL,
    realtime_tenant_concurrency integer       NOT NULL,
    batch_slice_items           integer       NOT NULL DEFAULT 25,
    batch_poll_claim_limit      integer       NOT NULL DEFAULT 1,
    remark                      varchar(500)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_resource_policy_version ON sw_bpm_resource_policy (tenant_id, policy_version);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_resource_policy_status ON sw_bpm_resource_policy (status);

-- 3) 占用计数（原子条件更新的准入加速器；权威事实=命令/批次项/引擎队列持久行，可重建勾稽。
--    跨策略版本共享同一套计数，不按版本分账——方向明确禁止「每版本各自可超额的一套计数」）
CREATE TABLE IF NOT EXISTS sw_bpm_resource_usage (
    id              bigint        NOT NULL PRIMARY KEY,
    create_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       bigint,
    update_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       bigint,
    deleted         smallint      NOT NULL DEFAULT 0,
    tenant_id       bigint        NOT NULL DEFAULT 0,
    version         bigint        NOT NULL DEFAULT 0,
    scope           varchar(16)   NOT NULL,
    scope_key       bigint        NOT NULL DEFAULT 0,
    segment         varchar(20)   NOT NULL,
    outstanding     bigint        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_resource_usage_scope ON sw_bpm_resource_usage (scope, scope_key, segment);

-- 4) 拒绝审计（REQUIRES_NEW 独立短事务写入；拒绝不产生业务效果但留可查证据）
CREATE TABLE IF NOT EXISTS sw_bpm_resource_reject_log (
    id              bigint        NOT NULL PRIMARY KEY,
    create_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       bigint,
    update_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       bigint,
    deleted         smallint      NOT NULL DEFAULT 0,
    tenant_id       bigint        NOT NULL DEFAULT 0,
    version         bigint        NOT NULL DEFAULT 0,
    policy_version  integer,
    resource_class  varchar(10),
    reject_scope    varchar(20)   NOT NULL,
    requested_units integer       NOT NULL DEFAULT 0,
    reason_code     varchar(80),
    detail          varchar(500),
    command_key     varchar(200)
);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_resource_reject_tenant_time ON sw_bpm_resource_reject_log (tenant_id, create_time);

-- 5) 全局计数种子行（幂等；固定低位 id 不与雪花 id 冲突；租户行按需惰性建立）
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910001, 0, 'GLOBAL', 0, 'TOTAL', 0 WHERE NOT EXISTS
    (SELECT 1 FROM sw_bpm_resource_usage WHERE scope = 'GLOBAL' AND scope_key = 0 AND segment = 'TOTAL');
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910002, 0, 'GLOBAL', 0, 'PROD_RESERVED', 0 WHERE NOT EXISTS
    (SELECT 1 FROM sw_bpm_resource_usage WHERE scope = 'GLOBAL' AND scope_key = 0 AND segment = 'PROD_RESERVED');
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910003, 0, 'GLOBAL', 0, 'OA_RESERVED', 0 WHERE NOT EXISTS
    (SELECT 1 FROM sw_bpm_resource_usage WHERE scope = 'GLOBAL' AND scope_key = 0 AND segment = 'OA_RESERVED');
INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, segment, outstanding)
SELECT 910004, 0, 'GLOBAL', 0, 'SHARED', 0 WHERE NOT EXISTS
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
