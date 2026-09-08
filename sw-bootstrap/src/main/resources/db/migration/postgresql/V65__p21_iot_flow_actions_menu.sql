-- ===================================================================
-- V65: P21 A6/G2a — 流程设备动作配置入口（PostgreSQL/H2 通用幂等）
--   id=337 path=iot/flow-actions component=iot/views/IotFlowActions
--   权限 workflow:def:publish（复用流程发布权限，不新增权限串）。
-- ===================================================================
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 337, current_timestamp, current_timestamp, 0, 0, 8, 'IotFlowActions', '流程设备动作', false, 1,
       'iot/flow-actions', 'iot/views/IotFlowActions', 'workflow:def:publish', 'Setting', 68
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 337);
