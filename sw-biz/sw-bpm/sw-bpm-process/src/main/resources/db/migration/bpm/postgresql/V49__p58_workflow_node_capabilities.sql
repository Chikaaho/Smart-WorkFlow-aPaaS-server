-- P58：参与人快照、审批动作/意见、分支轨迹与抄送投递审计。
create table sw_bpm_participant_snapshot (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, task_id varchar(64) not null,
    participant_id varchar(64) not null, participant_status varchar(20) not null,
    invalid_reason varchar(500)
);
create index idx_sw_bpm_participant_task on sw_bpm_participant_snapshot (tenant_id, task_id);

create table sw_bpm_approval_action (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, task_id varchar(64) not null,
    actor_id bigint not null, action varchar(20) not null, opinion_form_id varchar(200),
    opinion_form_version varchar(64), initialization_summary text, opinion_data text,
    settlement_status varchar(20)
);
create unique index uk_sw_bpm_approval_task_actor on sw_bpm_approval_action (tenant_id, task_id, actor_id);

create table sw_bpm_branch_trace (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, branch_id varchar(200) not null,
    condition_version varchar(64) not null, input_summary text
);
create index idx_sw_bpm_branch_trace_instance on sw_bpm_branch_trace (tenant_id, process_instance_id);

create table sw_bpm_copy_record (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    process_instance_id varchar(64) not null, node_key varchar(200) not null, task_id varchar(64),
    recipient_id varchar(64) not null, delivery_status varchar(30) not null, failure_reason varchar(500)
);
create index idx_sw_bpm_copy_instance on sw_bpm_copy_record (tenant_id, process_instance_id);
