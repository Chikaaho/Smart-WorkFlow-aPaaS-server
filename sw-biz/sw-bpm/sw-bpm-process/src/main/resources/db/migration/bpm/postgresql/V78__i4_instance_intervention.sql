-- V78 (I4 §3.3): 实例级运营干预审计。挂起/恢复/终止/迁移办理人逐项授权并记录
-- 操作者、原因、对象、前后状态与受影响任务；与定义级挂起/激活互不混同。
create table sw_bpm_instance_intervention (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    action varchar(20) not null,
    operator_id bigint not null,
    reason varchar(500),
    before_state varchar(20),
    after_state varchar(20),
    from_assignee bigint,
    to_assignee bigint,
    affected_tasks int,
    intervened_at timestamp not null
);
create index idx_sw_bpm_itv_instance on sw_bpm_instance_intervention (process_instance_id);
