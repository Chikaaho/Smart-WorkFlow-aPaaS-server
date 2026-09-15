-- ===================================================================
-- V84 (I5): 第三方 SSO 数据模型 — 配置 / 绑定 / 授权状态 / 审计
-- ===================================================================
-- 三个 Provider（企业微信/飞书/钉钉）共用同一套载体，Provider 以字符串字面量区分
-- （WECOM / FEISHU / DINGTALK），不建立第二套身份/会话/授权体系。
--
-- 表：
--   sys_sso_provider_config  租户级 Provider 配置（凭据 AES-GCM 落库加密）
--   sys_sso_user_binding     外部身份 → 本地账号绑定（(provider,tenant,external) 唯一）
--   sys_sso_auth_state       一次性授权 state（防重放：唯一消费 + 过期）
--   sys_sso_audit_record     最小持久审计（脱敏，不含票据/秘密）
--
-- 全部表带标准基列（tenant_id/deleted/version/审计列）；敏感列（app_secret）
-- 存 AES-256-GCM 密文，不存明文；审计只存摘要/掩码，不存 code/token/secret。
-- ===================================================================

create table sys_sso_provider_config (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    provider        varchar(20)     not null,
    enabled         smallint        not null default 0,
    app_id          varchar(200)    not null,
    app_secret_enc  varchar(1000)   not null,
    extra_config    clob,
    redirect_path   varchar(500)
);
create unique index uk_sys_sso_provider_config
    on sys_sso_provider_config (tenant_id, provider, deleted);

create table sys_sso_user_binding (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    provider        varchar(20)     not null,
    external_id     varchar(200)    not null,
    external_digest varchar(64)     not null,
    user_id         bigint          not null,
    bind_status     varchar(20)     not null default 'ACTIVE'
);
create unique index uk_sys_sso_binding_external
    on sys_sso_user_binding (provider, tenant_id, external_id, deleted);
create unique index uk_sys_sso_binding_user
    on sys_sso_user_binding (provider, tenant_id, user_id, deleted);
create index idx_sys_sso_binding_user_all on sys_sso_user_binding (user_id);

create table sys_sso_auth_state (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    state_value     varchar(128)    not null,
    provider        varchar(20)     not null,
    nonce           varchar(128),
    redirect_path   varchar(500),
    consumed        smallint        not null default 0,
    expire_at       timestamp       not null
);
create unique index uk_sys_sso_state_value on sys_sso_auth_state (state_value);
create index idx_sys_sso_state_expire on sys_sso_auth_state (expire_at);

create table sys_sso_audit_record (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    provider        varchar(20)     not null,
    event_type      varchar(40)     not null,
    result          varchar(20)     not null,
    actor_id        bigint,
    local_user_id   bigint,
    external_digest varchar(64),
    detail          varchar(1000)
);
create index idx_sys_sso_audit_query
    on sys_sso_audit_record (tenant_id, provider, event_type, create_time);
