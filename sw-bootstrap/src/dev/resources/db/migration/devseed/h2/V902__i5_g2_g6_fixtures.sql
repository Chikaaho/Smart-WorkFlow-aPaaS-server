-- I5 复验 03（G2a/G2b）：dev 专用负向夹具第二批（仅 devseed location 装载）。
-- OpenAPI 应用：secret 仅存 SHA-256 摘要（哨兵明文不出现在任何仓库文件）。
-- app 绑定租户 100（有效）——签名正反与租户有效链。
merge into sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version,
                           app_id, app_name, secret_hash, scopes, status, act_as_user_id)
key (id)
values (9001, current_timestamp, current_timestamp, 0, 100, 0,
        'i5-openapi-t100', 'I5复验应用-租户100',
        '804f665030d380832588ee486b27f601ddfb70e47db575ccc6215b8a2c928cc5',
        'PROCESS_START,PROCESS_QUERY,TASK_HANDLE', 'ENABLED', 9001);

-- app 绑定租户 300（已过期）——签名合法但租户无效必须 fail closed（G2a）。
merge into sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version,
                           app_id, app_name, secret_hash, scopes, status, act_as_user_id)
key (id)
values (9002, current_timestamp, current_timestamp, 0, 300, 0,
        'i5-openapi-t300', 'I5复验应用-过期租户',
        '804f665030d380832588ee486b27f601ddfb70e47db575ccc6215b8a2c928cc5',
        'PROCESS_START,PROCESS_QUERY,TASK_HANDLE', 'ENABLED', 9301);

-- 租户 1000：120 秒后过期（G2b 会话收敛时间序列：先登录成功，到期后全链拒绝）。
merge into sys_tenant (id, create_time, update_time, deleted, tenant_id, version,
                       name, code, status, description, expire_time)
key (id)
values (1000, current_timestamp, current_timestamp, 0, 0, 0,
        'I5限时租户', 'i5-tenant-1000', 0, 'dev 夹具：登录后 2 分钟过期（G2b）',
        dateadd(second, 180, current_timestamp));

merge into sys_dept (id, create_time, update_time, deleted, tenant_id, version,
                     parent_id, name, code, sort, status, description)
key (id)
values (4001, current_timestamp, current_timestamp, 0, 1000, 0, 0, '限时租户部门', 'i5-t1000-root', 0, 0, 'dev 夹具');

merge into sys_user (id, create_time, update_time, deleted, tenant_id, version,
                     username, password, real_name, dept_id, status, is_admin)
key (id)
values (9401, current_timestamp, current_timestamp, 0, 1000, 0,
        't1000admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK',
        '限时租户管理员', 4001, 0, 1);

merge into sys_role (id, create_time, update_time, deleted, tenant_id, version,
                     name, code, sort, status, data_scope, remark, built_in)
key (id)
values (9401, current_timestamp, current_timestamp, 0, 1000, 0,
        '限时租户管理员', 't1000_admin', 0, 1, 0, 'dev 夹具', false);

merge into sys_user_role (id, create_time, update_time, deleted, tenant_id, version,
                          user_id, role_id)
key (id)
values (9401, current_timestamp, current_timestamp, 0, 1000, 0, 9401, 9401);

-- SQL 回读通道权限（dev 夹具）：外部数据源执行/管理按钮菜单 + 租户 100 角色授权。
merge into sys_menu (id, create_time, update_time, deleted, version,
                     parent_id, name, title, menu_type, path, component, permission, icon, sort, hidden)
key (id)
values (910002, current_timestamp, current_timestamp, 0, 0, 0,
        'ds_execute', '数据源执行', 2, '', '', 'workflow:datasource:execute', '', 98, false);
merge into sys_menu (id, create_time, update_time, deleted, version,
                     parent_id, name, title, menu_type, path, component, permission, icon, sort, hidden)
key (id)
values (910003, current_timestamp, current_timestamp, 0, 0, 0,
        'ds_manage', '数据源管理', 2, '', '', 'workflow:datasource:manage', '', 97, false);
merge into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
key (id)
values (910002, current_timestamp, current_timestamp, 0, 100, 0, 9001, 910002);
merge into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
key (id)
values (910003, current_timestamp, current_timestamp, 0, 100, 0, 9001, 910003);
