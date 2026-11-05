-- ===================================================================
-- V0.2.8 IoT 事件规则 → Agent 图执行桥接（0.2.4 README 示例二/三）
-- 1) sw_iot_event_rule 新增 Agent 动作配置：启用开关、目标图 key、
--    输入映射（复用 form_mapping_json 的 source 词汇表 + RECENT_SERIES）、
--    失败升级流程模板 key（研判执行终态 FAILED 时按 P0 升级发起）。
-- 2) sw_iot_process_trigger 扩展为双动作载体：action_type 区分 FLOW/AGENT，
--    AGENT 行记录目标图 key 与执行回写 agent_execution_id（事件↔研判实例互查）。
-- 工具白名单（sw_agent_tool_internal / sw_agent_tool_external）为租户级数据
-- 配置，经 /agent/tool API 注册，不在本迁移内种子。
-- ===================================================================

alter table sw_iot_event_rule add column if not exists agent_enabled smallint not null default 0;
alter table sw_iot_event_rule add column if not exists agent_graph_key varchar(64);
alter table sw_iot_event_rule add column if not exists agent_input_mapping_json text;
alter table sw_iot_event_rule add column if not exists agent_failure_template_key varchar(128);

alter table sw_iot_process_trigger add column if not exists action_type varchar(16) not null default 'FLOW';
alter table sw_iot_process_trigger add column if not exists agent_graph_key varchar(64);
alter table sw_iot_process_trigger add column if not exists agent_execution_id bigint;

create index if not exists idx_sw_iot_trigger_agent_exec on sw_iot_process_trigger (tenant_id, agent_execution_id);
