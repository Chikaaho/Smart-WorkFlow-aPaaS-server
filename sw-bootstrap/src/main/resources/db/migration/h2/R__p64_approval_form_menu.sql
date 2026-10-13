-- BF20261013-020：审批意见表单维护菜单（幂等可重复）。
-- 父菜单：id=5（流程管理）；菜单 9111（P62—P64 预留段 9100—9199）；授权 9312（role_id=2 管理员；9311 已被 P62 交易批次占用）。
-- 前端组件 workflow/views/ApprovalFormList（可被菜单 glob 命中）。
-- 不向普通用户默认授予：授权只落管理员角色，普通角色须经角色菜单管理显式授权。

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9111, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowApprovalForm', '审批意见表单', false, 1, 'workflow/approval-form', 'workflow/views/ApprovalFormList', 'workflow:approvalForm:list', 'Document', 40
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9111)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'WorkflowApprovalForm' AND parent_id = 5 AND deleted = 0);

-- 既有库纠正：早期种子误挂 id=7（智能体）且 path 缺 workflow/ 前缀，此处前向对齐（幂等）。
UPDATE sys_menu SET parent_id = 5, path = 'workflow/approval-form', update_time = current_timestamp
WHERE id = 9111 AND deleted = 0 AND (parent_id <> 5 OR path <> 'workflow/approval-form');

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 9312, current_timestamp, current_timestamp, 0, 0, 2, 9111
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9312)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = 9111 AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = 9111 AND m.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
