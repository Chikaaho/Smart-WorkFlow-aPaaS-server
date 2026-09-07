-- ===================================================================
-- V58: v0.0.2 OA — 工作台布局持久化 + 新页面菜单/权限 (H2/PostgreSQL 通用方言)
-- ===================================================================
-- 1) sys_user_workspace：用户级工作台布局与常用事项（租户+用户唯一，JSON 存储）。
--    组件键白名单（todo/myInitiated/cc/favoriteItems）由服务层校验；
--    常用事项存事项稳定标识（processKey），可执行性由目录/绑定链路实时校验。
-- 2) 菜单/权限种子（幂等 WHERE NOT EXISTS，不 seed sys_role_menu，对齐 V44/V53 口径）：
--   id=320 流程中心（workflow/catalog，workflow:catalog:view）
--   id=321 抄送我的（workflow/my-cc，workflow:cc:view）
--   id=322 催办按钮（挂我发起的 id=24，workflow:urge）
--   id=323 分类/事项管理按钮（挂流程中心 id=320，workflow:catalog:manage）
--   id=324 通知发送记录（notify/record，notify:record:view）
--   id=325 通知失败重发按钮（挂发送记录 id=324，notify:record:resend）
-- ===================================================================

CREATE TABLE IF NOT EXISTS sys_user_workspace (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    user_id           bigint          not null,
    layout_json       text
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_workspace ON sys_user_workspace (tenant_id, user_id);

-- ==================== 菜单/权限种子 ====================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 320, current_timestamp, current_timestamp, 0, 0, 5, 'ProcessCatalog', '流程中心', false, 1,
       'workflow/catalog', 'workflow/views/ProcessCatalog', 'workflow:catalog:view', 'Files', 24
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 320);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 321, current_timestamp, current_timestamp, 0, 0, 5, 'WorkflowMyCc', '抄送我的', false, 1,
       'workflow/my-cc', 'workflow/views/MyCc', 'workflow:cc:view', 'Message', 25
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 321);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 322, current_timestamp, current_timestamp, 0, 0, 24, 'WorkflowUrge', '催办', false, 2, '', '', 'workflow:urge', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 322);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 323, current_timestamp, current_timestamp, 0, 0, 320, 'CatalogManage', '分类/事项管理', false, 2, '', '', 'workflow:catalog:manage', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 323);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 324, current_timestamp, current_timestamp, 0, 0, 6, 'NotifyRecord', '发送记录', false, 1,
       'record', 'notify/views/NotifyRecordList', 'notify:record:view', 'List', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 324);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 325, current_timestamp, current_timestamp, 0, 0, 324, 'NotifyRecordResend', '失败重发', false, 2, '', '', 'notify:record:resend', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 325);
