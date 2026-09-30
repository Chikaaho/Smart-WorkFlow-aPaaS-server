-- P62 分级执行 S4：设备命令独立授权人工核实最小权限（幂等可重复）。
-- 菜单 id 沿用 P62 预留段 9100—9199（按钮 9104，父级=设备管理菜单 332）；
-- 角色授权沿用 P62 预留段 9300—9399（9310，role_id=2 管理员）。
-- 不向普通用户默认授予：授权只落管理员角色，普通角色须经角色菜单管理显式授权。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9104, current_timestamp, current_timestamp, 0, 0, 332, 'IotCommandManualVerify', '设备命令人工核实', false, 2, '', '', 'iot:command:verify', '', 4
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'IotCommandManualVerify' AND parent_id = 332 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9310, current_timestamp, current_timestamp, 0, 0, 2, 9104
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9104 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9104 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

-- P62 复核01 G6：设备管理菜单（332，iot:device:manage）授权管理员角色 role 2——
-- 该菜单在 baseline 种子中未对 role 2 授权，设备管理页对管理员 403（错误横幅根因）。
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9313, current_timestamp, current_timestamp, 0, 0, 2, 332
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 332 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 332 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
