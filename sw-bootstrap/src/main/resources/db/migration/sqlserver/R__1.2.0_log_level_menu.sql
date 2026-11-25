-- 1.2.0 日志等级管理菜单种子（SQL Server 重复执行脚本，P41 E2）。
-- 与 R__1.0.0_audit_log_menu.sql 同因：SQL Server 用户/菜单基线在
-- R__0.2.0_sqlserver_seed.sql（可重复脚本）中，版本化脚本执行时父菜单尚未生成，
-- 菜单种子放可重复脚本保证全新安装与升级都幂等生效。
-- 独立权限点 system:loglevel:manage，查看与调整共用一点；超管经 PermissionService
-- 短路默认可见可调，默认不授任何角色，其他角色由菜单管理显式授权。
INSERT INTO [dbo].[sys_menu] ([id], [create_time], [update_time], [deleted], [version], [parent_id], [name], [title], [hidden], [menu_type], [path], [component], [permission], [icon], [sort])
SELECT 9116, SYSDATETIME(), SYSDATETIME(), 0, 0, 1, N'SystemLogLevel', N'日志等级', 0, 1, N'system/log-level', N'system/views/LogLevelList', N'system:loglevel:manage', N'Tickets', 44
WHERE NOT EXISTS (SELECT 1 FROM [dbo].[sys_menu] WHERE [id] = 9116)
  AND NOT EXISTS (SELECT 1 FROM [dbo].[sys_menu] WHERE [parent_id] = 1 AND [name] = N'SystemLogLevel' AND [deleted] = 0);
