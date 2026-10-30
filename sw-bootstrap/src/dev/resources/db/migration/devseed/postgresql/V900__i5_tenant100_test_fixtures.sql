-- I5 复验（G1/G2/G4）：dev 专用可控测试租户与身份夹具（PostgreSQL 镜像，与 h2/V900 同口径）。
-- 仅经 application-dev.yml 的 flyway devseed location 装载；生产/local 不包含本目录。
-- 边界与 BpmDeployRunner 骨架一致：dev/test 验证骨架，不构成产品语义。
-- 明文密码 admin123（与 V4 种子同一 BCrypt 散列，仅 dev 使用）。

insert into sys_tenant (id, create_time, update_time, deleted, tenant_id, version,
                        name, code, status, description)
values (100, current_timestamp, current_timestamp, 0, 0, 0,
        'I5测试租户', 'i5-tenant-100', 0, 'I5 复验可控测试租户（dev 夹具）')
on conflict (id) do nothing;

insert into sys_dept (id, create_time, update_time, deleted, tenant_id, version,
                      parent_id, name, code, sort, status, description)
values (1001, current_timestamp, current_timestamp, 0, 100, 0,
        0, 'I5测试租户根部门', 'i5-t100-root', 0, 0, 'dev 夹具')
on conflict (id) do nothing;

insert into sys_user (id, create_time, update_time, deleted, tenant_id, version,
                      username, password, real_name, dept_id, status, is_admin)
values (9001, current_timestamp, current_timestamp, 0, 100, 0,
        't100admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK',
        '租户100管理员', 1001, 0, 1)
on conflict (id) do nothing;

insert into sys_role (id, create_time, update_time, deleted, tenant_id, version,
                      name, code, sort, status, data_scope, remark, built_in)
values (9001, current_timestamp, current_timestamp, 0, 100, 0,
        '租户100管理员', 't100_admin', 0, 1, 0, 'dev 夹具：全量权限（数据范围 ALL）', false)
on conflict (id) do nothing;

insert into sys_user_role (id, create_time, update_time, deleted, tenant_id, version,
                           user_id, role_id)
values (9001, current_timestamp, current_timestamp, 0, 100, 0, 9001, 9001)
on conflict (id) do nothing;

-- 授权该租户全部菜单（dev 夹具；id 段 900000+ 避开其他夹具）
insert into sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
select 900000 + m.id, current_timestamp, current_timestamp, 0, 100, 0, 9001, m.id
from sys_menu m where m.deleted = 0
on conflict (id) do nothing;
