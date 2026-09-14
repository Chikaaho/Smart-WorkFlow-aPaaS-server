-- ===================================================================
-- V90 (I6): 通知管理端菜单与按钮权限种子
-- 适用 H2 与 PostgreSQL（同一迁移身份，双目录逐字节一致）
-- 在既有「通知」目录（id=6）下登记 I6 新管理/偏好页面：
--   218 通知规则   notify/views/NotifyRuleList      notify:rule:view
--   219 渠道配置   notify/views/NotifyChannelList   notify:channel:view
--   220 订阅偏好   notify/views/NotifyPreference    notify:preference
--   221 规则管理按钮   notify:rule:manage
--   222 渠道管理按钮   notify:channel:manage
-- 幂等：NOT EXISTS 防重入；未改动既有菜单。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 218, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyRule', '通知规则', false, 1, 'rule', 'notify/views/NotifyRuleList', 'notify:rule:view', 'Setting', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 218);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 219, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyChannel', '渠道配置', false, 1, 'channel', 'notify/views/NotifyChannelList', 'notify:channel:view', 'Link', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 219);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 220, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyPreference', '订阅偏好', false, 1, 'preference', 'notify/views/NotifyPreference', 'notify:preference', 'ToggleOn', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 220);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 221, current_timestamp, current_timestamp, 0, 0, 218, 'NotifyRuleManage', '规则新建/编辑/启停/删除', 2, '', '', 'notify:rule:manage', '', 1, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 221);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 222, current_timestamp, current_timestamp, 0, 0, 219, 'NotifyChannelManage', '渠道启停/重试策略', 2, '', '', 'notify:channel:manage', '', 1, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 222);
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      menu_type, path, component, permission, icon, sort, hidden)
SELECT 223, current_timestamp, current_timestamp, 0, 0, 0, 'NotifyRecordDetail', '记录必要详情（含正文与尝试流水，独立权限并审计）', 2, '', '', 'notify:record:detail', '', 2, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 223);
