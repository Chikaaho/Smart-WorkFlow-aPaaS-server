-- P62 首事务阶段：事务动作管理菜单与权限按钮（幂等可重复）。
-- 父菜单：id=2（表单目录）；菜单 330 + 按钮 331/332/333；对齐 V47 授权手法（role_id=2）。
-- 前端组件 form/views/TxnActionList（可被菜单 glob 命中）。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 330, current_timestamp, current_timestamp, 0, 0, 2, 'FormTxnAction', '事务动作', false, 1, 'txn-action', 'form/views/TxnActionList', 'form:action:view', 'Operation', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 330);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 331, current_timestamp, current_timestamp, 0, 0, 330, 'FormTxnActionManage', '动作新建/编辑/启停', false, 2, '', '', 'form:action:manage', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 331);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 332, current_timestamp, current_timestamp, 0, 0, 330, 'FormTxnActionPublish', '动作发布校验', false, 2, '', '', 'form:action:publish', '', 2
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 332);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 333, current_timestamp, current_timestamp, 0, 0, 330, 'FormTxnActionInvoke', '动作调用与结果查看', false, 2, '', '', 'form:action:invoke', '', 3
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 333);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 3000 + m.id, current_timestamp, current_timestamp, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.id IN (330, 331, 332, 333)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
