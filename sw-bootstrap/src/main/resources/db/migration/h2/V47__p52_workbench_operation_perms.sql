-- ===================================================================
-- V47: P52 表单工作台操作权限按钮
--
-- 方向：p52-form-workbench §3.5 —— 保存、发布、历史查看、流程创建和流程
-- 管理分别沿用真实权限，前端按钮可见性不能替代后端授权。
--
-- 内容：
--   1. 注册 P52 操作边界按钮权限（menu_type=2）：
--      表单侧挂表单设计菜单（permission 基线 form:design，见 V46）：
--        form:design:save     —— 建草稿/改元数据/存 config/删草稿
--        form:design:publish  —— 发布
--      流程侧挂流程定义菜单（permission 基线 workflow:def:view，见 V44）：
--        workflow:def:create
--        workflow:def:save
--        workflow:def:publish
--        workflow:def:delete
--      查看类（快照查询、流程定义列表）复用既有菜单基线码，不新增。
--   2. 将新按钮授权给既有管理员角色（role_id=2，对齐 V45 手法），
--      保证既有非超管管理员能力不变；超管经 superAdmin 短路天然放行。
--
-- 约束：
--   · 按钮菜单 id 使用 306-311（避开既有 1-305）
--   · 父菜单按 permission 定位，不硬编码父 id
--   · 全部幂等：NOT EXISTS 守卫，可安全重复执行
-- ===================================================================

-- -------------------- 表单侧按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 306, current_timestamp, current_timestamp, 0, 0, p.id, 'FormDesignSave', '表单草稿保存', false, 2, '', '', 'form:design:save', '', 21
FROM sys_menu p
WHERE p.permission = 'form:design' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'form:design:save' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 307, current_timestamp, current_timestamp, 0, 0, p.id, 'FormDesignPublish', '表单发布', false, 2, '', '', 'form:design:publish', '', 22
FROM sys_menu p
WHERE p.permission = 'form:design' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'form:design:publish' AND m.deleted = 0);

-- -------------------- 流程侧按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 308, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefCreate', '流程创建', false, 2, '', '', 'workflow:def:create', '', 11
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 309, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefSave', '流程修改', false, 2, '', '', 'workflow:def:save', '', 12
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:save' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 310, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefPublish', '流程发布', false, 2, '', '', 'workflow:def:publish', '', 13
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:publish' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 311, current_timestamp, current_timestamp, 0, 0, p.id, 'WorkflowDefDelete', '流程删除', false, 2, '', '', 'workflow:def:delete', '', 14
FROM sys_menu p
WHERE p.permission = 'workflow:def:view' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'workflow:def:delete' AND m.deleted = 0);

-- -------------------- 授权管理员角色（role_id=2） --------------------
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, role_id, menu_id)
SELECT 3000 + m.id, current_timestamp, current_timestamp, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.id IN (306, 307, 308, 309, 310, 311)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
