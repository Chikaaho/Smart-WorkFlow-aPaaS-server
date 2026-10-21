-- 应用中心与表单分类统一：后台「应用管理」+ 用户端「应用中心」菜单（幂等可重复）。
-- 背景：后台应用管理（/form/app-manage）与用户端应用中心（/form/app-center）
-- 原为静态路由直达，无菜单行；本脚本给出可达入口并对账既有授权。
-- 菜单 id 段：P62—P64 预留 9100—9199（已占 9100—9112、9199 边界；本脚本用 9130/9131）；
-- 授权 id 段 9300—9399（已占 9300、9310—9321、9399；本脚本用 9330—9333）。
-- 可见范围应用（见 V20261015190000）中手动配置；后台管理权限 form:app:manage。
-- 不向普通用户默认授予：授权只落管理员角色（role 2）/系统管理员角色（role 1），
-- 普通角色须经角色菜单管理显式授权；用户端应用中心页面行为按可见范围校验。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9130, current_timestamp, current_timestamp, 0, 0, 2, 'FormAppManage', '应用管理', false, 1, 'form/app-manage', 'form/views/AppManageList', 'form:app:manage', 'Grid', 25
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9130)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'FormAppManage' AND parent_id = 2 AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9131, current_timestamp, current_timestamp, 0, 0, 2, 'FormAppCenter', '应用中心', false, 1, 'form/app-center', 'form/views/AppCenter', NULL, 'Menu', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9131)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'FormAppCenter' AND parent_id = 2 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9330, current_timestamp, current_timestamp, 0, 0, 2, 9130
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9330)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9130 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9130 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9331, current_timestamp, current_timestamp, 0, 0, 1, 9130
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9331)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = 9130 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9130 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 1 AND r.deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9332, current_timestamp, current_timestamp, 0, 0, 2, 9131
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9332)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9131 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9131 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9333, current_timestamp, current_timestamp, 0, 0, 1, 9131
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9333)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = 9131 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9131 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 1 AND r.deleted = 0);

-- 0.1.7 #6 菜单口径对账（幂等）：应用管理（9130）上提顶级、排序 15（系统管理 10 与
-- 表单管理 20 之间的第二项）。该口径原写在 V0.1.7 基线内的 UPDATE，但全新库上
-- 9130 由本脚本后置插入，V 阶段 UPDATE 空转，导致新装环境应用管理残留在表单管理
-- 下；此处随插入一并收敛，存量库已正确的不再变化。

update sys_menu
set parent_id = 0, sort = 15
where id = 9130
  and deleted = 0
  and (parent_id <> 0 or sort <> 15);
