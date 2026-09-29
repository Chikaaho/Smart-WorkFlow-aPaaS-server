-- ===================================================================
-- V103: sso-admin-config —— 后台 SSO 配置管理菜单入口
--
-- 系统管理组下新增「SSO 配置管理」页面菜单（租户级列表/编辑/启停/凭据/检查/
-- 审计；权限点 system:sso:config:list 由服务端 @PreAuthorize 守卫，编辑/启停/
-- 凭据更新分别以 system:sso:config:edit|enable|secret 区分）。
-- 约束：幂等（NOT EXISTS 守卫）；不 seed sys_role_menu（超管可见全量菜单，
-- 租户角色经角色管理授权，与 V97 口径一致）。
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 420, current_timestamp, current_timestamp, 0, 0, 1, 'SsoConfig', 'SSO 配置管理', false, 1, 'system/sso-config', 'system/views/SsoConfig', 'system:sso:config:list', 'Key', 65
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 420);

-- 按钮权限行（menu_type=2）：编辑/启停/凭据更新分别授权（凭据更新不隐式混入普通编辑权限）
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 421, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigEdit', 'SSO 配置编辑', false, 2, '', '', 'system:sso:config:edit', '', 10
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 421);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 422, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigEnable', 'SSO 配置启停', false, 2, '', '', 'system:sso:config:enable', '', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 422);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 423, current_timestamp, current_timestamp, 0, 0, 420, 'SsoConfigSecret', 'SSO 应用凭据更新', false, 2, '', '', 'system:sso:config:secret', '', 30
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 423);
