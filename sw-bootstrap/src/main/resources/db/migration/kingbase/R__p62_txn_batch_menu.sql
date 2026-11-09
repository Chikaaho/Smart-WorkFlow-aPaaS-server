-- P62 分级执行 S5：后台批量调用控制台菜单（幂等可重复）。
-- 父菜单：id=2（表单目录，与事务动作管理同域）；菜单 9105（页面，component=workflow/views/TxnBatchConsole）。
-- 权限码与批量受理/回查一致（form:action:invoke，与受控动作调用同口径）；
-- 角色授权沿用 P62 预留段 9300—9399（9311，role_id=2 管理员）。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9105, current_timestamp, current_timestamp, 0, 0, 2, 'TxnBatchConsole', '后台批量调用', false, 1, 'txn-batch', 'workflow/views/TxnBatchConsole', 'form:action:invoke', 'Operation', 41
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'TxnBatchConsole' AND parent_id = 2 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9311, current_timestamp, current_timestamp, 0, 0, 2, 9105
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9105 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9105 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
