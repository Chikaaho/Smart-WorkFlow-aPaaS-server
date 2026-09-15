-- ===================================================================
-- Smart-WorkFlow :: P21 IoT 平台化扩展 (PostgreSQL)
-- ===================================================================
-- 连接配置 / 产品与物模型版本 / 设备扩展 / Topic / 受控脚本 / 事件规则 /
-- 消息与运行记录 / 流程触发幂等 / 统一命令。
-- 约定：8 基列 + bigint PK + 无外键，与既有 sw_iot_* 对齐。
-- ===================================================================

-- 1. 连接配置（独立于设备，可复用；凭证仅密文）
create table sw_iot_connection (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    code              varchar(64)     not null,
    name              varchar(200)    not null,
    conn_type         varchar(20)     not null,
    enabled           smallint        not null default 1,
    health_status     varchar(20)     not null default 'UNKNOWN',
    last_check_time   timestamp,
    last_check_result varchar(500),
    host              varchar(200),
    port              int,
    use_tls           smallint        not null default 0,
    client_id_prefix  varchar(64),
    username          varchar(128),
    password_cipher   varchar(512),
    keepalive         int             not null default 60,
    clean_session     smallint        not null default 1,
    reconnect_min_sec int             not null default 1,
    reconnect_max_sec int             not null default 60,
    region            varchar(64),
    endpoint          varchar(200),
    secret_ref        varchar(256),
    ext_json          text
);
create unique index uk_sw_iot_conn_code on sw_iot_connection (tenant_id, code);

-- 2. 产品（承载统一物模型）
create table sw_iot_product (
    id                 bigint          not null primary key,
    create_time        timestamp       not null default current_timestamp,
    create_by          bigint,
    update_time        timestamp       not null default current_timestamp,
    update_by          bigint,
    deleted            smallint        not null default 0,
    tenant_id          bigint          not null default 0,
    version            bigint          not null default 0,
    code               varchar(64)     not null,
    name               varchar(200)    not null,
    conn_id            bigint,
    conn_type          varchar(20)     not null default 'MQTT',
    model_status       varchar(20)     not null default 'DRAFT',
    published_model_id bigint,
    description        varchar(500)
);
create unique index uk_sw_iot_product_code on sw_iot_product (tenant_id, code);

-- 3. 物模型版本（草稿→校验/发布→归档；content_json = properties/events/actions）
create table sw_iot_thing_model (
    id           bigint          not null primary key,
    create_time  timestamp       not null default current_timestamp,
    create_by    bigint,
    update_time  timestamp       not null default current_timestamp,
    update_by    bigint,
    deleted      smallint        not null default 0,
    tenant_id    bigint          not null default 0,
    version      bigint          not null default 0,
    product_id   bigint          not null,
    model_version int            not null,
    status       varchar(20)     not null default 'DRAFT',
    content_json text,
    publish_time timestamp
);
create unique index uk_sw_iot_model_ver on sw_iot_thing_model (tenant_id, product_id, model_version);

-- 4. 设备扩展列（保留既有腾讯字段）
alter table sw_iot_device add column if not exists connection_id      bigint;
alter table sw_iot_device add column if not exists product_ref_id     bigint;
alter table sw_iot_device add column if not exists manage_status      varchar(20) not null default 'DRAFT';
alter table sw_iot_device add column if not exists process_access_enabled smallint not null default 0;
alter table sw_iot_device add column if not exists last_report_time   timestamp;
alter table sw_iot_device add column if not exists last_command_time  timestamp;
alter table sw_iot_device add column if not exists labels             varchar(500);
alter table sw_iot_device add column if not exists ext_json           text;

-- 5. Topic 配置（订阅/发布、方向、QoS、载荷映射）
create table sw_iot_topic (
    id            bigint          not null primary key,
    create_time   timestamp       not null default current_timestamp,
    create_by     bigint,
    update_time   timestamp       not null default current_timestamp,
    update_by     bigint,
    deleted       smallint        not null default 0,
    tenant_id     bigint          not null default 0,
    version       bigint          not null default 0,
    conn_id       bigint          not null,
    product_id    bigint,
    topic         varchar(200)    not null,
    direction     varchar(10)     not null default 'UP',
    qos           int             not null default 1,
    retain        smallint        not null default 0,
    payload_type  varchar(20)     not null default 'RAW',
    mapping_json  text,
    enabled       smallint        not null default 1,
    remark        varchar(500)
);
create unique index uk_sw_iot_topic on sw_iot_topic (tenant_id, topic);

-- 6. 受控脚本与版本
create table sw_iot_script (
    id               bigint          not null primary key,
    create_time      timestamp       not null default current_timestamp,
    create_by        bigint,
    update_time      timestamp       not null default current_timestamp,
    update_by        bigint,
    deleted          smallint        not null default 0,
    tenant_id        bigint          not null default 0,
    version          bigint          not null default 0,
    code             varchar(64)     not null,
    name             varchar(200)    not null,
    language         varchar(10)     not null,
    trigger_type     varchar(20)     not null default 'MESSAGE',
    binding_json     text,
    input_schema     text,
    output_schema    text,
    status           varchar(20)     not null default 'DRAFT',
    current_version  int             not null default 1,
    published_version int,
    timeout_ms       int             not null default 5000
);
create unique index uk_sw_iot_script_code on sw_iot_script (tenant_id, code);

create table sw_iot_script_version (
    id           bigint          not null primary key,
    create_time  timestamp       not null default current_timestamp,
    create_by    bigint,
    update_time  timestamp       not null default current_timestamp,
    update_by    bigint,
    deleted      smallint        not null default 0,
    tenant_id    bigint          not null default 0,
    version      bigint          not null default 0,
    script_id    bigint          not null,
    script_version int           not null,
    source_code  text,
    status       varchar(20)     not null default 'DRAFT',
    publish_time timestamp
);
create unique index uk_sw_iot_script_ver on sw_iot_script_version (tenant_id, script_id, script_version);

create table sw_iot_script_exec (
    id             bigint          not null primary key,
    create_time    timestamp       not null default current_timestamp,
    create_by      bigint,
    update_time    timestamp       not null default current_timestamp,
    update_by      bigint,
    deleted        smallint        not null default 0,
    tenant_id      bigint          not null default 0,
    version        bigint          not null default 0,
    script_id      bigint          not null,
    script_version int             not null,
    trigger_source varchar(20),
    trigger_ref    varchar(128),
    device_ref     varchar(128),
    status         varchar(20)     not null,
    input_json     text,
    output_json    text,
    error          varchar(1000),
    duration_ms    bigint,
    side_effect    smallint        not null default 0,
    idempotent_key varchar(128)
);
create index idx_sw_iot_exec_script on sw_iot_script_exec (tenant_id, script_id, create_time);

-- 7. 事件规则（阈值/变化/事件/上下线；防抖/冷却；流程映射）
create table sw_iot_event_rule (
    id                   bigint          not null primary key,
    create_time          timestamp       not null default current_timestamp,
    create_by            bigint,
    update_time          timestamp       not null default current_timestamp,
    update_by            bigint,
    deleted              smallint        not null default 0,
    tenant_id            bigint          not null default 0,
    version              bigint          not null default 0,
    code                 varchar(64)     not null,
    name                 varchar(200)    not null,
    device_id            bigint          not null,
    rule_type            varchar(20)     not null,
    condition_json       text,
    debounce_ms          bigint          not null default 0,
    cooldown_ms          bigint          not null default 0,
    continuous_count     int             not null default 1,
    script_id            bigint,
    process_template_key varchar(128),
    form_mapping_json    text,
    process_enabled      smallint        not null default 0,
    status               varchar(20)     not null default 'DRAFT',
    rule_version         int             not null default 1,
    last_fired_time      timestamp
);
create unique index uk_sw_iot_rule_code on sw_iot_event_rule (tenant_id, code);
create index idx_sw_iot_rule_device on sw_iot_event_rule (tenant_id, device_id);

-- 8. 运行记录
create table sw_iot_message_log (
    id            bigint          not null primary key,
    create_time   timestamp       not null default current_timestamp,
    create_by     bigint,
    update_time   timestamp       not null default current_timestamp,
    update_by     bigint,
    deleted       smallint        not null default 0,
    tenant_id     bigint          not null default 0,
    version       bigint          not null default 0,
    conn_id       bigint,
    device_id     bigint,
    topic         varchar(200),
    direction     varchar(10)     not null default 'UP',
    message_id    varchar(128),
    dedup_key     varchar(128),
    payload       text,
    payload_type  varchar(20),
    parse_status  varchar(20)     not null default 'RECEIVED',
    parse_error   varchar(500),
    qos           int             not null default 0
);
create index idx_sw_iot_msg_dedup on sw_iot_message_log (tenant_id, dedup_key);
create index idx_sw_iot_msg_topic on sw_iot_message_log (tenant_id, topic, create_time);

create table sw_iot_property_record (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    device_id       bigint          not null,
    property_id     varchar(64)     not null,
    value_json      varchar(1000),
    pre_value_json  varchar(1000),
    report_time     timestamp       not null
);
create index idx_sw_iot_prop_dev on sw_iot_property_record (tenant_id, device_id, property_id, report_time);

create table sw_iot_event_record (
    id          bigint          not null primary key,
    create_time timestamp       not null default current_timestamp,
    create_by   bigint,
    update_time timestamp       not null default current_timestamp,
    update_by   bigint,
    deleted     smallint        not null default 0,
    tenant_id   bigint          not null default 0,
    version     bigint          not null default 0,
    device_id   bigint          not null,
    event_id    varchar(64)     not null,
    payload     text,
    occur_time  timestamp       not null
);
create index idx_sw_iot_event_dev on sw_iot_event_record (tenant_id, device_id, occur_time);

-- 9. 流程触发幂等记录
create table sw_iot_process_trigger (
    id                  bigint          not null primary key,
    create_time         timestamp       not null default current_timestamp,
    create_by           bigint,
    update_time         timestamp       not null default current_timestamp,
    update_by           bigint,
    deleted             smallint        not null default 0,
    tenant_id           bigint          not null default 0,
    version             bigint          not null default 0,
    rule_id             bigint,
    script_id           bigint,
    device_id           bigint,
    idempotent_key      varchar(128)    not null,
    status              varchar(20)     not null default 'PENDING',
    process_instance_id varchar(64),
    form_snapshot       text,
    error               varchar(500),
    trigger_time        timestamp       not null
);
create unique index uk_sw_iot_trigger_key on sw_iot_process_trigger (tenant_id, idempotent_key);

-- 11. 遗留腾讯身份列放宽
alter table sw_iot_device alter column product_id set null;
alter table sw_iot_device alter column device_name set null;

-- 10. 统一命令（Provider 无关；区分 Broker 接收 / 设备回复 / 业务执行）
create table sw_iot_command (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    device_id       bigint          not null,
    conn_id         bigint,
    provider        varchar(20)     not null default 'MQTT',
    capability_type varchar(20)     not null,
    capability_id   varchar(64),
    params_json     text,
    status          varchar(20)     not null default 'PENDING',
    result_json     text,
    error           varchar(500),
    idempotent_key  varchar(128),
    source_type     varchar(20)     not null default 'MANUAL',
    source_ref      varchar(128),
    flow_instance_id varchar(64),
    flow_task_id    varchar(64),
    qos             int             not null default 1,
    sent_time       timestamp,
    reply_time      timestamp,
    timeout_ms      int             not null default 30000
);
create index idx_sw_iot_cmd_dev on sw_iot_command (tenant_id, device_id, create_time);
create index idx_sw_iot_cmd_flow on sw_iot_command (tenant_id, flow_instance_id);
