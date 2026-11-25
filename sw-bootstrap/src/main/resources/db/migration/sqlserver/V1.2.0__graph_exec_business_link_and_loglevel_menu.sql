-- 1.2.0：图执行请求业务关联（P67 E1-7）。
-- 关联列：business_source（PROCESS/FORM/DIRECT；存量行 NULL 由读侧按 DIRECT 展示）、
-- process_instance_id / node_id（流程侧回查）、form_record_id（表单侧回查）；
-- 支撑执行列表按来源筛选与流程实例详情反查 Graph 执行编号。
-- 日志等级菜单种子不放在本版本化脚本：SQL Server 用户/菜单基线在
-- R__0.2.0_sqlserver_seed.sql（可重复脚本），全新库版本化阶段父菜单尚未生成，
-- 种子会按 R__1.0.0_audit_log_menu.sql 先例放 R__1.2.0_log_level_menu.sql。
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD [business_source] NVARCHAR(32);
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD [process_instance_id] NVARCHAR(64);
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD [node_id] NVARCHAR(100);
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD [form_record_id] BIGINT;
CREATE INDEX [idx_sw_agent_exec_req_biz_src] ON [dbo].[sw_agent_graph_execution_request] ([tenant_id], [business_source]);
CREATE INDEX [idx_sw_agent_exec_req_proc] ON [dbo].[sw_agent_graph_execution_request] ([process_instance_id]);
