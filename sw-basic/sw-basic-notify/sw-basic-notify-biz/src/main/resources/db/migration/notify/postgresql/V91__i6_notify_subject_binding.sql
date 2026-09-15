-- ===================================================================
-- V91 (I6): 通知 Provider 主体映射权威（安全、租户隔离、可失效、可判定重复绑定）
-- 适用于 H2 与 PostgreSQL（同一迁移身份，双目录逐字节一致）
-- 关系：
--   与 I5 SSO 绑定身份独立；不复用 sys_sso_user_binding.external_id 摘要语义
--   （I5 摘要不可反解：SSO 复用摘要不能充当通知明文主体）。
-- 秘密：subject_cipher 存以工程 cipher 服务加密的密文（不落明文/SQL 日志）。
-- ===================================================================

create table sw_notify_subject_binding (
    id              bigint       not null primary key,
    create_time     timestamp    not null default current_timestamp,
    update_time     timestamp    not null default current_timestamp,
    create_by       bigint,
    update_by       bigint,
    deleted         smallint     not null default 0,
    tenant_id       bigint       not null default 0,
    version         bigint       not null default 0,
    user_id         bigint       not null,
    provider        varchar(40)  not null,
    subject_cipher  varchar(500) not null,
    subject_digest  varchar(128) not null,
    bind_status     varchar(20)  not null default 'ACTIVE'
);

create unique index uk_sw_notify_subject_binding
    on sw_notify_subject_binding (tenant_id, user_id, provider, deleted);
create index idx_sw_notify_subject_provider
    on sw_notify_subject_binding (tenant_id, provider, deleted);
