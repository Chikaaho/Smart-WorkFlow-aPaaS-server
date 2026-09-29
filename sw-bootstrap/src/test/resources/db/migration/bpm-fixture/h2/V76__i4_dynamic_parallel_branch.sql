-- V76 (I4 §3.1): 动态并行分支冻结快照。
-- 进入节点时冻结来源部门、负责人与汇聚策略；运行中表单或组织变化不改写本表。
-- 无效对象（失效部门/负责人缺失/跨租户）落 CANCELED 行并记录原因，不静默跳过。
create table sw_bpm_dynamic_branch (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    process_instance_id varchar(64) not null,
    node_key varchar(128) not null,
    branch_index int not null,
    source_type varchar(32),
    source_value varchar(512),
    dept_ids varchar(512),
    leader_id bigint,
    converge_mode varchar(16) not null,
    status varchar(20) not null,
    task_id varchar(64),
    cancel_reason varchar(512)
);
create index idx_sw_bpm_dyn_branch_instance on sw_bpm_dynamic_branch (process_instance_id, node_key);
create unique index uk_sw_bpm_dyn_branch on sw_bpm_dynamic_branch (tenant_id, process_instance_id, node_key, branch_index);
