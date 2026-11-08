-- P64 阶段Ⅱ：岗位委托管理菜单（幂等可重复）。
-- 菜单 id 沿用 P62/P63 预留段 9100—9199（页面 9110，父级=系统管理菜单 1）；
-- 角色授权沿用预留段 9300—9399（9319，role_id=2 管理员）。
-- 不向普通用户默认授予：授权只落管理员角色，普通角色须经角色菜单管理显式授权。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9110, current_timestamp, current_timestamp, 0, 0, 1, 'PostDelegate', '岗位委托', false, 1, 'post-delegate', 'system/views/PostDelegateList', 'system:postDelegate:list', 'Connection', 41
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'PostDelegate' AND parent_id = 1 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9319, current_timestamp, current_timestamp, 0, 0, 2, 9110
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9110 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9110 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
