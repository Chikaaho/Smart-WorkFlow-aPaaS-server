-- I6：与 postgresql/R__i6_notify_menu_reconciliation.sql 保持同一菜单语义。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 326, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyRule', '通知规则', false, 1, 'rule', 'notify/views/NotifyRuleList', 'notify:rule:view', 'Setting', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 326);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 327, current_timestamp, current_timestamp, 0, 0, 326, 'NotifyRuleManage', '规则新建/编辑/启停/删除', false, 2, '', '', 'notify:rule:manage', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 327);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 328, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyChannel', '渠道配置', false, 1, 'channel', 'notify/views/NotifyChannelList', 'notify:channel:view', 'Link', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 328);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 329, current_timestamp, current_timestamp, 0, 0, 328, 'NotifyChannelManage', '渠道启停/重试策略', false, 2, '', '', 'notify:channel:manage', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 329);
