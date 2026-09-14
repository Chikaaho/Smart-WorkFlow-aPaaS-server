-- ===================================================================
-- V89 (I6): 通知与版本收口 — 通知域数据模型
-- 适用 H2 与 PostgreSQL（同一迁移身份，双目录逐字节一致）
-- 内容：
--   1) 模板事件/渠道/白名单扩展 + 不可变版本追加表
--   2) 通知规则（事件开关/接收人规则/渠道顺序/失败策略）
--   3) 用户订阅（普通用户可选偏好；强制事件不由订阅关闭）
--   4) 租户级渠道启停登记（秘密不入库，只存非秘密摘要）
--   5) 消息行统一业务身份（事件类型/发生次序/模板版本/深链/重试状态）
--   6) 尝试流水扩展（失败分类/起止时间）
-- 约束：表前缀 sw_notify_；不降低既有 8 基列/租户隔离语义；
--       全部列为可空或带默认值，不重写历史数据。
-- ===================================================================

-- ---------- 1. 模板扩展 ----------
alter table sw_notify_template add column event_type varchar(40) not null default 'SYSTEM';
alter table sw_notify_template add column channel varchar(40) not null default 'IN_APP';
alter table sw_notify_template add column variables_allowed varchar(1000);
alter table sw_notify_template add column jump_ref varchar(200);

-- ---------- 2. 模板版本追加（发布后不可改写） ----------
create table sw_notify_template_version (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    template_id       bigint          not null,
    template_version  int             not null default 1,
    event_type        varchar(40)     not null default 'SYSTEM',
    channel           varchar(40)     not null default 'IN_APP',
    title_template    varchar(200)    not null,
    content_template  text            not null,
    variables_allowed varchar(1000),
    jump_ref          varchar(200),
    status            varchar(20)     not null default 'RELEASED'
);
create unique index uk_sw_notify_tpl_ver on sw_notify_template_version (tenant_id, template_id, template_version);
create index idx_sw_notify_tpl_ver_template on sw_notify_template_version (template_id);

-- ---------- 3. 通知规则 ----------
create table sw_notify_rule (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    rule_code         varchar(100)    not null,
    name              varchar(100)    not null,
    event_type        varchar(40)     not null,
    channel_priority  varchar(200)    not null default 'IN_APP',
    recipient_rule    varchar(500)    not null,
    required_flag     smallint        not null default 0,
    failure_policy    varchar(20)     not null default 'RETRY',
    enabled           smallint        not null default 1,
    remark            varchar(500)
);
create unique index uk_sw_notify_rule_code on sw_notify_rule (tenant_id, rule_code, deleted);
create index idx_sw_notify_rule_event on sw_notify_rule (tenant_id, event_type);

-- ---------- 4. 用户订阅（可选偏好） ----------
create table sw_notify_subscription (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    user_id           bigint          not null,
    event_type        varchar(40)     not null,
    channel           varchar(40)     not null default 'IN_APP',
    enabled           smallint        not null default 1
);
create unique index uk_sw_notify_sub on sw_notify_subscription (tenant_id, user_id, event_type, channel, deleted);

-- ---------- 5. 租户级渠道启停（秘密不入库） ----------
create table sw_notify_channel_config (
    id                bigint          not null primary key,
    create_time       timestamp       not null default current_timestamp,
    create_by         bigint,
    update_time       timestamp       not null default current_timestamp,
    update_by         bigint,
    deleted           smallint        not null default 0,
    tenant_id         bigint          not null default 0,
    version           bigint          not null default 0,
    channel           varchar(40)     not null,
    enabled           smallint        not null default 0,
    sender_display    varchar(200),
    config_summary    varchar(500)
);
create unique index uk_sw_notify_channel on sw_notify_channel_config (tenant_id, channel, deleted);

-- ---------- 6. 消息行统一业务身份与投递状态扩展 ----------
alter table sw_notify_message add column event_type varchar(40) not null default 'SYSTEM';
alter table sw_notify_message add column occurrence_no bigint not null default 1;
alter table sw_notify_message add column template_id bigint;
alter table sw_notify_message add column template_version int;
alter table sw_notify_message add column link_type varchar(32);
alter table sw_notify_message add column link_id varchar(64);
alter table sw_notify_message add column retry_count int not null default 0;
alter table sw_notify_message add column next_retry_time timestamp;
alter table sw_notify_message add column failure_class varchar(40);
alter table sw_notify_message add column receipt_digest varchar(200);

-- 一次业务通知稳定身份：租户+事件类型+业务对象+发生次序+接收人+渠道
-- （biz_id 为空的系统通告不受唯一键约束，保持可重复广播语义）
create unique index uk_sw_notify_msg_identity on sw_notify_message (tenant_id, event_type, biz_id, occurrence_no, recipient_id, channel);
create index idx_sw_notify_msg_retry on sw_notify_message (tenant_id, event_type, biz_id);

-- ---------- 7. 尝试流水扩展 ----------
alter table sw_notify_send_attempt add column failure_class varchar(40);
alter table sw_notify_send_attempt add column started_at timestamp;
alter table sw_notify_send_attempt add column finished_at timestamp;
