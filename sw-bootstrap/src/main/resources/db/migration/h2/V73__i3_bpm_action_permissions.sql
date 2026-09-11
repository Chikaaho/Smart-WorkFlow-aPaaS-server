-- ===================================================================
-- V67 (bpm 分链): P60 I3 — 审批动作与设计器权限按钮 + 角色授予
--
-- 方向：.../direction-stage-i3-manual-approval-first-party-process-designer.md §4.11
--
-- 内容：
--   挂流程定义菜单（parent=23）的 I3 动作按钮权限（menu_type=2）：
--     workflow:def:design        自研流程设计器页（设计/编辑图）
--     workflow:def:validate      草稿独立校验
--     workflow:def:suspend       发布版本挂起/激活
--   挂待办菜单（parent=20）的 I3 办理动作权限：
--     workflow:task:transfer     转办
--     workflow:task:delegate     委托
--     workflow:task:authorize    授权代理规则管理
--     workflow:task:add-sign     加签/补签
--     workflow:task:withdraw     发起人撤回
--     workflow:task:communicate  沟通征询
--     workflow:task:discard      无效实例废弃
--     workflow:task:manage       时限/催办/升级与自动动作管理
--   挂流程定义菜单（parent=23）：
--     workflow:def:publish 已有——不重复
--   普通办理（APPROVE/DISAPPROVE/RETURN/REJECT、意见录入）沿用既有
--   workflow:todo:view + 任务归属校验，不新增按钮码。
--
-- 约束：
--   · 按钮 id 使用 354-364（避开既有 1-353）
--   · 使用 sys_role_menu 结构 3600 + menu_id 移位，避开既有 3000-3499 区
--   · 越权动作的服务端授权以 @PreAuthorize + 任务归属校验为唯一权威
--   · 全部幂等：NOT EXISTS 守卫，可安全重复执行
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 354, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDesigner', '流程设计器页', false, 2,
       '', '', 'workflow:def:design', '', 21
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 354);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 355, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDefValidate', '流程草稿校验', false, 2,
       '', '', 'workflow:def:validate', '', 22
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 355);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 356, current_timestamp, current_timestamp, 0, 0, 23, 'WorkflowDefSuspend', '发布版本挂起/激活', false, 2,
       '', '', 'workflow:def:suspend', '', 23
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 356);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 357, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskTransfer', '任务转办', false, 2,
       '', '', 'workflow:task:transfer', '', 31
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 357);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 358, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskDelegate', '任务委托', false, 2,
       '', '', 'workflow:task:delegate', '', 32
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 358);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 359, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskAuthorize', '授权代理规则', false, 2,
       '', '', 'workflow:task:authorize', '', 33
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 359);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 360, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskAddSign', '任务加签/补签', false, 2,
       '', '', 'workflow:task:add-sign', '', 34
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 360);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 361, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskWithdraw', '发起人撤回', false, 2,
       '', '', 'workflow:task:withdraw', '', 35
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 361);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 362, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskCommunicate', '沟通征询', false, 2,
       '', '', 'workflow:task:communicate', '', 36
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 362);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 363, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskDiscard', '实例废弃', false, 2,
       '', '', 'workflow:task:discard', '', 37
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 363);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 364, current_timestamp, current_timestamp, 0, 0, 20, 'WorkflowTaskManage', '时限与自动动作管理', false, 2,
       '', '', 'workflow:task:manage', '', 38
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 364);

-- -------------------- 授予角色 2（管理员） --------------------
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT 3600 + m.id, current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND m.permission IN ('workflow:def:design', 'workflow:def:validate', 'workflow:def:suspend',
                       'workflow:task:transfer', 'workflow:task:delegate', 'workflow:task:authorize',
                       'workflow:task:add-sign', 'workflow:task:withdraw', 'workflow:task:communicate',
                       'workflow:task:discard', 'workflow:task:manage')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
