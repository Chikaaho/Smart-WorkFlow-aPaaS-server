-- I5 复验（G6）：同一 Provider 应用归属唯一租户。
-- 方向 §C13：同一 Provider 应用/组织中的同一稳定外部主体不能跨租户重复绑定。
-- 应用级唯一是前提：同一 (provider, app_id) 只允许一个租户登记，
-- 从而外部主体在各租户的绑定域天然不交叉。
create unique index uk_sys_sso_provider_app
    on sys_sso_provider_config (provider, app_id, deleted);
