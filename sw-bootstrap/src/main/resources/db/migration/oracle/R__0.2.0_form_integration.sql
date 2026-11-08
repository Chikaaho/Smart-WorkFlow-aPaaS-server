-- 0.2.0 表单受控集成：能力副作用登记、写副作用幂等账本、最小调用权限。
-- 可重复执行；不保存秘密，不暴露外部 URL 给表单层。
-- Oracle：sw_agent_tool_external 由 V0.2.0 基线创建，本脚本只补 effect_scope 列与表单集成表。

ALTER TABLE sw_agent_tool_external
    add effect_scope VARCHAR2(16 CHAR);

COMMENT ON COLUMN sw_agent_tool_external.effect_scope IS '副作用分类：QUERY/WRITE；空=未分类，表单运行期不可调用';

create table sw_form_integration_invocation (
    id              VARCHAR2(36 CHAR)  NOT NULL PRIMARY KEY,
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    deleted         NUMBER(5)     DEFAULT 0 NOT NULL,
    create_time     timestamp    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    create_by       NUMBER(19),
    update_time     timestamp    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_by       NUMBER(19),
    version         NUMBER(19)       DEFAULT 0 NOT NULL,
    form_key        VARCHAR2(128 CHAR) NOT NULL,
    form_version    NUMBER(10)      NOT NULL,
    capability_id   VARCHAR2(64 CHAR)  NOT NULL,
    phase           VARCHAR2(32 CHAR)  NOT NULL,
    request_key     VARCHAR2(128 CHAR) NOT NULL,
    request_hash    VARCHAR2(64 CHAR),
    subject_id      NUMBER(19)       NOT NULL,
    status          VARCHAR2(16 CHAR)  NOT NULL,
    result_json     CLOB,
    error_message   VARCHAR2(500 CHAR),
    latency_ms      NUMBER(19)
);

create UNIQUE index uk_form_integration_invocation_identity
    ON sw_form_integration_invocation
        (tenant_id, form_key, form_version, capability_id, phase, request_key, subject_id);
create index idx_form_integration_invocation_form
    ON sw_form_integration_invocation (tenant_id, form_key, form_version);

-- 按钮级最小权限：运行期调用表单受控集成。设计器候选列表另行要求既有 agent:tool:view。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version,
                      parent_id, name, title, hidden, menu_type, path, component,
                      permission, icon, sort)
SELECT 346, current_timestamp, current_timestamp, 0, 0,
       p.id, 'FormIntegrationInvoke', '表单集成调用', 0, 2, '', '',
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
