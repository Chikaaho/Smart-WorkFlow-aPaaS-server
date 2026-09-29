-- I5 复验 03（G6a）：同一 Provider 稳定外部主体（摘要）全局仅允许一个租户绑定。
-- external_id 列自 iteration-03 起存摘要（与 external_digest 同值），明文不落 SQL。
create unique index uk_sys_sso_binding_digest_global
    on sys_sso_user_binding (provider, external_digest, deleted);
