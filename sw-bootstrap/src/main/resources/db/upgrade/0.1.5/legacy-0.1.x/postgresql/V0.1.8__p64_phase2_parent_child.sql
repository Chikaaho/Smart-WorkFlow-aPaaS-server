-- V0.1.8 (P64 阶段Ⅱ 人员与父子协作): 子流程派发批次 / 批次项两张新表。
-- 全部为追加新表，不 ALTER 既有表；无 CHILD 动作的旧图零行为，
-- 旧定义与运行实例按其 def_version 冻结图运行（ADR-P64-001 §6 回退口径不变）。
-- 回退：新代码回滚后旧代码不读写两张新表，数据保留，不通过破坏性 DDL 删除事实。

-- 1) 子流程派发批次：一次 CHILD 动作派发一批（主方向 §3.3 派发冻结）。
-- status: WAITING（等待结算）/ SETTLED（已结算推进一次）/ BLOCKED（失败/冲突阻断，可诊断）/
--         CANCELLED（父取消/退回终止写回推进权）。
-- wait_policy: ALL / ANY / COUNT / NONE（NONE=派发意图提交即结算）；wait_count 仅 COUNT 有效。
-- parent_depth: 父链深度（根=0，子=父+1；默认上限 3、硬上限 8）；
-- root_instance_id: 业务根链实例（累计实例护栏计数锚）。
-- source_record_id/source_record_version: 派发时冻结的父主表单记录身份与版本（稳定行契约）。
create table sw_bpm_child_batch (
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
create unique index uk_sw_bpm_child_batch on sw_bpm_child_batch (tenant_id, batch_key);
create index idx_sw_bpm_child_batch_parent on sw_bpm_child_batch (tenant_id, parent_instance_id);
create index idx_sw_bpm_child_batch_root on sw_bpm_child_batch (tenant_id, root_instance_id);

-- 2) 子流程批次项：一个预期子流程一行（冻结来源行身份与版本）。
-- status: DISPATCHED（已派发待子完成）/ WRITTEN（有效完成且所需回写已提交，计成功数）/
--         CONFLICT（回写冲突挂起，可诊断、有权恢复不重复已生效结果）/
--         LATE（批次已结算后的迟到完成，独立留痕不覆盖已用快照）/
--         REFUSED（父取消/退回后失去写回推进权，留痕）/ FAILED（发起链失败且不可恢复）/ CANCELLED。
-- source_row_id/source_row_version: 稳定来源行身份与派发时版本（父表排序改变不错行）；
-- source_rows_json: 本项冻结的授权来源行集合 [{rowId, version}]（分组项含多行；行级回写只接受集合内行）；
-- writeback_json/writeback_source: 回写值与来源（子实例/节点/轮次/任务可追溯）。
create table sw_bpm_child_item (
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
    source_rows_json text,
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
create unique index uk_sw_bpm_child_item on sw_bpm_child_item (tenant_id, batch_id, item_key);
create index idx_sw_bpm_child_item_target on sw_bpm_child_item (tenant_id, target_record_id);
create index idx_sw_bpm_child_item_batch on sw_bpm_child_item (tenant_id, batch_id);
