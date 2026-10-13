-- P64 阶段Ⅱ（人员与父子协作）：子流程派发批次 / 批次项两张新表（追加、非破坏）。
-- 与主链 V0.1.8 同构；全部 IF NOT EXISTS 可重复执行；无 CHILD 动作的旧图零行为。

CREATE TABLE IF NOT EXISTS sw_bpm_child_batch (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    batch_key varchar(240) not null,
    parent_instance_id varchar(64) not null,
    parent_def_key varchar(128),
    root_instance_id varchar(64),
    parent_depth int not null default 0,
    trigger_id varchar(64) not null,
    action_id varchar(64) not null,
    round_no bigint not null default 0,
    wait_policy varchar(16) not null,
    wait_count int,
    expected_count int not null default 0,
    source_record_id varchar(64),
    source_record_version bigint,
    status varchar(20) not null,
    settled_count int not null default 0,
    block_reason text,
    settled_at timestamp,
    config_json text
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_child_batch ON sw_bpm_child_batch (tenant_id, batch_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_child_batch_parent ON sw_bpm_child_batch (tenant_id, parent_instance_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_child_batch_root ON sw_bpm_child_batch (tenant_id, root_instance_id);

CREATE TABLE IF NOT EXISTS sw_bpm_child_item (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    batch_id bigint not null,
    item_key varchar(128) not null,
    action_ref_id bigint,
    source_row_id varchar(64),
    source_row_version bigint,
    source_summary text,
    target_def_key varchar(128),
    target_form_key varchar(128),
    target_record_id varchar(64),
    target_instance_id varchar(64),
    status varchar(20) not null,
    writeback_json text,
    writeback_time timestamp,
    writeback_source varchar(200),
    error_text text
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_child_item ON sw_bpm_child_item (tenant_id, batch_id, item_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_child_item_target ON sw_bpm_child_item (tenant_id, target_record_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_child_item_batch ON sw_bpm_child_item (tenant_id, batch_id);
