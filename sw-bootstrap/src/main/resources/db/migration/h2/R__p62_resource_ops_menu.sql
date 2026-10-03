-- P62 资源保障与多租户公平：流程运维入口承载资源策略与积压页面（幂等可重复）。
-- 父菜单：id=5（流程引擎目录，复用现有流程运维入口，不新增外部路由）；
-- 菜单 id 沿用 P62 预留段 9100—9199（9106 策略页 / 9107 积压页 / 9108 管理按钮）；
-- 角色授权沿用 P62 预留段 9300—9399（9315—9317，role_id=2 管理员；9310—9314 已被既有 P62 菜单授权占用）。
-- 查看权限 workflow:resource:view（租户内可见自身数据）；管理权限
-- workflow:resource:manage 为独立服务端授权（跨租户运维/策略修改，不能只靠前端过滤）。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9106, current_timestamp, current_timestamp, 0, 0, 5, 'ResourcePolicyConsole', '资源策略', false, 1, 'workflow/resource-policy', 'workflow/views/ResourcePolicyConsole', 'workflow:resource:view', 'Setting', 50
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'ResourcePolicyConsole' AND parent_id = 5 AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9107, current_timestamp, current_timestamp, 0, 0, 5, 'ResourceBacklogConsole', '资源积压与配额', false, 1, 'workflow/resource-backlog', 'workflow/views/ResourceBacklogConsole', 'workflow:resource:view', 'Monitor', 60
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'ResourceBacklogConsole' AND parent_id = 5 AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9108, current_timestamp, current_timestamp, 0, 0, 9106, 'ResourcePolicyManage', '策略管理操作', false, 2, '', '', 'workflow:resource:manage', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'ResourcePolicyManage' AND parent_id = 9106 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9315, current_timestamp, current_timestamp, 0, 0, 2, 9106
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9106 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9106 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9316, current_timestamp, current_timestamp, 0, 0, 2, 9107
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9107 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9107 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9317, current_timestamp, current_timestamp, 0, 0, 2, 9108
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9108 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9108 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
