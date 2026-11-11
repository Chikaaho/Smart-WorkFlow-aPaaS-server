-- 1.0.0：平台审计日志（F3）+ 首次登录强制改密标记（F1-4）+ 日志查询菜单与人员导入导出按钮种子（F1-5）。
-- sys_user.must_change_password：导入/管理员新建的账号置 1，登录后被服务端拦到改密；存量账号默认 0 不受影响。
ALTER TABLE sys_user ADD COLUMN must_change_password smallint NOT NULL DEFAULT 0;

-- 审计日志：登录（LOGIN）/管理操作（OPERATION）/服务端异常（EXCEPTION）一类一表，保留天数由任务清理。
-- 不落完整堆栈与请求体；密码、令牌、密钥由写入方负责剔除。
CREATE TABLE IF NOT EXISTS sys_audit_log (
    id              BIGINT       NOT NULL PRIMARY KEY,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    log_type        VARCHAR(20)  NOT NULL,
    username        VARCHAR(64),
    user_id         BIGINT,
    result          VARCHAR(20)  NOT NULL,
    fail_reason     VARCHAR(255),
    module          VARCHAR(50),
    action          VARCHAR(50),
    target          VARCHAR(255),
    ip              VARCHAR(64),
    user_agent      VARCHAR(255),
    exception_type  VARCHAR(200),
    summary         VARCHAR(1000),
    trace_id        VARCHAR(64)
);
CREATE INDEX idx_sys_audit_log_tenant_time ON sys_audit_log (tenant_id, create_time);
CREATE INDEX idx_sys_audit_log_time ON sys_audit_log (create_time);
CREATE INDEX idx_sys_audit_log_type_time ON sys_audit_log (log_type, create_time);
COMMENT ON TABLE sys_audit_log IS '平台审计日志：登录/管理操作/服务端异常';
COMMENT ON COLUMN sys_audit_log.log_type IS '日志类型：LOGIN/OPERATION/EXCEPTION';
COMMENT ON COLUMN sys_audit_log.result IS '结果：SUCCESS/FAIL';

-- 日志查询菜单（系统管理下，用户组管理 9113 之后）。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9115, current_timestamp, current_timestamp, 0, 0, 1, 'SystemLog', '日志查询', false, 1, 'system/log', 'system/views/SystemLogList', 'system:log:list', 'Tickets', 43
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9115)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 1 AND name = 'SystemLog' AND deleted = 0);

-- 用户管理按钮：导入 / 导出（沿用基线按钮 id 段 300+，父菜单按权限定位）。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 430, current_timestamp, current_timestamp, 0, 0, p.id, 'UserImport', '用户导入', false, 2, '', '', 'system:user:import', '', 14
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:import' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 431, current_timestamp, current_timestamp, 0, 0, p.id, 'UserExport', '用户导出', false, 2, '', '', 'system:user:export', '', 15
FROM sys_menu p
WHERE p.permission = 'system:user:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:user:export' AND m.deleted = 0);

-- 授权管理员角色模板（超级管理员由既有旁路读取全部菜单）。
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY r.id),
       current_timestamp, current_timestamp, 0, r.tenant_id, 0, r.id, 9115
FROM sys_role r
WHERE r.code = 'admin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 9115 AND deleted = 0)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = 9115 AND rm.deleted = 0
  );
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY r.id),
       current_timestamp, current_timestamp, 0, r.tenant_id, 0, r.id, 430
FROM sys_role r
WHERE r.code = 'admin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 430 AND deleted = 0)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = 430 AND rm.deleted = 0
  );
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY r.id),
       current_timestamp, current_timestamp, 0, r.tenant_id, 0, r.id, 431
FROM sys_role r
WHERE r.code = 'admin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 431 AND deleted = 0)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = 431 AND rm.deleted = 0
  );
