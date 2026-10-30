-- T020 / DR02：稳定管理员关联和创建请求去重。只回填唯一、同租户的有效授权关系。
ALTER TABLE sys_tenant ADD COLUMN admin_user_id BIGINT;
ALTER TABLE sys_tenant ADD COLUMN creation_request_id VARCHAR(64);
CREATE UNIQUE INDEX uk_sys_tenant_creation_request ON sys_tenant (creation_request_id);

UPDATE sys_tenant t
SET admin_user_id = (
    SELECT MIN(u.id)
    FROM sys_user u
    JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = t.id AND ur.deleted = 0
    JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = t.id AND r.deleted = 0 AND r.status = 1
    WHERE u.tenant_id = t.id AND u.deleted = 0 AND u.status = 0
      AND ((t.id = 0 AND r.code = 'superadmin') OR (t.id <> 0 AND r.code = 'admin'))
    HAVING COUNT(DISTINCT u.id) = 1
)
WHERE t.deleted = 0;

-- 租户管理恢复为真实授权菜单；仅绑定租户 0 的超级管理员角色，不进入普通管理员模板。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9600, current_timestamp, current_timestamp, 0, 0, 1, 'SystemTenant', '租户管理', false, 1, 'system/tenant', 'system/views/TenantList', 'system:tenant:list', 'OfficeBuilding', 60
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9600)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 1 AND name = 'SystemTenant' AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT 9601, current_timestamp, current_timestamp, 0, 0, 0, r.id, 9600
FROM sys_role r
WHERE r.tenant_id = 0 AND r.code = 'superadmin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 9600 AND deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9601)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = r.id AND menu_id = 9600 AND tenant_id = 0 AND deleted = 0);
