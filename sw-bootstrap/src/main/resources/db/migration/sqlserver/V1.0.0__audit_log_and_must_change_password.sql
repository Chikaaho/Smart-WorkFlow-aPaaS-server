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
