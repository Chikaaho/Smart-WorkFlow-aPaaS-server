-- P62 首事务阶段：低代码本地事务动作（动作定义/版本/调用/预占/台账 + C1 策略）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-local-transaction-actions.md 与 ADR-P62-001。
-- 基线：V0.1.0__baseline_seed.sql（0.1.3）；本文件为基线后的前向增量迁移，新建库自动执行。

-- ==================== 1. 事务动作定义 ====================
CREATE TABLE sw_form_txn_action (
    id              VARCHAR(36)  PRIMARY KEY,
    form_id         VARCHAR(36)  NOT NULL,
    action_key      VARCHAR(100) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    action_type     VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    current_version INT,
    config_json     JSON,
    description     VARCHAR(500),
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT,
    version         BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_txn_action            IS '低代码事务动作定义（草稿/发布态）';
COMMENT ON COLUMN sw_form_txn_action.id          IS 'UUID 主键';
COMMENT ON COLUMN sw_form_txn_action.form_id     IS '关联 sw_form_def.id（动作绑定表单模型）';
COMMENT ON COLUMN sw_form_txn_action.action_key  IS '动作业务标识（租户+表单内唯一）';
COMMENT ON COLUMN sw_form_txn_action.action_type IS 'RESERVE/CONFIRM/RELEASE/ADJUST';
COMMENT ON COLUMN sw_form_txn_action.status      IS 'DRAFT(草稿)/PUBLISHED(已发布)/DISABLED(已停用)';
COMMENT ON COLUMN sw_form_txn_action.current_version IS '当前已发布版本号（未发布为空）';
COMMENT ON COLUMN sw_form_txn_action.config_json IS '动作声明配置（字段绑定/约束/预占 TTL，JSON）';

CREATE UNIQUE INDEX uk_sw_form_txn_action_key ON sw_form_txn_action (tenant_id, form_id, action_key);

-- ==================== 2. 动作发布版本（不可变快照） ====================
CREATE TABLE sw_form_txn_action_version (
    id           VARCHAR(36) PRIMARY KEY,
    action_id    VARCHAR(36) NOT NULL,
    version_no   INT         NOT NULL,
    form_version INT         NOT NULL,
    config_json  JSON       NOT NULL,
    published_by BIGINT,
    published_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id    BIGINT      NOT NULL DEFAULT 0,
    deleted      SMALLINT    NOT NULL DEFAULT 0,
    create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    BIGINT,
    update_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by    BIGINT,
    version      BIGINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_txn_action_version             IS '动作发布版本快照（不可变；已受理调用/预占固定原版本）';
COMMENT ON COLUMN sw_form_txn_action_version.action_id    IS '关联 sw_form_txn_action.id';
COMMENT ON COLUMN sw_form_txn_action_version.version_no   IS '动作版本号（发布递增）';
COMMENT ON COLUMN sw_form_txn_action_version.form_version IS '发布时绑定的表单版本号';

CREATE UNIQUE INDEX uk_sw_form_txn_action_ver ON sw_form_txn_action_version (tenant_id, action_id, version_no);

-- ==================== 3. 动作调用记录（稳定幂等身份与结果回查） ====================
CREATE TABLE sw_form_txn_invocation (
    id              VARCHAR(36)  PRIMARY KEY,
    action_id       VARCHAR(36)  NOT NULL,
    action_version  INT          NOT NULL,
    invocation_key  VARCHAR(200) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    biz_record_id   VARCHAR(64),
    status          VARCHAR(20)  NOT NULL,
    error_code      INT,
    error_msg       VARCHAR(500),
    result_json     JSON,
    duration_ms     BIGINT,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT,
    version         BIGINT       NOT NULL DEFAULT 0
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
    id                   VARCHAR(36)  PRIMARY KEY,
    action_id            VARCHAR(36)  NOT NULL,
    action_version       INT          NOT NULL,
    form_id              VARCHAR(36)  NOT NULL,
    record_id            VARCHAR(64)  NOT NULL,
    biz_keys_json        JSON,
    quantity             DECIMAL(20,6) NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    expires_at           TIMESTAMP    NOT NULL,
    reserve_invocation_id VARCHAR(36),
    settle_invocation_id  VARCHAR(36),
    settled_at           TIMESTAMP,
    tenant_id            BIGINT       NOT NULL DEFAULT 0,
    deleted              SMALLINT     NOT NULL DEFAULT 0,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by            BIGINT,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by            BIGINT,
    version              BIGINT       NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_txn_reservation                     IS '预占凭据（确认/释放/过期竞争以条件更新裁决）';
COMMENT ON COLUMN sw_form_txn_reservation.record_id            IS '目标动态宽表记录 id';
COMMENT ON COLUMN sw_form_txn_reservation.biz_keys_json        IS '声明业务键快照（物料/库位等，JSON）';
COMMENT ON COLUMN sw_form_txn_reservation.status               IS 'ACTIVE/CONFIRMED/RELEASED/EXPIRED';
COMMENT ON COLUMN sw_form_txn_reservation.expires_at           IS '预占过期时刻（过期后不可确认）';

CREATE INDEX idx_sw_form_txn_res_expire ON sw_form_txn_reservation (tenant_id, status, expires_at);
CREATE INDEX idx_sw_form_txn_res_record ON sw_form_txn_reservation (tenant_id, form_id, record_id);

-- ==================== 5. 事务台账（每次效果一行，勾稽复算） ====================
CREATE TABLE sw_form_txn_ledger (
    id              VARCHAR(36)   PRIMARY KEY,
    action_id       VARCHAR(36)   NOT NULL,
    action_version  INT           NOT NULL,
    invocation_id   VARCHAR(36)   NOT NULL,
    reservation_id  VARCHAR(36),
    entry_type      VARCHAR(20)   NOT NULL,
    form_id         VARCHAR(36)   NOT NULL,
    record_id       VARCHAR(64)   NOT NULL,
    quantity        DECIMAL(20,6) NOT NULL,
    balance_after   DECIMAL(20,6),
    reserved_after  DECIMAL(20,6),
    biz_keys_json   JSON,
    tenant_id       BIGINT        NOT NULL DEFAULT 0,
    deleted         SMALLINT      NOT NULL DEFAULT 0,
    create_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       BIGINT,
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT,
    version         BIGINT        NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_txn_ledger                 IS '事务动作台账（RESERVE/CONFIRM/RELEASE/EXPIRE/ADJUST 每效果一行）';
COMMENT ON COLUMN sw_form_txn_ledger.entry_type       IS 'RESERVE/CONFIRM/RELEASE/EXPIRE/ADJUST';
COMMENT ON COLUMN sw_form_txn_ledger.balance_after    IS '动作后余额（同事务行锁下读取，供勾稽）';
COMMENT ON COLUMN sw_form_txn_ledger.reserved_after   IS '动作后有效预占（供勾稽）';

CREATE INDEX idx_sw_form_txn_ledger_action ON sw_form_txn_ledger (tenant_id, action_id, create_time);
CREATE INDEX idx_sw_form_txn_ledger_res ON sw_form_txn_ledger (tenant_id, reservation_id);

-- ==================== 6. C1 关键数据保护策略（表单模型级声明） ====================
CREATE TABLE sw_form_c1_policy (
    id          VARCHAR(36) PRIMARY KEY,
    form_id     VARCHAR(36) NOT NULL,
    enabled     SMALLINT    NOT NULL DEFAULT 0,
    policy_json JSON,
    applied_at  TIMESTAMP,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by   BIGINT,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT,
    version     BIGINT      NOT NULL DEFAULT 0
);

COMMENT ON TABLE  sw_form_c1_policy              IS 'C1 关键数据保护策略（受保护字段/约束，随模型声明并发布冻结）';
COMMENT ON COLUMN sw_form_c1_policy.enabled       IS '1=启用 C1 保护（普通写入口拒绝受保护字段）';
COMMENT ON COLUMN sw_form_c1_policy.policy_json   IS '受保护字段列表/余额与预占字段/非负约束（JSON）';

CREATE UNIQUE INDEX uk_sw_form_c1_policy_form ON sw_form_c1_policy (tenant_id, form_id);
