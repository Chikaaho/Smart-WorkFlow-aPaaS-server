-- 0.1.5 发布收敛：存量库升级脚本（h2，自 legacy 0.1.1 状态升级）。
-- 适用：flyway_schema_history 的 versioned 终点为 V0.1.1（如 UAT 2026-10-13 巡检所见状态，执行前须现场核实）。
-- 内容 = 旧链增量 V0.1.2—V0.1.10（各文件段头注明来源）+ 历史衔接（删除 0.1.0—0.1.10 记录，
--        写入 V0.1.5__baseline_seed.sql 成功记录，checksum=1778504403）。
-- 边界：只处理 version 非空的历史记录；R__ 可重复迁移记录与业务数据不动。
-- 要求：执行前完成数据库备份；本脚本恰好运行一次（增量段部分语句无 IF NOT EXISTS 守卫，
--        重复执行会在首个无守卫语句处显式失败——这是防重复执行的保护行为，不是缺陷）。
-- 事务：增量 DDL 段逐文件提交由执行方数据库客户端决定；历史衔接段在单事务内执行。

-- ======== 来源: V0.1.2__tiered_command_semantics.sql =======
-- P62 分级执行与统一命令：命令统一身份 / 准入截止 / 效果权威账本（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-tiered-execution-unified-command.md 与 ADR-P62-002。
-- 全部 IF NOT EXISTS 可重复执行；既有行新列保持 NULL，旧语义与旧消费者不受影响。

-- 1) 统一逻辑命令身份与载荷指纹（跨通道复用同一操作身份；允许由既有物理记录映射）
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS logical_command_id varchar(200);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS payload_fingerprint varchar(64);

-- 2) 受理时冻结的形态/完成点/准入截止（改配置只影响新受理对象）
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS tier varchar(30);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS completion_point varchar(200);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS deadline_at timestamp;
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS overdue_at timestamp;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_command_logical ON sw_bpm_command (tenant_id, logical_command_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_deadline ON sw_bpm_command (status, deadline_at);

-- 3) 效果权威账本（sw_bpm_command_effect）：与业务效果同事务写入；
--    命令未完成（如提交后崩溃/完成记录未写）时，恢复路径据本表确定结果，不再猜测。
CREATE TABLE IF NOT EXISTS sw_bpm_command_effect (
    command_id          bigint       NOT NULL PRIMARY KEY,
    logical_command_id  varchar(200),
    claim_token         varchar(64),
    result_json         text,
    biz_ref             varchar(200),
    tenant_id           bigint       NOT NULL DEFAULT 0,
    create_time         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_effect_logical ON sw_bpm_command_effect (logical_command_id);

-- ======== 来源: V0.1.3__batch_command.sql =======
-- P62 分级执行与统一命令：后台批量（S3）——批次受理 / 稳定项键 / 逐项持久结果（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-tiered-execution-unified-command.md（U02/U07）。
-- 全部 IF NOT EXISTS 可重复执行；批次重放返回原批次，同项同键异载荷拒绝，重复消费不重做已成功项。

-- 1) 批次受理（同租户批次键唯一：重放返回原批次）
CREATE TABLE IF NOT EXISTS sw_bpm_command_batch (
    id              bigint        NOT NULL PRIMARY KEY,
    create_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       bigint,
    update_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       bigint,
    deleted         smallint      NOT NULL DEFAULT 0,
    tenant_id       bigint        NOT NULL DEFAULT 0,
    version         bigint        NOT NULL DEFAULT 0,
    batch_key       varchar(128)  NOT NULL,
    action_id       varchar(64)   NOT NULL,
    action_version  integer,
    status          varchar(20)   NOT NULL DEFAULT 'PENDING',
    total_count     integer       NOT NULL DEFAULT 0,
    succeeded_count integer       NOT NULL DEFAULT 0,
    failed_count    integer       NOT NULL DEFAULT 0,
    command_id      bigint,
    initiator_id    bigint
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_command_batch_key ON sw_bpm_command_batch (tenant_id, batch_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_batch_cmd ON sw_bpm_command_batch (command_id);

-- 2) 批次项（批次内稳定项键唯一；逐项独立事务与持久结果，可独立追踪与恢复）
CREATE TABLE IF NOT EXISTS sw_bpm_command_batch_item (
    id              bigint        NOT NULL PRIMARY KEY,
    create_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       bigint,
    update_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       bigint,
    deleted         smallint      NOT NULL DEFAULT 0,
    tenant_id       bigint        NOT NULL DEFAULT 0,
    version         bigint        NOT NULL DEFAULT 0,
    batch_id        bigint        NOT NULL,
    item_key        varchar(128)  NOT NULL,
    record_id       varchar(64),
    quantity        varchar(40),
    status          varchar(20)   NOT NULL DEFAULT 'PENDING',
    invocation_id   varchar(64),
    error_code      integer,
    error_msg       varchar(500),
    attempt_count   integer       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_command_batch_item_key ON sw_bpm_command_batch_item (batch_id, item_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_batch_item_status ON sw_bpm_command_batch_item (batch_id, status);


-- ======== 来源: V0.1.4__resource_assurance.sql =======
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

-- ======== 来源: V0.1.5__p63_dynamic_branch_semantics.sql =======
-- V0.1.5 (P63): 动态并行语义 v2。
-- 语义版本 2：按对象身份生成分支（USER=人、DEPT=部门→负责人），同负责人不同部门保持独立分支；
-- 轮次以多实例根 executionId 标识（同 execution 恢复复用、退回重入新轮次，旧轮次行保留）；
-- source_refs 保留全部来源位置（表格行 id / 字段引用）。
-- semantic_version IS NULL = 旧语义行（按负责人合并），行为不变。
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN semantic_version SMALLINT;
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN object_type VARCHAR(16);
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN object_id VARCHAR(64);
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN round_no BIGINT;
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN execution_id VARCHAR(64);
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN source_refs TEXT;
DROP INDEX IF EXISTS uk_sw_bpm_dyn_branch;
CREATE UNIQUE INDEX uk_sw_bpm_dyn_branch
    ON sw_bpm_dynamic_branch (tenant_id, process_instance_id, node_key, round_no, branch_index);

-- ======== 来源: V0.1.6__p63_iot_command_reservation.sql =======
-- V0.1.6 (P63): 流程成功结束后的一次性 IoT 预约意图。
-- 冻结租户/定义/实例/表单记录/设备目标/命令与参数/预约时刻（显式时区）/迟到窗口；
-- 状态机：PENDING → DISPATCHING → DISPATCHED；CANCELED / EXPIRED / FAILED 为确定终态。
-- 同一租户同一流程实例至多一条预约意图（同一次成功完成的幂等身份）。
create table sw_iot_command_reservation (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    process_def_key varchar(128),
    def_version int,
    form_key varchar(128),
    record_id varchar(36),
    device_key varchar(128),
    product_id varchar(128),
    device_name varchar(128),
    command_key varchar(128),
    command_type varchar(16),
    payload text,
    due_at_utc timestamp not null,
    timezone_id varchar(64) not null,
    due_local_text varchar(64),
    late_window_seconds int not null,
    status varchar(20) not null,
    command_id bigint,
    reject_reason varchar(512),
    cancel_by bigint,
    cancel_reason varchar(512),
    cancel_time timestamp
);
create index idx_sw_iot_reservation_status on sw_iot_command_reservation (status, due_at_utc);
create unique index uk_sw_iot_reservation_instance on sw_iot_command_reservation (tenant_id, process_instance_id);

-- ======== 来源: V0.1.7__p64_orchestration_data_action.sql =======
-- V0.1.7 (P64 阶段Ⅰ 数据到动作): 节点业务表单 / Trigger 判断 / 关联动作三张新表。
-- 全部为追加新表，不 ALTER 既有表；旧图（无 variables/triggers/节点表单绑定）零行为，
-- 旧定义与运行实例按其 def_version 冻结图运行（ADR-P64-001 §6）。
-- 回退：新代码回滚后旧代码不读写三张新表，数据保留，不通过破坏性 DDL 删除事实。

-- 1) 任务级节点业务表单数据：一行 = 一个任务的数据身份（ADR-P64-001 §1）。
-- status: DRAFT / SUBMITTED；仅合法最终提交（APPROVE/DISAPPROVE 同事务）进入 SUBMITTED，
-- REJECT/RETURN 不产生有效提交；round_no = 实例 RETURN 动作数 + 1（退回前旧轮不混入新轮读取）。
create table sw_bpm_task_form_data (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    process_def_key varchar(128),
    node_key varchar(200) not null,
    task_id varchar(64) not null,
    round_no bigint not null default 0,
    form_key varchar(128) not null,
    form_version bigint not null,
    status varchar(16) not null,
    data_text text,
    submitted_by bigint,
    submit_time timestamp
);
create unique index uk_sw_bpm_task_form_data on sw_bpm_task_form_data (tenant_id, task_id);
create index idx_sw_bpm_task_form_data_instance on sw_bpm_task_form_data (tenant_id, process_instance_id, node_key, round_no);

-- 2) Trigger 判断执行记录：一次评估一行（ADR-P64-001 §3）。
-- status: MATCHED / UNMATCHED / FAILED；disposition: EMPTY_COLLECTION / OVER_LIMIT /
-- BLOCK / IGNORE；snapshot_text 为本次判断共用的一致数据快照（≤1MiB）；可诊断、不冒充成功。
create table sw_bpm_trigger_exec (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    process_def_key varchar(128),
    def_version int,
    trigger_id varchar(64) not null,
    event_type varchar(32) not null,
    node_key varchar(200),
    round_no bigint not null default 0,
    exec_key varchar(200) not null,
    status varchar(20) not null,
    result_value varchar(512),
    result_type varchar(16),
    matched_branch_id varchar(64),
    disposition varchar(32),
    error_text text,
    snapshot_text text,
    duration_ms bigint
);
create unique index uk_sw_bpm_trigger_exec on sw_bpm_trigger_exec (tenant_id, exec_key);
create index idx_sw_bpm_trigger_exec_instance on sw_bpm_trigger_exec (tenant_id, process_instance_id);

-- 3) 动作意图与目标实例关联：一次派发项一行（ADR-P64-001 §4）。
-- status: INTENT_SUBMITTED / STARTED / FAILED；command_key 与 sw_bpm_command 幂等键一致；
-- target_record_id/target_instance_id 由消费事务回填，构成 业务单据→触发→动作→关联实例 回查链。
create table sw_bpm_action_ref (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    exec_id bigint not null,
    process_instance_id varchar(64) not null,
    trigger_id varchar(64),
    action_id varchar(64) not null,
    action_type varchar(24) not null,
    item_key varchar(128) not null,
    item_summary text,
    payload_json text,
    command_key varchar(200) not null,
    command_id bigint,
    target_def_key varchar(128) not null,
    target_form_key varchar(128),
    target_record_id varchar(64),
    target_instance_id varchar(64),
    status varchar(20) not null,
    error_text text
);
create unique index uk_sw_bpm_action_ref on sw_bpm_action_ref (tenant_id, command_key);
create index idx_sw_bpm_action_ref_instance on sw_bpm_action_ref (tenant_id, process_instance_id);
create index idx_sw_bpm_action_ref_exec on sw_bpm_action_ref (tenant_id, exec_id);

-- ======== 来源: V0.1.8__p64_phase2_parent_child.sql =======
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
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    batch_key varchar(240) not null,
    parent_instance_id varchar(64) not null,
    parent_def_key varchar(128),
    root_instance_id varchar(64),
    parent_depth int not null default 0,
    trigger_id varchar(64) not null,
    action_id varchar(64) not null,
    round_no bigint not null default 0,
    wait_policy varchar(16) not null,
    wait_count int,
    expected_count int not null default 0,
    source_record_id varchar(64),
    source_record_version bigint,
    status varchar(20) not null,
    settled_count int not null default 0,
    block_reason text,
    settled_at timestamp,
    config_json text
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
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    batch_id bigint not null,
    item_key varchar(128) not null,
    action_ref_id bigint,
    source_row_id varchar(64),
    source_row_version bigint,
    source_rows_json text,
    source_summary text,
    target_def_key varchar(128),
    target_form_key varchar(128),
    target_record_id varchar(64),
    target_instance_id varchar(64),
    status varchar(20) not null,
    writeback_json text,
    writeback_time timestamp,
    writeback_source varchar(200),
    error_text text
);
create unique index uk_sw_bpm_child_item on sw_bpm_child_item (tenant_id, batch_id, item_key);
create index idx_sw_bpm_child_item_target on sw_bpm_child_item (tenant_id, target_record_id);
create index idx_sw_bpm_child_item_batch on sw_bpm_child_item (tenant_id, batch_id);

-- ======== 来源: V0.1.9__p64_position_delegate.sql =======
-- V0.1.9 (P64 阶段Ⅱ 人员与父子协作): 后台源岗位→受托岗位委托关系表（组织域通用配置）。
-- 追加新表，不 ALTER 既有表；未配置委托关系时岗位解析沿 P63 原义（源岗位有效人员）。
-- scope_type: ORG（显式组织默认范围）/ DEPT（精确部门范围，优先于 ORG）；dept_id 仅 DEPT 有效。
-- status: ENABLED / DISABLED（启停、审计；每个源岗位/适用范围最多一条有效关系，
--         同优先级重叠由服务层配置校验拒绝——自委托/循环/跨租户/越权映射同样在配置与生效时拒绝）。
-- 回退：新代码回滚后旧代码不读写本表，配置数据保留，不通过破坏性 DDL 删除事实。
create table sys_post_delegate (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    source_post_id bigint not null,
    target_post_id bigint not null,
    scope_type varchar(16) not null,
    dept_id bigint,
    status varchar(16) not null,
    remark varchar(500)
);
create index idx_sys_post_delegate_source on sys_post_delegate (tenant_id, source_post_id, status);
create index idx_sys_post_delegate_dept on sys_post_delegate (tenant_id, dept_id);

-- ======== 来源: V0.1.10__p64_approval_form_type.sql =======
-- BF20261013-020：低代码表单分类型（元数据区分）。
-- form_type：BUSINESS（业务表单，存量默认）/ APPROVAL_COMMENT（审批意见表单，流程节点审批意见专用，
-- 在「流程管理 → 审批意见表单」单独维护；流程节点选择器只列该类型）。

ALTER TABLE sw_form_def ADD COLUMN form_type VARCHAR(20) NOT NULL DEFAULT 'BUSINESS';

COMMENT ON COLUMN sw_form_def.form_type IS '表单类型：BUSINESS(业务表单)/APPROVAL_COMMENT(审批意见表单，流程节点专用)';


-- ======== 历史衔接：versioned 记录收敛为 V0.1.5 单条 ========
BEGIN;

DELETE FROM "flyway_schema_history" WHERE "version" IS NOT NULL;

INSERT INTO "flyway_schema_history" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success")
SELECT COALESCE(MAX("installed_rank"), 0) + 1,
       '0.1.5', 'baseline seed', 'SQL', 'V0.1.5__baseline_seed.sql', 1778504403,
       USER, CURRENT_TIMESTAMP, 0, TRUE
FROM "flyway_schema_history";

COMMIT;
