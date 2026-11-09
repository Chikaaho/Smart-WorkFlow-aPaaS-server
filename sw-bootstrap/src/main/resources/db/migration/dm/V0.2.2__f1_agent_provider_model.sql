-- ===================================================================
-- Smart-WorkFlow :: V0.2.2 F1 阶段 1：供应商/模型两级数据模型 (PostgreSQL)
-- D022-02：供应商（名称、API 格式、地址、密钥、启停）下挂模型
--          （模型名、能力 CHAT/EMBEDDING、默认参数、启停、多Key轮换字段）
-- D022-03：旧 sw_agent_model_config 一次性迁移，id 原样带入（graph_json /
--          会话 / 执行记录中的 agentModelConfigId 无需改写即可解析到新模型行）。
--          ollama → OPENAI_CHAT + 地址修正为 OpenAI 兼容口（补 /v1）；
--          other  → OPENAI_CHAT 且供应商置停用，等待重新配置。
-- 旧表保留不删（不可逆动作，历史 id 引用兜底），职责由新表承接。
-- ===================================================================

CREATE TABLE sw_agent_provider (
    id              NUMBER(19) NOT NULL PRIMARY KEY,
    name            VARCHAR2(100) NOT NULL,
    api_format      VARCHAR2(30) NOT NULL,
    base_url        VARCHAR2(500) NOT NULL,
    api_key_cipher  CLOB,
    enabled         NUMBER(1) DEFAULT 1 NOT NULL,
    remark          VARCHAR2(500),
    create_time     TIMESTAMP,
    create_by       VARCHAR2(64),
    update_time     TIMESTAMP,
    update_by       VARCHAR2(64),
    deleted         NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19) DEFAULT 0 NOT NULL,
    version         NUMBER(19) DEFAULT 0 NOT NULL
);

CREATE UNIQUE INDEX uk_sw_agent_provider_name ON sw_agent_provider (tenant_id, name);
CREATE INDEX idx_sw_agent_provider_tenant_deleted ON sw_agent_provider (tenant_id, deleted);

COMMENT ON TABLE sw_agent_provider IS 'F1 供应商：一种 API 格式 + 地址 + 密钥';
COMMENT ON COLUMN sw_agent_provider.api_format IS 'API 格式：OPENAI_CHAT / OPENAI_RESPONSES / ANTHROPIC';
COMMENT ON COLUMN sw_agent_provider.api_key_cipher IS 'API Key 密文（AesGcmCipher），不回传明文';

CREATE TABLE sw_agent_model (
    id              NUMBER(19) NOT NULL PRIMARY KEY,
    provider_id     NUMBER(19) NOT NULL,
    name            VARCHAR2(100) NOT NULL,
    model_name      VARCHAR2(200) NOT NULL,
    capabilities    VARCHAR2(100) DEFAULT 'CHAT' NOT NULL,
    temperature     NUMBER(4,2),
    max_tokens      NUMBER(10),
    top_p           NUMBER(4,2),
    timeout_seconds NUMBER(10) DEFAULT 30 NOT NULL,
    retry_count     NUMBER(10) DEFAULT 0 NOT NULL,
    enabled         NUMBER(1) DEFAULT 1 NOT NULL,
    group_key       VARCHAR2(100),
    sort            NUMBER(10) DEFAULT 0 NOT NULL,
    locked_until    TIMESTAMP,
    quota_cooldown_seconds NUMBER(10) DEFAULT 60 NOT NULL,
    remark          VARCHAR2(500),
    create_time     TIMESTAMP,
    create_by       VARCHAR2(64),
    update_time     TIMESTAMP,
    update_by       VARCHAR2(64),
    deleted         NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id       NUMBER(19) DEFAULT 0 NOT NULL,
    version         NUMBER(19) DEFAULT 0 NOT NULL
);

CREATE UNIQUE INDEX uk_sw_agent_model_tenant_name ON sw_agent_model (tenant_id, name);
CREATE INDEX idx_sw_agent_model_td ON sw_agent_model (tenant_id, deleted);
CREATE INDEX idx_sw_agent_model_provider ON sw_agent_model (tenant_id, provider_id);
CREATE INDEX idx_sw_agent_model_grp ON sw_agent_model (tenant_id, group_key, sort);

COMMENT ON TABLE sw_agent_model IS 'F1 模型：挂在供应商下，含采样/超时/重试与多Key轮换字段';
COMMENT ON COLUMN sw_agent_model.capabilities IS '能力：CHAT / EMBEDDING（逗号分隔，预留）';
COMMENT ON COLUMN sw_agent_model.group_key IS '多Key轮询候选分组标识，null=独立配置不参与轮换';

-- ======== D022-03 旧数据迁移（id 原样带入） ========
INSERT INTO sw_agent_provider (id, name, api_format, base_url, api_key_cipher, enabled, remark,
                               create_time, create_by, update_time, update_by, deleted, tenant_id, version)
SELECT c.id,
       c.name,
       'OPENAI_CHAT',
       CASE WHEN c.protocol_type = 'ollama' AND c.base_url NOT LIKE '%/v1'
            THEN c.base_url || '/v1' ELSE c.base_url END,
       c.api_key_cipher,
       CASE WHEN c.protocol_type = 'other' THEN 0 WHEN c.enabled <> 0 THEN 1 ELSE 0 END,
       'D022-03 迁移自 sw_agent_model_config（protocol=' || c.protocol_type || '）',
       c.create_time, c.create_by, c.update_time, c.update_by, c.deleted, c.tenant_id, c.version
FROM sw_agent_model_config c;

INSERT INTO sw_agent_model (id, provider_id, name, model_name, capabilities,
                            temperature, max_tokens, top_p, timeout_seconds, retry_count,
                            enabled, group_key, sort, locked_until, quota_cooldown_seconds,
                            remark, create_time, create_by, update_time, update_by, deleted, tenant_id, version)
SELECT c.id, c.id, c.name, c.model_name, 'CHAT',
       c.temperature, c.max_tokens, c.top_p, c.timeout_seconds, c.retry_count,
       CASE WHEN c.enabled <> 0 THEN 1 ELSE 0 END, c.group_key, c.sort, c.locked_until, c.quota_cooldown_seconds,
       c.remark, c.create_time, c.create_by, c.update_time, c.update_by, c.deleted, c.tenant_id, c.version
FROM sw_agent_model_config c;

COMMENT ON TABLE sw_agent_model_config IS 'M07 大模型接入配置（0.2.2 起废弃：职责由 sw_agent_provider/sw_agent_model 承接；保留历史行以维持旧 id 引用可解析）';
