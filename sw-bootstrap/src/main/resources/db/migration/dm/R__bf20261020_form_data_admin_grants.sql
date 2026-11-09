-- BF20261020-05：表单数据动作权限授予管理员角色（幂等可重复）。
--
-- 背景：V43/V68 只注册了 form:data:template/import/export/submit/edit/delete/query
-- 按钮权限，却没有像 V67 那样授予「管理员」角色（role_id=2）与租户管理员角色
-- （code=demo_admin）。管理员能设计/发布表单，却看不到表单数据列表与动作按钮，
-- 业务上不成立。
--
-- 口径：授权只落管理员类角色（admin / demo_admin），普通用户角色仍须经角色菜单
-- 管理显式授权；superadmin 仍按平台既有语义旁路。

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY r.id, m.id),
       current_timestamp, current_timestamp, 0, 0, r.tenant_id, r.id, m.id
FROM sys_role r
JOIN sys_menu m
  ON m.deleted = 0
 AND m.permission IN ('form:data:template', 'form:data:import', 'form:data:export',
                      'form:data:submit', 'form:data:edit', 'form:data:delete',
                      'form:data:query')
WHERE r.deleted = 0
  AND r.code IN ('admin', 'demo_admin')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.tenant_id = r.tenant_id
      AND rm.role_id = r.id
      AND rm.menu_id = m.id
      AND rm.deleted = 0
  );
