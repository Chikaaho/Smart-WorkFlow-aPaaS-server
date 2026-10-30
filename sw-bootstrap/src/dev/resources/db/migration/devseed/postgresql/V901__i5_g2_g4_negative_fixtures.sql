-- I5 复验（G2/G4/G5/G6/G7）：dev 专用负向夹具（PostgreSQL 镜像，与 h2/V901 同口径）。
-- 仅 devseed location 装载。明文密码 admin123（同 V4 散列）。
-- 租户 200：停用（status=1）——登录/装载应被拒。
insert into sys_tenant (id, create_time, update_time, deleted, tenant_id, version,
                        name, code, status, description)
values (200, current_timestamp, current_timestamp, 0, 0, 0,
        'I5停用租户', 'i5-tenant-200', 1, 'dev 夹具：停用租户（G2 负向）')
on conflict (id) do nothing;

-- 租户 300：已过期（expire_time < now）——登录/装载应被拒。
insert into sys_tenant (id, create_time, update_time, deleted, tenant_id, version,
                        name, code, status, description, expire_time)
values (300, current_timestamp, current_timestamp, 0, 0, 0,
        'I5过期租户', 'i5-tenant-300', 0, 'dev 夹具：过期租户（G2 负向）',
        '2020-01-01 00:00:00')
on conflict (id) do nothing;

insert into sys_dept (id, create_time, update_time, deleted, tenant_id, version,
                      parent_id, name, code, sort, status, description)
values (2001, current_timestamp, current_timestamp, 0, 200, 0, 0, '停用租户部门', 'i5-t200-root', 0, 0, 'dev 夹具')
on conflict (id) do nothing;
insert into sys_dept (id, create_time, update_time, deleted, tenant_id, version,
                      parent_id, name, code, sort, status, description)
values (3001, current_timestamp, current_timestamp, 0, 300, 0, 0, '过期租户部门', 'i5-t300-root', 0, 0, 'dev 夹具')
on conflict (id) do nothing;

insert into sys_user (id, create_time, update_time, deleted, tenant_id, version,
                      username, password, real_name, dept_id, status, is_admin)
values (9201, current_timestamp, current_timestamp, 0, 200, 0,
        't200admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK',
        '停用租户管理员', 2001, 0, 1)
on conflict (id) do nothing;
insert into sys_user (id, create_time, update_time, deleted, tenant_id, version,
                      username, password, real_name, dept_id, status, is_admin)
values (9301, current_timestamp, current_timestamp, 0, 300, 0,
        't300admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK',
        '过期租户管理员', 3001, 0, 1)
on conflict (id) do nothing;

-- G4：租户 100 无任何角色的用户（空权限身份）。
insert into sys_user (id, create_time, update_time, deleted, tenant_id, version,
                      username, password, real_name, dept_id, status, is_admin)
values (9101, current_timestamp, current_timestamp, 0, 100, 0,
        't100nobody', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK',
        '无角色用户', 1001, 0, 0)
on conflict (id) do nothing;

-- G7：租户 100 的 SSO 审计查询权限按钮菜单 + 角色授权（dev 夹具）。
insert into sys_menu (id, create_time, update_time, deleted, version,
                      parent_id, name, title, menu_type, path, component, permission, icon, sort, hidden)
values (910001, current_timestamp, current_timestamp, 0, 0, 0,
        'sso_audit', 'SSO审计查询', 2, '', '', 'system:sso:audit:query', '', 99, false)
on conflict (id) do nothing;

insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
values (910001, current_timestamp, current_timestamp, 0, 100, 0, 9001, 910001)
on conflict (id) do nothing;

-- G5/G6/G7：租户 100 的 WECOM Provider 配置（enabled；secret 为 AES-GCM 密文）。
insert into sys_sso_provider_config (id, create_time, update_time, deleted, tenant_id, version,
                                     provider, enabled, app_id, app_secret_enc, extra_config, redirect_path)
values (90001, current_timestamp, current_timestamp, 0, 100, 0,
        'WECOM', 1, 'ww-sentinel-app-id',
        'eDEMqN6EIcxVVGTdeVFKdTkJcLBOEMGOhgs6+am5nfe7NRKcXVhk+qvqcjin5J7+M6M=',
        '{"agentId":"1000002"}', '/workspace')
on conflict (id) do nothing;
