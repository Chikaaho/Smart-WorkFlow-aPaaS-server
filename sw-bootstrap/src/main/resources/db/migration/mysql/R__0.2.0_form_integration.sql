-- 0.2.0 表单受控集成：能力副作用登记、写副作用幂等账本、最小调用权限。
-- 可重复执行；不保存秘密，不暴露外部 URL 给表单层。
-- 存量库自足：sw_agent_tool_external 仅由基线种子在全新库创建，早期升级通道衔接的存量库
-- 可能尚未含该表；本对账按种子原文幂等补表补索引后再补列，任意受支持入口均可执行。

CREATE TABLE IF NOT EXISTS sw_agent_tool_external (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500) NOT NULL,
    input_schema    longtext,
    url             VARCHAR(500) NOT NULL,
    http_method     VARCHAR(10) NOT NULL DEFAULT 'POST',
    timeout_seconds INT NOT NULL DEFAULT 30,
    enabled         SMALLINT NOT NULL DEFAULT 1,
    remark          VARCHAR(500),
    create_time     TIMESTAMP,
    create_by       VARCHAR(64),
    update_time     TIMESTAMP,
    update_by       VARCHAR(64),
    deleted         SMALLINT NOT NULL DEFAULT 0,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0
);

SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'CREATE INDEX idx_sw_agent_tool_external_tenant_deleted ON sw_agent_tool_external (tenant_id, deleted)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sw_agent_tool_external' AND index_name = 'idx_sw_agent_tool_external_tenant_deleted');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;

SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE sw_agent_tool_external ADD COLUMN effect_scope varchar(16)', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sw_agent_tool_external' AND column_name = 'effect_scope');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;

CREATE TABLE IF NOT EXISTS sw_form_integration_invocation (
    id              varchar(36)  NOT NULL PRIMARY KEY,
    tenant_id       bigint       NOT NULL DEFAULT 0,
    deleted         smallint     NOT NULL DEFAULT 0,
    create_time     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       bigint,
    update_time     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       bigint,
    version         bigint       NOT NULL DEFAULT 0,
    form_key        varchar(128) NOT NULL,
    form_version    integer      NOT NULL,
    capability_id   varchar(64)  NOT NULL,
    phase           varchar(32)  NOT NULL,
    request_key     varchar(128) NOT NULL,
    request_hash    varchar(64),
    subject_id      bigint       NOT NULL,
    status          varchar(16)  NOT NULL,
    result_json     longtext,
    error_message   varchar(500),
    latency_ms      bigint
);

SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'ALTER TABLE sw_form_integration_invocation ADD COLUMN request_hash varchar(64)', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'sw_form_integration_invocation' AND column_name = 'request_hash');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;

SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'CREATE UNIQUE INDEX uk_form_integration_invocation_identity ON sw_form_integration_invocation (tenant_id, form_key, form_version, capability_id, phase, request_key, subject_id)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sw_form_integration_invocation' AND index_name = 'uk_form_integration_invocation_identity');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;
SET @sw_ddl := (SELECT IF(COUNT(*) = 0, 'CREATE INDEX idx_form_integration_invocation_form ON sw_form_integration_invocation (tenant_id, form_key, form_version)', 'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'sw_form_integration_invocation' AND index_name = 'idx_form_integration_invocation_form');
PREPARE sw_ddl_stmt FROM @sw_ddl;
EXECUTE sw_ddl_stmt;
DEALLOCATE PREPARE sw_ddl_stmt;

-- 按钮级最小权限：运行期调用表单受控集成。设计器候选列表另行要求既有 agent:tool:view。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version,
                      parent_id, name, title, hidden, menu_type, path, component,
                      permission, icon, sort)
SELECT 346, current_timestamp, current_timestamp, 0, 0,
       p.id, 'FormIntegrationInvoke', '表单集成调用', false, 2, '', '',
       'form:integration:invoke', '', 23
FROM sys_menu p
WHERE p.permission = 'form:design' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m
                  WHERE m.permission = 'form:integration:invoke' AND m.deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version,
                           role_id, menu_id)
SELECT COALESCE((SELECT MAX(id) FROM sys_role_menu), 0) + 1,
       current_timestamp, current_timestamp, 0, 0, 0, 1, m.id
FROM sys_menu m
WHERE m.permission = 'form:integration:invoke' AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm
                  WHERE rm.role_id = 1 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 1 AND r.deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version,
                           role_id, menu_id)
SELECT COALESCE((SELECT MAX(id) FROM sys_role_menu), 0) + 1,
       current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.permission = 'form:integration:invoke' AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm
                  WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
