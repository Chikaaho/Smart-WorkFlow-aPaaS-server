-- ===================================================================
-- V61: P21 IoT 平台管理菜单 — PostgreSQL/H2 通用方言
--
-- 变更：在「物联网」目录（id=8，V6 已建）下新增 7 个管理入口：
--   330 连接配置  331 产品物模型  332 设备管理  333 Topic 配置
--   334 受控脚本  335 事件规则    336 运行记录
-- 查看权限挂 iot:view 基线；管理权限由角色菜单管理显式授权；
-- 本脚本不 seed sys_role_menu（对齐 V44/V53 口径）。幂等：WHERE NOT EXISTS。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 330, current_timestamp, current_timestamp, 0, 0, 8, 'IotConnectionList', '连接配置', false, 1,
       'iot/connections', 'iot/views/IotConnectionList', 'iot:connection:manage', 'Link', 61
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 330);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 331, current_timestamp, current_timestamp, 0, 0, 8, 'IotProductList', '产品物模型', false, 1,
       'iot/products', 'iot/views/IotProductList', 'iot:product:manage', 'Goods', 62
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 331);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 332, current_timestamp, current_timestamp, 0, 0, 8, 'IotDeviceList', '设备管理', false, 1,
       'iot/devices-manage', 'iot/views/IotDeviceList', 'iot:device:manage', 'Monitor', 63
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 332);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 333, current_timestamp, current_timestamp, 0, 0, 8, 'IotTopicList', 'Topic 配置', false, 1,
       'iot/topics', 'iot/views/IotTopicList', 'iot:topic:manage', 'ChatLineSquare', 64
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 333);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 334, current_timestamp, current_timestamp, 0, 0, 8, 'IotScriptList', '受控脚本', false, 1,
       'iot/scripts', 'iot/views/IotScriptList', 'iot:script:manage', 'EditPen', 65
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 334);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 335, current_timestamp, current_timestamp, 0, 0, 8, 'IotRuleList', '事件规则', false, 1,
       'iot/rules', 'iot/views/IotRuleList', 'iot:rule:manage', 'Bell', 66
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 335);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 336, current_timestamp, current_timestamp, 0, 0, 8, 'IotRuntimeLogs', '运行记录', false, 1,
       'iot/runtime', 'iot/views/IotRuntimeLogs', 'iot:runtime:view', 'DataLine', 67
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 336);
