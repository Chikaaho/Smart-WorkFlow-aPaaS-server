-- 0.2.0 表单受控集成：能力副作用登记、写副作用幂等账本、最小调用权限。
-- 可重复执行；不保存秘密，不暴露外部 URL 给表单层。

ALTER TABLE sw_agent_tool_external
    ADD COLUMN IF NOT EXISTS effect_scope varchar(16);

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
    result_json     clob,
    error_message   varchar(500),
    latency_ms      bigint
);

ALTER TABLE sw_form_integration_invocation
    ADD COLUMN IF NOT EXISTS request_hash varchar(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_form_integration_invocation_identity
    ON sw_form_integration_invocation
        (tenant_id, form_key, form_version, capability_id, phase, request_key, subject_id);
CREATE INDEX IF NOT EXISTS idx_form_integration_invocation_form
    ON sw_form_integration_invocation (tenant_id, form_key, form_version);

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
