-- I5 复验（G2）：真实迁移链补默认租户种子行。
-- V2 种子只含 id=1 的 'default' 展示租户；I5 租户有效性校验上线后，
-- 种子账号（tenant_id=0）在真实启动的登录/装载路径会被判租户不存在而拒绝。
-- 本迁移按幂等方式补 id=0 默认租户行（默认租户常量在其边界真实成立），
-- 不改写任何既有行；已存在的 id=0 行保持原值。
insert into sys_tenant (id, create_time, update_time, deleted, tenant_id, version,
                        name, code, status, description)
values (0, current_timestamp, current_timestamp, 0, 0, 0,
        '默认租户', 'tenant-0', 0, 'I5 收口：系统默认租户（种子账号所属租户）')
on conflict (id) do nothing;
