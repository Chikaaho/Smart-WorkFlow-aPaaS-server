-- P4：流程业务命令持久受理/队列 与 业务发起草稿。
create table sw_bpm_command (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    command_key varchar(128) not null, command_type varchar(32) not null,
    channel varchar(16) not null default 'NORMAL', status varchar(16) not null default 'PENDING',
    payload text, result text, failure_reason varchar(1000),
    retry_count int not null default 0, next_retry_at timestamp,
    claimed_at timestamp, finished_at timestamp, initiator_id bigint
);
create unique index uk_sw_bpm_command_key on sw_bpm_command (tenant_id, command_key);
create index idx_sw_bpm_command_dispatch on sw_bpm_command (status, channel, next_retry_at);

create table sw_bpm_draft (
    id bigint not null primary key, create_time timestamp not null default current_timestamp,
    create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
    deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
    title varchar(200), form_key varchar(128) not null, form_version bigint,
    process_def_key varchar(128), payload text,
    status varchar(20) not null default 'EDITING', command_id bigint, submit_seq int not null default 0,
    result_record_id varchar(36), last_error varchar(1000)
);
create index idx_sw_bpm_draft_owner on sw_bpm_draft (tenant_id, create_by, form_key);
