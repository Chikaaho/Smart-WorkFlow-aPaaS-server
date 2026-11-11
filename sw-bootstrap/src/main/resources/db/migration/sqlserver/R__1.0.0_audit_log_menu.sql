-- 1.0.0 菜单与授权种子（SQL Server 重复执行脚本）。
-- SQL Server 的用户/菜单基线在 R__0.2.0_sqlserver_seed.sql（可重复脚本）中，
-- 按 Flyway 顺序可重复脚本晚于版本化脚本执行；若把菜单/授权放在 V1.0.0，
-- 全新库首次迁移时父菜单尚不存在，按钮与授权会被静默跳过（本轮 B1 实测发现）。
-- 因此 1.0.0 的菜单、按钮与 admin 授权统一放在本可重复脚本，保证全新安装与升级都幂等生效。

-- 日志查询菜单（系统管理下，用户组管理 9113 之后）。
INSERT INTO [dbo].[sys_menu] ([id], [create_time], [update_time], [deleted], [version], [parent_id], [name], [title], [hidden], [menu_type], [path], [component], [permission], [icon], [sort])
SELECT 9115, SYSDATETIME(), SYSDATETIME(), 0, 0, 1, N'SystemLog', N'日志查询', 0, 1, N'system/log', N'system/views/SystemLogList', N'system:log:list', N'Tickets', 43
WHERE NOT EXISTS (SELECT 1 FROM [dbo].[sys_menu] WHERE [id] = 9115)
  AND NOT EXISTS (SELECT 1 FROM [dbo].[sys_menu] WHERE [parent_id] = 1 AND [name] = N'SystemLog' AND [deleted] = 0);

-- 用户管理按钮：导入 / 导出（沿用基线按钮 id 段 300+，父菜单按权限定位）。
INSERT INTO [dbo].[sys_menu] ([id], [create_time], [update_time], [deleted], [version], [parent_id], [name], [title], [hidden], [menu_type], [path], [component], [permission], [icon], [sort])
SELECT 430, SYSDATETIME(), SYSDATETIME(), 0, 0, p.[id], N'UserImport', N'用户导入', 0, 2, N'', N'', N'system:user:import', N'', 14
FROM [dbo].[sys_menu] p
WHERE p.[permission] = N'system:user:list' AND p.[deleted] = 0
  AND NOT EXISTS (SELECT 1 FROM [dbo].[sys_menu] m WHERE m.[permission] = N'system:user:import' AND m.[deleted] = 0);

INSERT INTO [dbo].[sys_menu] ([id], [create_time], [update_time], [deleted], [version], [parent_id], [name], [title], [hidden], [menu_type], [path], [component], [permission], [icon], [sort])
SELECT 431, SYSDATETIME(), SYSDATETIME(), 0, 0, p.[id], N'UserExport', N'用户导出', 0, 2, N'', N'', N'system:user:export', N'', 15
FROM [dbo].[sys_menu] p
WHERE p.[permission] = N'system:user:list' AND p.[deleted] = 0
  AND NOT EXISTS (SELECT 1 FROM [dbo].[sys_menu] m WHERE m.[permission] = N'system:user:export' AND m.[deleted] = 0);

-- 授权管理员角色模板（超级管理员由既有旁路读取全部菜单）。
INSERT INTO [dbo].[sys_role_menu] ([id], [create_time], [update_time], [deleted], [tenant_id], [version], [role_id], [menu_id])
SELECT (SELECT COALESCE(MAX([id]), 0) FROM [dbo].[sys_role_menu]) + ROW_NUMBER() OVER (ORDER BY r.[id]),
       SYSDATETIME(), SYSDATETIME(), 0, r.[tenant_id], 0, r.[id], 9115
FROM [dbo].[sys_role] r
WHERE r.[code] = N'admin' AND r.[deleted] = 0
  AND EXISTS (SELECT 1 FROM [dbo].[sys_menu] WHERE [id] = 9115 AND [deleted] = 0)
  AND NOT EXISTS (
    SELECT 1 FROM [dbo].[sys_role_menu] rm
    WHERE rm.[role_id] = r.[id] AND rm.[menu_id] = 9115 AND rm.[deleted] = 0
  );
INSERT INTO [dbo].[sys_role_menu] ([id], [create_time], [update_time], [deleted], [tenant_id], [version], [role_id], [menu_id])
SELECT (SELECT COALESCE(MAX([id]), 0) FROM [dbo].[sys_role_menu]) + ROW_NUMBER() OVER (ORDER BY r.[id]),
       SYSDATETIME(), SYSDATETIME(), 0, r.[tenant_id], 0, r.[id], 430
FROM [dbo].[sys_role] r
WHERE r.[code] = N'admin' AND r.[deleted] = 0
  AND EXISTS (SELECT 1 FROM [dbo].[sys_menu] WHERE [id] = 430 AND [deleted] = 0)
  AND NOT EXISTS (
    SELECT 1 FROM [dbo].[sys_role_menu] rm
    WHERE rm.[role_id] = r.[id] AND rm.[menu_id] = 430 AND rm.[deleted] = 0
  );
INSERT INTO [dbo].[sys_role_menu] ([id], [create_time], [update_time], [deleted], [tenant_id], [version], [role_id], [menu_id])
SELECT (SELECT COALESCE(MAX([id]), 0) FROM [dbo].[sys_role_menu]) + ROW_NUMBER() OVER (ORDER BY r.[id]),
       SYSDATETIME(), SYSDATETIME(), 0, r.[tenant_id], 0, r.[id], 431
FROM [dbo].[sys_role] r
WHERE r.[code] = N'admin' AND r.[deleted] = 0
  AND EXISTS (SELECT 1 FROM [dbo].[sys_menu] WHERE [id] = 431 AND [deleted] = 0)
  AND NOT EXISTS (
    SELECT 1 FROM [dbo].[sys_role_menu] rm
    WHERE rm.[role_id] = r.[id] AND rm.[menu_id] = 431 AND rm.[deleted] = 0
  );
