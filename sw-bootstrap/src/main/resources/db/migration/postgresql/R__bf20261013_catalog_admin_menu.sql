-- BF20261013-051：事项归属管理（分类维护 + 事项归属）进管理端侧栏（幂等可重复）。
-- 背景：ProcessCatalogAdmin（/workflow/catalog-admin）此前只有静态路由、没有菜单行，
-- 管理端侧栏点不到；分类维护一度由前台流程中心的「管理分类」弹窗承担。
-- Owner 2026-10-15 决定：分类维护放到管理端 —— 前台入口撤下，管理端给出可达入口。
-- 父菜单：id=5（流程管理）；菜单 9112（P62—P64 预留段 9100—9199，9111 已被审批意见表单占用）；
-- 授权 9320（role_id=2 管理员；9300/9310—9319 已占用）。
-- 不向普通用户默认授予：授权只落管理员角色，普通角色须经角色菜单管理显式授权。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9112, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowCatalogAdmin', '事项归属管理', false, 1, 'workflow/catalog-admin', 'workflow/views/ProcessCatalogAdmin', 'workflow:catalog:manage', 'List', 27
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9112)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'WorkflowCatalogAdmin' AND parent_id = 5 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9320, current_timestamp, current_timestamp, 0, 0, 2, 9112
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9320)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9112 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9112 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
