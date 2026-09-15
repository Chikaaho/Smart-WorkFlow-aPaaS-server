-- V1 (I4 §3.4): 开放接口底座 —— 应用身份（绑定租户/授权范围/代理用户）、
-- 防重放 nonce、幂等键登记、出站回调日志。
create table sw_openapi_app (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    app_name varchar(200) not null,
    secret_hash varchar(128) not null,
    scopes varchar(200) not null,
    status varchar(20) not null default 'ENABLED',
    act_as_user_id bigint not null,
    callback_url varchar(500),
    callback_secret_hash varchar(128)
);
create unique index uk_sw_openapi_app on sw_openapi_app (app_id);

create table sw_openapi_nonce (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    nonce varchar(128) not null,
    expire_at timestamp not null
);
create unique index uk_sw_openapi_nonce on sw_openapi_nonce (app_id, nonce);

create table sw_openapi_idempotency (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    idem_key varchar(200) not null,
    result_ref varchar(64)
);
create unique index uk_sw_openapi_idem on sw_openapi_idempotency (app_id, idem_key);

create table sw_openapi_callback_log (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    app_id varchar(64) not null,
    event varchar(50) not null,
    biz_ref varchar(64) not null,
    url varchar(500),
    attempt int not null,
    status varchar(20) not null,
    response_summary varchar(500),
    delivered_at timestamp
);
create index idx_sw_openapi_cb_app on sw_openapi_callback_log (app_id, biz_ref, event);
