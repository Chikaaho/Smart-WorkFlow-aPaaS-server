-- ===================================================================
-- V57: v0.0.2 OA — 通知发送尝试流水 (PostgreSQL)
-- ===================================================================
-- sw_notify_send_attempt：每次渠道投递（首次发送与失败重发）各写一行，
-- 保留原始失败、各次尝试与最新结果；消息行 delivery_status 恒为最新结果。
-- 全部幂等（IF NOT EXISTS），可重复执行；与 H2 版本同语义。
-- ===================================================================

CREATE TABLE IF NOT EXISTS sw_notify_send_attempt (
    id                  bigint          not null primary key,
    create_time         timestamp       not null default current_timestamp,
    create_by           bigint,
    update_time         timestamp       not null default current_timestamp,
    update_by           bigint,
    deleted             smallint        not null default 0,
    tenant_id           bigint          not null default 0,
    version             bigint          not null default 0,
    message_id          bigint          not null,
    attempt_no          int             not null default 1,
    channel             varchar(32),
    status              varchar(20)     not null,
    failure_reason      varchar(500),
    external_message_id varchar(200)
);

CREATE INDEX IF NOT EXISTS idx_sw_notify_attempt_message ON sw_notify_send_attempt (message_id);
