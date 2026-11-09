-- P63：IoT 预约下发取消独立最小权限（幂等可重复）。
-- 菜单 id 沿用 P62 预留段 9100—9199（按钮 9109，父级=设备管理菜单 332）；
-- 角色授权沿用 P62 预留段 9300—9399（9318，role_id=2 管理员）。
-- 不向普通用户默认授予：授权只落管理员角色，普通角色须经角色菜单管理显式授权。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9109, current_timestamp, current_timestamp, 0, 0, 332, 'IotReservationCancel', '设备预约取消', false, 2, '', '', 'iot:reservation:cancel', '', 5
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'IotReservationCancel' AND parent_id = 332 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9318, current_timestamp, current_timestamp, 0, 0, 2, 9109
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9109 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9109 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
