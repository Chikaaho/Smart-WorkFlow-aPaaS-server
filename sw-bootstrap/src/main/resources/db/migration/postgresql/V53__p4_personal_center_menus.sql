-- ===================================================================
-- V53: P4 OA 个人中心三入口菜单 — PostgreSQL/H2 通用方言
--
-- 变更：在「流程引擎」目录（id=5，V44 已目录化）下新增：
--   id=24 我发起的   path=workflow/my-instances  component=workflow/views/MyInstances
--   id=25 我的草稿   path=workflow/my-drafts     component=workflow/views/MyDrafts
--   id=26 我的已办   path=workflow/my-processed  component=workflow/views/MyProcessed
-- 查看权限挂目录基线 workflow:view；角色授权由管理员经角色菜单管理显式操作，
-- 本脚本不 seed sys_role_menu（对齐 V44 口径）。幂等：WHERE NOT EXISTS。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 24, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyInstances', '我发起的', false, 1,
       'workflow/my-instances', 'workflow/views/MyInstances', 'workflow:my:view', 'Document', 21
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 24);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 25, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyDrafts', '我的草稿', false, 1,
       'workflow/my-drafts', 'workflow/views/MyDrafts', 'workflow:my:view', 'EditPen', 22
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 25);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 26, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyProcessed', '我的已办', false, 1,
       'workflow/my-processed', 'workflow/views/MyProcessed', 'workflow:my:view', 'Finished', 23
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 26);
