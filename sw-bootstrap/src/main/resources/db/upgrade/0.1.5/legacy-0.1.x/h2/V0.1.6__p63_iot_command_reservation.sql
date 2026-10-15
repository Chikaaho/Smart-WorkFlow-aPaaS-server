-- V0.1.6 (P63): 流程成功结束后的一次性 IoT 预约意图。
-- 冻结租户/定义/实例/表单记录/设备目标/命令与参数/预约时刻（显式时区）/迟到窗口；
-- 状态机：PENDING → DISPATCHING → DISPATCHED；CANCELED / EXPIRED / FAILED 为确定终态。
-- 同一租户同一流程实例至多一条预约意图（同一次成功完成的幂等身份）。
create table sw_iot_command_reservation (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    process_def_key varchar(128),
    def_version int,
    form_key varchar(128),
    record_id varchar(36),
    device_key varchar(128),
    product_id varchar(128),
    device_name varchar(128),
    command_key varchar(128),
    command_type varchar(16),
    payload text,
    due_at_utc timestamp not null,
    timezone_id varchar(64) not null,
    due_local_text varchar(64),
    late_window_seconds int not null,
    status varchar(20) not null,
    command_id bigint,
    reject_reason varchar(512),
    cancel_by bigint,
    cancel_reason varchar(512),
    cancel_time timestamp
);
create index idx_sw_iot_reservation_status on sw_iot_command_reservation (status, due_at_utc);
create unique index uk_sw_iot_reservation_instance on sw_iot_command_reservation (tenant_id, process_instance_id);
