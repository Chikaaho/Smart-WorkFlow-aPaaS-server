-- P64 阶段Ⅰ（数据到动作）：节点业务表单 / Trigger 判断 / 关联动作三张新表（追加、非破坏）。
-- 依据：product/p64-mes-advanced-orchestration/ready/adr-p64-001-phase1-data-to-action.md。
-- 与主链 V0.1.7 同构；全部 IF NOT EXISTS 可重复执行；旧图（无 variables/triggers/节点表单绑定）零行为。

CREATE TABLE IF NOT EXISTS sw_bpm_task_form_data (
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
    node_key varchar(200) not null,
    task_id varchar(64) not null,
    round_no bigint not null default 0,
    form_key varchar(128) not null,
    form_version bigint not null,
    status varchar(16) not null,
    data_text text,
    submitted_by bigint,
    submit_time timestamp
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_task_form_data ON sw_bpm_task_form_data (tenant_id, task_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_task_form_data_instance ON sw_bpm_task_form_data (tenant_id, process_instance_id, node_key, round_no);

CREATE TABLE IF NOT EXISTS sw_bpm_trigger_exec (
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
    trigger_id varchar(64) not null,
    event_type varchar(32) not null,
    node_key varchar(200),
    round_no bigint not null default 0,
    exec_key varchar(200) not null,
    status varchar(20) not null,
    result_value varchar(512),
    result_type varchar(16),
    matched_branch_id varchar(64),
    disposition varchar(32),
    error_text text,
    snapshot_text text,
    duration_ms bigint
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_trigger_exec ON sw_bpm_trigger_exec (tenant_id, exec_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_trigger_exec_instance ON sw_bpm_trigger_exec (tenant_id, process_instance_id);

CREATE TABLE IF NOT EXISTS sw_bpm_action_ref (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    exec_id bigint not null,
    process_instance_id varchar(64) not null,
    trigger_id varchar(64),
    action_id varchar(64) not null,
    action_type varchar(24) not null,
    item_key varchar(128) not null,
    item_summary text,
    payload_json text,
    command_key varchar(200) not null,
    command_id bigint,
    target_def_key varchar(128) not null,
    target_form_key varchar(128),
    target_record_id varchar(64),
    target_instance_id varchar(64),
    status varchar(20) not null,
    error_text text
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_action_ref ON sw_bpm_action_ref (tenant_id, command_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_action_ref_instance ON sw_bpm_action_ref (tenant_id, process_instance_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_action_ref_exec ON sw_bpm_action_ref (tenant_id, exec_id);
