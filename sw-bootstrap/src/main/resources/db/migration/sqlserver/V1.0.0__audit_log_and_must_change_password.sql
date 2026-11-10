-- 1.0.0：平台审计日志（F3）+ 首次登录强制改密标记（F1-4）+ 日志查询菜单与人员导入导出按钮种子（F1-5）。
-- SQL Server 自 0.4.0 终态基线后的首个真实增量。
-- sys_user.must_change_password：导入/管理员新建的账号置 1，登录后被服务端拦到改密；存量账号默认 0 不受影响。
ALTER TABLE [dbo].[sys_user] ADD [must_change_password] SMALLINT NOT NULL
    CONSTRAINT [df_sys_user_must_change_password] DEFAULT (0) WITH VALUES;

-- 审计日志：登录（LOGIN）/管理操作（OPERATION）/服务端异常（EXCEPTION）一类一表，保留天数由任务清理。
-- 不落完整堆栈与请求体；密码、令牌、密钥由写入方负责剔除。
CREATE TABLE [dbo].[sys_audit_log] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [log_type] NVARCHAR(20) NOT NULL,
    [username] NVARCHAR(64),
    [user_id] BIGINT,
    [result] NVARCHAR(20) NOT NULL,
    [fail_reason] NVARCHAR(255),
    [module] NVARCHAR(50),
    [action] NVARCHAR(50),
    [target] NVARCHAR(255),
    [ip] NVARCHAR(64),
    [user_agent] NVARCHAR(255),
    [exception_type] NVARCHAR(200),
    [summary] NVARCHAR(1000),
    [trace_id] NVARCHAR(64)
);
ALTER TABLE [dbo].[sys_audit_log] ADD CONSTRAINT [sys_audit_log_pkey] PRIMARY KEY ([id]);
CREATE INDEX [idx_sys_audit_log_tenant_time] ON [dbo].[sys_audit_log] ([tenant_id], [create_time]);
CREATE INDEX [idx_sys_audit_log_time] ON [dbo].[sys_audit_log] ([create_time]);
CREATE INDEX [idx_sys_audit_log_type_time] ON [dbo].[sys_audit_log] ([log_type], [create_time]);

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
