-- Phase 4 可靠业务事件（BAO-05）：IoT 流程触发的恢复身份列 + OpenAPI 回调持久任务。
-- 说明：IoT 触发原有 PENDING/SUCCESS/FAILED 状态与唯一幂等键不变，本迁移补齐恢复所需的
--       发起目标身份（process_template_key 等）与重试预算列；回调任务表把“回调意图”持久化，
--       使业务提交后进程退出仍可恢复投递（原实现只在进程内重试 3 次后仅留日志）。

alter table sw_iot_process_trigger add column if not exists process_template_key varchar(128);
alter table sw_iot_process_trigger add column if not exists trigger_source varchar(20);
alter table sw_iot_process_trigger add column if not exists configured_by bigint;
alter table sw_iot_process_trigger add column if not exists retry_count int not null default 0;
alter table sw_iot_process_trigger add column if not exists next_retry_time timestamp;
create index if not exists idx_sw_iot_trigger_recovery on sw_iot_process_trigger (status, next_retry_time);

create table if not exists sw_openapi_callback_task (
    id              bigint       not null primary key,
    tenant_id       bigint       not null default 0,
    version         bigint       not null default 0,
    deleted         smallint     not null default 0,
    create_time     timestamp    not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp    not null default current_timestamp,
    update_by       bigint,
    app_id          varchar(64)  not null,
    event           varchar(50)  not null,
    biz_ref         varchar(64)  not null,
    status          varchar(20)  not null default 'PENDING',
    attempts        int          not null default 0,
    next_retry_time timestamp,
    last_error      varchar(500),
    delivered_at    timestamp
);
create unique index if not exists uk_sw_openapi_cb_task on sw_openapi_callback_task (tenant_id, app_id, event, biz_ref);
create index if not exists idx_sw_openapi_cb_task_due on sw_openapi_callback_task (status, next_retry_time);
