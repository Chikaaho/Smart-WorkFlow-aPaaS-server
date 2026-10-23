-- U020：用户组管理菜单与管理员操作权限（幂等可重复）。
-- 菜单 id 使用系统管理预留段；关系 id 按现有最大值递增，避免固定 id 与多租户角色冲突。
-- 只授权管理员角色模板；超级管理员仍由既有旁路读取全部菜单。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9113, current_timestamp, current_timestamp, 0, 0, 1, 'SystemUserGroup', '用户组管理', false, 1, 'system/user-group', 'system/views/UserGroupList', 'system:userGroup:list', 'UserFilled', 42
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9113)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 1 AND name = 'SystemUserGroup' AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9114, current_timestamp, current_timestamp, 0, 0, 9113, 'SystemUserGroupManage', '管理用户组', true, 2, NULL, NULL, 'system:userGroup:manage', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9114)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 9113 AND name = 'SystemUserGroupManage' AND deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 9113 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY r.id),
       current_timestamp, current_timestamp, 0, r.tenant_id, 0, r.id, 9113
FROM sys_role r
WHERE r.code = 'admin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 9113 AND deleted = 0)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = 9113 AND rm.deleted = 0
  );
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY r.id),
       current_timestamp, current_timestamp, 0, r.tenant_id, 0, r.id, 9114
FROM sys_role r
WHERE r.code = 'admin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 9114 AND deleted = 0)
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = 9114 AND rm.deleted = 0
  );
