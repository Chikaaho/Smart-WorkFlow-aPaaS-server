-- V79 (I4 §3.6): 流程交接批次与逐项清单。
-- 只迁移选定范围内尚未完成的可办理任务；代理规则显式勾选且通过校验才随迁；
-- 抄送/已办/历史意见/已过期规则不迁移；历史办理人与意见零改写。
create table sw_bpm_handover (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    from_user_id bigint not null,
    to_user_id bigint not null,
    scope_def_keys varchar(1000),
    include_proxy_rules boolean not null default false,
    status varchar(20) not null,
    total_items int,
    migrated_items int,
    failed_items int,
    operator_id bigint not null,
    executed_at timestamp not null
);
create index idx_sw_bpm_handover_users on sw_bpm_handover (from_user_id, to_user_id);

create table sw_bpm_handover_item (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    handover_id bigint not null,
    task_id varchar(64),
    process_instance_id varchar(64),
    item_type varchar(20) not null,
    before_assignee bigint,
    after_assignee bigint,
    result varchar(30) not null,
    fail_reason varchar(500),
    rule_id bigint
);
create index idx_sw_bpm_handover_item on sw_bpm_handover_item (handover_id);
