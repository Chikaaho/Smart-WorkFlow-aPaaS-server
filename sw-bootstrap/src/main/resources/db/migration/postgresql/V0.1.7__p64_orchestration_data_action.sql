-- V0.1.7 (P64 阶段Ⅰ 数据到动作): 节点业务表单 / Trigger 判断 / 关联动作三张新表。
-- 全部为追加新表，不 ALTER 既有表；旧图（无 variables/triggers/节点表单绑定）零行为，
-- 旧定义与运行实例按其 def_version 冻结图运行（ADR-P64-001 §6）。
-- 回退：新代码回滚后旧代码不读写三张新表，数据保留，不通过破坏性 DDL 删除事实。

-- 1) 任务级节点业务表单数据：一行 = 一个任务的数据身份（ADR-P64-001 §1）。
-- status: DRAFT / SUBMITTED；仅合法最终提交（APPROVE/DISAPPROVE 同事务）进入 SUBMITTED，
-- REJECT/RETURN 不产生有效提交；round_no = 实例 RETURN 动作数 + 1（退回前旧轮不混入新轮读取）。
create table sw_bpm_task_form_data (
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
create unique index uk_sw_bpm_task_form_data on sw_bpm_task_form_data (tenant_id, task_id);
create index idx_sw_bpm_task_form_data_instance on sw_bpm_task_form_data (tenant_id, process_instance_id, node_key, round_no);

-- 2) Trigger 判断执行记录：一次评估一行（ADR-P64-001 §3）。
-- status: MATCHED / UNMATCHED / FAILED；disposition: EMPTY_COLLECTION / OVER_LIMIT /
-- BLOCK / IGNORE；snapshot_text 为本次判断共用的一致数据快照（≤1MiB）；可诊断、不冒充成功。
create table sw_bpm_trigger_exec (
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
create unique index uk_sw_bpm_trigger_exec on sw_bpm_trigger_exec (tenant_id, exec_key);
create index idx_sw_bpm_trigger_exec_instance on sw_bpm_trigger_exec (tenant_id, process_instance_id);

-- 3) 动作意图与目标实例关联：一次派发项一行（ADR-P64-001 §4）。
-- status: INTENT_SUBMITTED / STARTED / FAILED；command_key 与 sw_bpm_command 幂等键一致；
-- target_record_id/target_instance_id 由消费事务回填，构成 业务单据→触发→动作→关联实例 回查链。
create table sw_bpm_action_ref (
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
create unique index uk_sw_bpm_action_ref on sw_bpm_action_ref (tenant_id, command_key);
create index idx_sw_bpm_action_ref_instance on sw_bpm_action_ref (tenant_id, process_instance_id);
create index idx_sw_bpm_action_ref_exec on sw_bpm_action_ref (tenant_id, exec_id);
