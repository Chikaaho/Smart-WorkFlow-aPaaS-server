-- 0.2.0 T020：用户名按租户唯一（DR02），登录解析见 LoginUserResolver
DROP INDEX IF EXISTS uk_sys_user_username;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_tenant_username ON sys_user (tenant_id, username, deleted);

