-- I5 复验（G1/G2/G4）：dev 专用可控测试租户与身份夹具。
-- 仅经 application-dev.yml 的 flyway devseed location 装载；生产/local 不包含本目录。
-- 边界与 BpmDeployRunner 骨架一致：dev/test 验证骨架，不构成产品语义。
-- 明文密码 admin123（与 V4 种子同一 BCrypt 散列，仅 dev 使用）。

merge into sys_tenant (id, create_time, update_time, deleted, tenant_id, version,
                       name, code, status, description)
key (id)
values (100, current_timestamp, current_timestamp, 0, 0, 0,
        'I5测试租户', 'i5-tenant-100', 0, 'I5 复验可控测试租户（dev 夹具）');

merge into sys_dept (id, create_time, update_time, deleted, tenant_id, version,
                     parent_id, name, code, sort, status, description)
key (id)
values (1001, current_timestamp, current_timestamp, 0, 100, 0,
        0, 'I5测试租户根部门', 'i5-t100-root', 0, 0, 'dev 夹具');

merge into sys_user (id, create_time, update_time, deleted, tenant_id, version,
                     username, password, real_name, dept_id, status, is_admin)
key (id)
values (9001, current_timestamp, current_timestamp, 0, 100, 0,
        't100admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK',
        '租户100管理员', 1001, 0, 1);

merge into sys_role (id, create_time, update_time, deleted, tenant_id, version,
                     name, code, sort, status, data_scope, remark, built_in)
key (id)
values (9001, current_timestamp, current_timestamp, 0, 100, 0,
        '租户100管理员', 't100_admin', 0, 1, 0, 'dev 夹具：全量权限（数据范围 ALL）', false);

merge into sys_user_role (id, create_time, update_time, deleted, tenant_id, version,
                          user_id, role_id)
key (id)
values (9001, current_timestamp, current_timestamp, 0, 100, 0, 9001, 9001);

-- 授权该租户全部菜单（dev 夹具；id 段 900000+ 避开其他夹具）
merge into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
key (id)
select 900000 + m.id, current_timestamp, current_timestamp, 0, 100, 0, 9001, m.id
from sys_menu m where m.deleted = 0;
