-- SQL Server 首次支持：由 PostgreSQL 0.4.0 终态 schema 生成（不含 flyway_schema_history）。
-- 类型映射：字符/大文本/布尔/时间类型已转为 SQL Server 等价类型。
CREATE TABLE [dbo].[sw_agent_graph_debug_node] (
    [id] BIGINT NOT NULL,
    [debug_session_id] BIGINT NOT NULL,
    [node_seq] INT NOT NULL,
    [branch_id] NVARCHAR(64) NOT NULL,
    [node_id] NVARCHAR(100) NOT NULL,
    [node_type] NVARCHAR(20) NOT NULL,
    [node_latency_ms] BIGINT,
    [variable_snapshot] NVARCHAR(MAX),
    [input_tokens] BIGINT,
    [output_tokens] BIGINT,
    [create_time] DATETIME2 NOT NULL,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_graph_debug_session] (
    [id] BIGINT NOT NULL,
    [graph_def_id] BIGINT NOT NULL,
    [graph_def_version] INT NOT NULL,
    [graph_json] NVARCHAR(MAX),
    [status] NVARCHAR(20) NOT NULL,
    [input] NVARCHAR(MAX),
    [breakpoints] NVARCHAR(MAX),
    [state_json] NVARCHAR(MAX),
    [result_text] NVARCHAR(MAX),
    [error_category] NVARCHAR(50),
    [error_message] NVARCHAR(MAX),
    [latency_ms] BIGINT,
    [expires_at] DATETIME2,
    [input_tokens] BIGINT,
    [output_tokens] BIGINT,
    [create_time] DATETIME2 NOT NULL,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_graph_def] (
    [id] BIGINT NOT NULL,
    [graph_key] NVARCHAR(100) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [def_version] INT DEFAULT 1 NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [graph_json] NVARCHAR(MAX),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [execution_timeout_seconds] INT DEFAULT 300 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_graph_execution] (
    [id] BIGINT NOT NULL,
    [graph_def_id] BIGINT NOT NULL,
    [graph_def_version] INT NOT NULL,
    [status] NVARCHAR(20) NOT NULL,
    [input] NVARCHAR(MAX),
    [result_text] NVARCHAR(MAX),
    [error_category] NVARCHAR(50),
    [error_message] NVARCHAR(MAX),
    [latency_ms] BIGINT,
    [create_time] DATETIME2 NOT NULL,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [input_tokens] BIGINT,
    [output_tokens] BIGINT
);

CREATE TABLE [dbo].[sw_agent_graph_execution_event] (
    [id] BIGINT NOT NULL,
    [execution_id] BIGINT NOT NULL,
    [sequence_no] BIGINT NOT NULL,
    [event_type] NVARCHAR(32) NOT NULL,
    [node_id] NVARCHAR(100),
    [payload] NVARCHAR(MAX),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_graph_execution_node] (
    [id] BIGINT NOT NULL,
    [execution_id] BIGINT NOT NULL,
    [node_seq] INT NOT NULL,
    [branch_id] NVARCHAR(64) NOT NULL,
    [node_id] NVARCHAR(100) NOT NULL,
    [node_type] NVARCHAR(20) NOT NULL,
    [node_latency_ms] BIGINT,
    [variable_snapshot] NVARCHAR(MAX),
    [create_time] DATETIME2 NOT NULL,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [input_tokens] BIGINT,
    [output_tokens] BIGINT,
    [tool_calls] NVARCHAR(MAX),
    [input_text] NVARCHAR(MAX),
    [output_text] NVARCHAR(MAX),
    [start_time] DATETIME2,
    [end_time] DATETIME2,
    [status] NVARCHAR(20) DEFAULT N'SUCCESS' NOT NULL,
    [error_message] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_agent_graph_execution_request] (
    [id] BIGINT NOT NULL,
    [graph_def_id] BIGINT NOT NULL,
    [graph_def_version] INT NOT NULL,
    [idempotency_key] NVARCHAR(100),
    [request_fingerprint] NVARCHAR(64) NOT NULL,
    [status] NVARCHAR(32) NOT NULL,
    [input] NVARCHAR(MAX) NOT NULL,
    [result_execution_id] BIGINT,
    [error_category] NVARCHAR(64),
    [error_message] NVARCHAR(1000),
    [latency_ms] BIGINT,
    [started_at] DATETIME2,
    [finished_at] DATETIME2,
    [cancel_requested_at] DATETIME2,
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_graph_snapshot] (
    [id] BIGINT NOT NULL,
    [graph_def_id] BIGINT NOT NULL,
    [def_version] INT NOT NULL,
    [graph_key] NVARCHAR(100),
    [name] NVARCHAR(200),
    [graph_json] NVARCHAR(MAX) NOT NULL,
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_message] (
    [id] BIGINT NOT NULL,
    [session_id] BIGINT NOT NULL,
    [role] NVARCHAR(20) NOT NULL,
    [content] NVARCHAR(MAX) NOT NULL,
    [msg_order] INT NOT NULL,
    [create_time] DATETIME2 NOT NULL,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [input_tokens] BIGINT,
    [output_tokens] BIGINT
);

CREATE TABLE [dbo].[sw_agent_model] (
    [id] BIGINT NOT NULL,
    [provider_id] BIGINT NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [model_name] NVARCHAR(200) NOT NULL,
    [capabilities] NVARCHAR(100) DEFAULT N'CHAT' NOT NULL,
    [temperature] DECIMAL(4,2),
    [max_tokens] INT,
    [top_p] DECIMAL(4,2),
    [timeout_seconds] INT DEFAULT 30 NOT NULL,
    [retry_count] INT DEFAULT 0 NOT NULL,
    [enabled] BIT DEFAULT 1 NOT NULL,
    [group_key] NVARCHAR(100),
    [sort] INT DEFAULT 0 NOT NULL,
    [locked_until] DATETIME2,
    [quota_cooldown_seconds] INT DEFAULT 60 NOT NULL,
    [remark] NVARCHAR(500),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_model_config] (
    [id] BIGINT NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [protocol_type] NVARCHAR(32) NOT NULL,
    [base_url] NVARCHAR(500) NOT NULL,
    [model_name] NVARCHAR(100) NOT NULL,
    [api_key_cipher] NVARCHAR(MAX),
    [temperature] DECIMAL(4,2),
    [max_tokens] INT,
    [top_p] DECIMAL(4,2),
    [timeout_seconds] INT DEFAULT 30 NOT NULL,
    [retry_count] INT DEFAULT 0 NOT NULL,
    [enabled] SMALLINT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [group_key] NVARCHAR(100),
    [sort] INT DEFAULT 0 NOT NULL,
    [locked_until] DATETIME2,
    [quota_cooldown_seconds] INT DEFAULT 60 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_provider] (
    [id] BIGINT NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [api_format] NVARCHAR(30) NOT NULL,
    [base_url] NVARCHAR(500) NOT NULL,
    [api_key_cipher] NVARCHAR(MAX),
    [enabled] BIT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_session] (
    [id] BIGINT NOT NULL,
    [agent_model_config_id] BIGINT NOT NULL,
    [title] NVARCHAR(500),
    [status] NVARCHAR(20) DEFAULT N'ACTIVE' NOT NULL,
    [create_time] DATETIME2 NOT NULL,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_tool_call_log] (
    [id] BIGINT NOT NULL,
    [session_id] BIGINT NOT NULL,
    [tool_name] NVARCHAR(100) NOT NULL,
    [tool_call_args] NVARCHAR(MAX),
    [tool_call_result] NVARCHAR(MAX),
    [latency_ms] BIGINT,
    [create_time] DATETIME2 NOT NULL,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_agent_tool_external] (
    [id] BIGINT NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [description] NVARCHAR(500) NOT NULL,
    [input_schema] NVARCHAR(MAX),
    [url] NVARCHAR(500) NOT NULL,
    [http_method] NVARCHAR(10) DEFAULT N'POST' NOT NULL,
    [timeout_seconds] INT DEFAULT 30 NOT NULL,
    [enabled] BIT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [effect_scope] NVARCHAR(16)
);

CREATE TABLE [dbo].[sw_agent_tool_internal] (
    [id] BIGINT NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [description] NVARCHAR(500) NOT NULL,
    [input_schema] NVARCHAR(MAX),
    [bean_name] NVARCHAR(100) NOT NULL,
    [method_name] NVARCHAR(100) NOT NULL,
    [enabled] BIT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_action_ref] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [exec_id] BIGINT NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [trigger_id] NVARCHAR(64),
    [action_id] NVARCHAR(64) NOT NULL,
    [action_type] NVARCHAR(24) NOT NULL,
    [item_key] NVARCHAR(128) NOT NULL,
    [item_summary] NVARCHAR(MAX),
    [payload_json] NVARCHAR(MAX),
    [command_key] NVARCHAR(200) NOT NULL,
    [command_id] BIGINT,
    [target_def_key] NVARCHAR(128) NOT NULL,
    [target_form_key] NVARCHAR(128),
    [target_record_id] NVARCHAR(64),
    [target_instance_id] NVARCHAR(64),
    [status] NVARCHAR(20) NOT NULL,
    [error_text] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_approval_action] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64) NOT NULL,
    [actor_id] BIGINT NOT NULL,
    [action] NVARCHAR(20) NOT NULL,
    [opinion_form_id] NVARCHAR(200),
    [opinion_form_version] NVARCHAR(64),
    [initialization_summary] NVARCHAR(MAX),
    [opinion_data] NVARCHAR(MAX),
    [settlement_status] NVARCHAR(20),
    [command_id] BIGINT,
    [target_user_id] BIGINT,
    [proxy_for_user_id] BIGINT,
    [round_no] INT,
    [related_task_id] NVARCHAR(64),
    [detail] NVARCHAR(MAX),
    [opinion_form_snapshot] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_authorize_rule] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [principal_id] BIGINT NOT NULL,
    [agent_id] BIGINT NOT NULL,
    [scope_type] NVARCHAR(20) DEFAULT N'GLOBAL' NOT NULL,
    [process_def_key] NVARCHAR(200),
    [node_key] NVARCHAR(200),
    [business_key] NVARCHAR(200),
    [condition_json] NVARCHAR(MAX),
    [start_at] DATETIME2,
    [end_at] DATETIME2,
    [status] NVARCHAR(20) DEFAULT N'ACTIVE' NOT NULL,
    [detail] NVARCHAR(500)
);

CREATE TABLE [dbo].[sw_bpm_branch_trace] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [branch_id] NVARCHAR(200) NOT NULL,
    [condition_version] NVARCHAR(64) NOT NULL,
    [input_summary] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_category] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [sort_no] INT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_child_batch] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [batch_key] NVARCHAR(240) NOT NULL,
    [parent_instance_id] NVARCHAR(64) NOT NULL,
    [parent_def_key] NVARCHAR(128),
    [root_instance_id] NVARCHAR(64),
    [parent_depth] INT DEFAULT 0 NOT NULL,
    [trigger_id] NVARCHAR(64) NOT NULL,
    [action_id] NVARCHAR(64) NOT NULL,
    [round_no] BIGINT DEFAULT 0 NOT NULL,
    [wait_policy] NVARCHAR(16) NOT NULL,
    [wait_count] INT,
    [expected_count] INT DEFAULT 0 NOT NULL,
    [source_record_id] NVARCHAR(64),
    [source_record_version] BIGINT,
    [status] NVARCHAR(20) NOT NULL,
    [settled_count] INT DEFAULT 0 NOT NULL,
    [block_reason] NVARCHAR(MAX),
    [settled_at] DATETIME2,
    [config_json] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_child_item] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [batch_id] BIGINT NOT NULL,
    [item_key] NVARCHAR(128) NOT NULL,
    [action_ref_id] BIGINT,
    [source_row_id] NVARCHAR(64),
    [source_row_version] BIGINT,
    [source_rows_json] NVARCHAR(MAX),
    [source_summary] NVARCHAR(MAX),
    [target_def_key] NVARCHAR(128),
    [target_form_key] NVARCHAR(128),
    [target_record_id] NVARCHAR(64),
    [target_instance_id] NVARCHAR(64),
    [status] NVARCHAR(20) NOT NULL,
    [writeback_json] NVARCHAR(MAX),
    [writeback_time] DATETIME2,
    [writeback_source] NVARCHAR(200),
    [error_text] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_command] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [command_key] NVARCHAR(128) NOT NULL,
    [command_type] NVARCHAR(32) NOT NULL,
    [channel] NVARCHAR(16) DEFAULT N'NORMAL' NOT NULL,
    [status] NVARCHAR(16) DEFAULT N'PENDING' NOT NULL,
    [payload] NVARCHAR(MAX),
    [result] NVARCHAR(MAX),
    [failure_reason] NVARCHAR(1000),
    [retry_count] INT DEFAULT 0 NOT NULL,
    [next_retry_at] DATETIME2,
    [claimed_at] DATETIME2,
    [finished_at] DATETIME2,
    [initiator_id] BIGINT,
    [claim_token] NVARCHAR(64),
    [logical_command_id] NVARCHAR(200),
    [payload_fingerprint] NVARCHAR(64),
    [tier] NVARCHAR(30),
    [completion_point] NVARCHAR(200),
    [deadline_at] DATETIME2,
    [overdue_at] DATETIME2,
    [resource_class] NVARCHAR(10),
    [resource_units] INT,
    [resource_segment] NVARCHAR(20),
    [policy_version] INT,
    [resource_released_at] DATETIME2
);

CREATE TABLE [dbo].[sw_bpm_command_batch] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [batch_key] NVARCHAR(128) NOT NULL,
    [action_id] NVARCHAR(64) NOT NULL,
    [action_version] INT,
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [total_count] INT DEFAULT 0 NOT NULL,
    [succeeded_count] INT DEFAULT 0 NOT NULL,
    [failed_count] INT DEFAULT 0 NOT NULL,
    [command_id] BIGINT,
    [initiator_id] BIGINT
);

CREATE TABLE [dbo].[sw_bpm_command_batch_item] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [batch_id] BIGINT NOT NULL,
    [item_key] NVARCHAR(128) NOT NULL,
    [record_id] NVARCHAR(64),
    [quantity] NVARCHAR(40),
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [invocation_id] NVARCHAR(64),
    [error_code] INT,
    [error_msg] NVARCHAR(500),
    [attempt_count] INT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_command_effect] (
    [command_id] BIGINT NOT NULL,
    [logical_command_id] NVARCHAR(200),
    [claim_token] NVARCHAR(64),
    [result_json] NVARCHAR(MAX),
    [biz_ref] NVARCHAR(200),
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_communication] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64),
    [round_no] INT DEFAULT 1 NOT NULL,
    [initiator_id] BIGINT NOT NULL,
    [receiver_id] BIGINT NOT NULL,
    [message] NVARCHAR(MAX),
    [reply_message] NVARCHAR(MAX),
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [reply_time] DATETIME2
);

CREATE TABLE [dbo].[sw_bpm_consensus_vote] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64) NOT NULL,
    [actor_id] BIGINT NOT NULL,
    [outcome] NVARCHAR(20) NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_copy_record] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64),
    [recipient_id] NVARCHAR(64) NOT NULL,
    [delivery_status] NVARCHAR(30) NOT NULL,
    [failure_reason] NVARCHAR(500)
);

CREATE TABLE [dbo].[sw_bpm_draft] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [title] NVARCHAR(200),
    [form_key] NVARCHAR(128) NOT NULL,
    [form_version] BIGINT,
    [process_def_key] NVARCHAR(128),
    [payload] NVARCHAR(MAX),
    [status] NVARCHAR(20) DEFAULT N'EDITING' NOT NULL,
    [command_id] BIGINT,
    [submit_seq] INT DEFAULT 0 NOT NULL,
    [result_record_id] NVARCHAR(36),
    [last_error] NVARCHAR(1000)
);

CREATE TABLE [dbo].[sw_bpm_dynamic_branch] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(128) NOT NULL,
    [branch_index] INT NOT NULL,
    [source_type] NVARCHAR(32),
    [source_value] NVARCHAR(512),
    [dept_ids] NVARCHAR(512),
    [leader_id] BIGINT,
    [converge_mode] NVARCHAR(16) NOT NULL,
    [status] NVARCHAR(20) NOT NULL,
    [task_id] NVARCHAR(64),
    [cancel_reason] NVARCHAR(512),
    [semantic_version] SMALLINT,
    [object_type] NVARCHAR(16),
    [object_id] NVARCHAR(64),
    [round_no] BIGINT,
    [execution_id] NVARCHAR(64),
    [source_refs] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_ext_datasource] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [type] NVARCHAR(50) NOT NULL,
    [jdbc_url] NVARCHAR(500) NOT NULL,
    [driver_class] NVARCHAR(200) NOT NULL,
    [username] NVARCHAR(100) NOT NULL,
    [password_cipher] NVARCHAR(MAX) NOT NULL,
    [read_only] SMALLINT DEFAULT 1 NOT NULL,
    [enabled] SMALLINT DEFAULT 1 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_ext_datasource_grant] (
    [id] BIGINT NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [datasource_id] BIGINT NOT NULL,
    [subject_type] NVARCHAR(16) NOT NULL,
    [subject_key] NVARCHAR(64) NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_ext_sql_execution_audit] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [datasource_id] BIGINT NOT NULL,
    [datasource_name] NVARCHAR(100) NOT NULL,
    [sql_text] NVARCHAR(MAX) NOT NULL,
    [row_count] INT,
    [execution_time_ms] BIGINT,
    [success] SMALLINT DEFAULT 0 NOT NULL,
    [error_message] NVARCHAR(MAX),
    [operator_id] BIGINT NOT NULL,
    [operator_name] NVARCHAR(50) NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_form_binding] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [form_key] NVARCHAR(200) NOT NULL,
    [process_def_key] NVARCHAR(200) NOT NULL,
    [active] BIT DEFAULT 1 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_handover] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [from_user_id] BIGINT NOT NULL,
    [to_user_id] BIGINT NOT NULL,
    [scope_def_keys] NVARCHAR(1000),
    [include_proxy_rules] BIT DEFAULT 0 NOT NULL,
    [status] NVARCHAR(20) NOT NULL,
    [total_items] INT,
    [migrated_items] INT,
    [failed_items] INT,
    [operator_id] BIGINT NOT NULL,
    [executed_at] DATETIME2 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_handover_item] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [handover_id] BIGINT NOT NULL,
    [task_id] NVARCHAR(64),
    [process_instance_id] NVARCHAR(64),
    [item_type] NVARCHAR(20) NOT NULL,
    [before_assignee] BIGINT,
    [after_assignee] BIGINT,
    [result] NVARCHAR(30) NOT NULL,
    [fail_reason] NVARCHAR(500),
    [rule_id] BIGINT
);

CREATE TABLE [dbo].[sw_bpm_instance] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [process_def_key] NVARCHAR(200) NOT NULL,
    [business_key] NVARCHAR(36) NOT NULL,
    [form_key] NVARCHAR(200) NOT NULL,
    [initiator_id] BIGINT NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'RUNNING' NOT NULL,
    [def_version] INT,
    [theme] NVARCHAR(200)
);

CREATE TABLE [dbo].[sw_bpm_instance_intervention] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [action] NVARCHAR(20) NOT NULL,
    [operator_id] BIGINT NOT NULL,
    [reason] NVARCHAR(500),
    [before_state] NVARCHAR(20),
    [after_state] NVARCHAR(20),
    [from_assignee] BIGINT,
    [to_assignee] BIGINT,
    [affected_tasks] INT,
    [intervened_at] DATETIME2 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_node_function] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [func_key] NVARCHAR(128) NOT NULL,
    [func_version] INT NOT NULL,
    [func_type] NVARCHAR(32) NOT NULL,
    [impl_bean] NVARCHAR(128) NOT NULL,
    [config] NVARCHAR(MAX),
    [allowed_nodes] NVARCHAR(MAX),
    [timeout_ms] BIGINT DEFAULT 5000 NOT NULL,
    [failure_strategy] NVARCHAR(20) DEFAULT N'BLOCK' NOT NULL,
    [enabled] SMALLINT DEFAULT 1 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_node_function_audit] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [func_key] NVARCHAR(128) NOT NULL,
    [func_version] INT NOT NULL,
    [func_type] NVARCHAR(32) NOT NULL,
    [process_instance_id] NVARCHAR(64),
    [node_key] NVARCHAR(128),
    [idempotent_key] NVARCHAR(128),
    [actor_id] BIGINT,
    [outcome] NVARCHAR(32) NOT NULL,
    [error_code] INT,
    [summary] NVARCHAR(2000),
    [duration_ms] BIGINT
);

CREATE TABLE [dbo].[sw_bpm_participant_snapshot] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64) NOT NULL,
    [participant_id] NVARCHAR(64) NOT NULL,
    [participant_status] NVARCHAR(20) NOT NULL,
    [invalid_reason] NVARCHAR(500),
    [participant_name] NVARCHAR(100)
);

CREATE TABLE [dbo].[sw_bpm_process_def] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_key] NVARCHAR(200) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [form_key] NVARCHAR(200) NOT NULL,
    [def_version] INT DEFAULT 1 NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [deployment_id] NVARCHAR(64),
    [process_definition_id] NVARCHAR(64),
    [graph_json] NVARCHAR(MAX),
    [category_id] BIGINT,
    [iot_access_enabled] BIT DEFAULT 0 NOT NULL,
    [iot_device_action_json] NVARCHAR(MAX),
    [published_version] INT,
    [source_template_id] BIGINT,
    [source_template_version] INT,
    [theme_rule] NVARCHAR(200)
);

CREATE TABLE [dbo].[sw_bpm_process_def_version] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [def_id] BIGINT NOT NULL,
    [graph_version] INT NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'PUBLISHED' NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [form_key] NVARCHAR(200),
    [form_version] NVARCHAR(64),
    [function_versions] NVARCHAR(MAX),
    [graph_json] NVARCHAR(MAX),
    [deployment_id] NVARCHAR(64),
    [process_definition_id] NVARCHAR(64),
    [published_by] BIGINT,
    [published_at] DATETIME2
);

CREATE TABLE [dbo].[sw_bpm_process_favorite] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [user_id] BIGINT NOT NULL,
    [process_key] NVARCHAR(64) NOT NULL,
    [process_name] NVARCHAR(128),
    [create_by] BIGINT,
    [update_by] BIGINT
);

CREATE TABLE [dbo].[sw_bpm_process_template] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [category] NVARCHAR(100),
    [description] NVARCHAR(500),
    [form_key] NVARCHAR(200) NOT NULL,
    [graph_json] NVARCHAR(MAX),
    [template_version] INT DEFAULT 1 NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'ENABLED' NOT NULL,
    [scope_type] NVARCHAR(20) DEFAULT N'GLOBAL' NOT NULL,
    [scope_dept_id] BIGINT,
    [source_def_id] BIGINT,
    [source_def_version] INT
);

CREATE TABLE [dbo].[sw_bpm_resource_policy] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [policy_version] INT NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [enabled] BIT DEFAULT 0 NOT NULL,
    [stop_acceptance] BIT DEFAULT 0 NOT NULL,
    [global_max_outstanding] INT NOT NULL,
    [tenant_max_outstanding] INT NOT NULL,
    [prod_reserved] INT NOT NULL,
    [oa_reserved] INT NOT NULL,
    [shared_capacity] INT NOT NULL,
    [tenant_rate_per_sec] INT NOT NULL,
    [tenant_burst] INT NOT NULL,
    [realtime_global_concurrency] INT NOT NULL,
    [realtime_tenant_concurrency] INT NOT NULL,
    [batch_slice_items] INT DEFAULT 25 NOT NULL,
    [batch_poll_claim_limit] INT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500)
);

CREATE TABLE [dbo].[sw_bpm_resource_reject_log] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [policy_version] INT,
    [resource_class] NVARCHAR(10),
    [reject_scope] NVARCHAR(20) NOT NULL,
    [requested_units] INT DEFAULT 0 NOT NULL,
    [reason_code] NVARCHAR(80),
    [detail] NVARCHAR(500),
    [command_key] NVARCHAR(200)
);

CREATE TABLE [dbo].[sw_bpm_resource_usage] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [scope] NVARCHAR(16) NOT NULL,
    [scope_key] BIGINT DEFAULT 0 NOT NULL,
    [segment] NVARCHAR(20) NOT NULL,
    [outstanding] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_sign_record] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64) NOT NULL,
    [sign_type] NVARCHAR(20) NOT NULL,
    [mode_type] NVARCHAR(10) DEFAULT N'SERIAL' NOT NULL,
    [operator_id] BIGINT NOT NULL,
    [participant_id] BIGINT NOT NULL,
    [seq_no] INT DEFAULT 0 NOT NULL,
    [sign_status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [result_status] NVARCHAR(20),
    [cancel_reason] NVARCHAR(500),
    [original_task_id] NVARCHAR(64),
    [original_status] NVARCHAR(30),
    [detail] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_task_deadline] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64) NOT NULL,
    [due_at] DATETIME2 NOT NULL,
    [remind_fired] SMALLINT DEFAULT 0 NOT NULL,
    [escalation_fired] SMALLINT DEFAULT 0 NOT NULL,
    [auto_action] NVARCHAR(20),
    [auto_action_config] NVARCHAR(MAX),
    [run_state] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [result_status] NVARCHAR(20),
    [last_reason] NVARCHAR(500),
    [handled_at] DATETIME2
);

CREATE TABLE [dbo].[sw_bpm_task_form_data] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [process_def_key] NVARCHAR(128),
    [node_key] NVARCHAR(200) NOT NULL,
    [task_id] NVARCHAR(64) NOT NULL,
    [round_no] BIGINT DEFAULT 0 NOT NULL,
    [form_key] NVARCHAR(128) NOT NULL,
    [form_version] BIGINT NOT NULL,
    [status] NVARCHAR(16) NOT NULL,
    [data_text] NVARCHAR(MAX),
    [submitted_by] BIGINT,
    [submit_time] DATETIME2
);

CREATE TABLE [dbo].[sw_bpm_task_opinion_draft] (
    [id] BIGINT NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [task_id] NVARCHAR(64) NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [node_key] NVARCHAR(128),
    [round_no] INT DEFAULT 1 NOT NULL,
    [assignee_id] BIGINT NOT NULL,
    [opinion_form_id] NVARCHAR(64),
    [opinion_form_version] NVARCHAR(64),
    [opinion_comment] NVARCHAR(MAX),
    [opinion_data] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_bpm_theme_seq] (
    [process_key] NVARCHAR(64) NOT NULL,
    [next_val] BIGINT DEFAULT 1 NOT NULL
);

CREATE TABLE [dbo].[sw_bpm_trigger_exec] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [process_def_key] NVARCHAR(128),
    [def_version] INT,
    [trigger_id] NVARCHAR(64) NOT NULL,
    [event_type] NVARCHAR(32) NOT NULL,
    [node_key] NVARCHAR(200),
    [round_no] BIGINT DEFAULT 0 NOT NULL,
    [exec_key] NVARCHAR(200) NOT NULL,
    [status] NVARCHAR(20) NOT NULL,
    [result_value] NVARCHAR(512),
    [result_type] NVARCHAR(16),
    [matched_branch_id] NVARCHAR(64),
    [disposition] NVARCHAR(32),
    [error_text] NVARCHAR(MAX),
    [snapshot_text] NVARCHAR(MAX),
    [duration_ms] BIGINT
);

CREATE TABLE [dbo].[sw_bpm_urge_record] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [initiator_id] BIGINT NOT NULL,
    [target_user_id] BIGINT,
    [result] NVARCHAR(20) NOT NULL,
    [detail] NVARCHAR(500),
    [cooldown_key] NVARCHAR(128)
);

CREATE TABLE [dbo].[sw_form_app] (
    [id] NVARCHAR(36) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [icon] NVARCHAR(64),
    [color] NVARCHAR(32),
    [sort_no] INT DEFAULT 0 NOT NULL,
    [visible_scope_type] NVARCHAR(20) DEFAULT N'SPECIFIED' NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'ENABLED' NOT NULL,
    [source] NVARCHAR(20) DEFAULT N'MANUAL' NOT NULL,
    [legacy_category_id] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_app_dir] (
    [id] NVARCHAR(36) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(36) NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [sort_no] INT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_app_import_record] (
    [id] NVARCHAR(36) NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [package_name] NVARCHAR(200) NOT NULL,
    [file_name] NVARCHAR(255),
    [app_id] NVARCHAR(36),
    [app_name] NVARCHAR(200),
    [result] NVARCHAR(20) NOT NULL,
    [report_json] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_form_app_scope] (
    [id] NVARCHAR(36) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(36) NOT NULL,
    [scope_type] NVARCHAR(20) NOT NULL,
    [scope_id] NVARCHAR(64) NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_app_user] (
    [id] NVARCHAR(36) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(36) NOT NULL,
    [user_id] BIGINT NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_c1_policy] (
    [id] NVARCHAR(36) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [enabled] SMALLINT DEFAULT 0 NOT NULL,
    [policy_json] NVARCHAR(MAX),
    [applied_at] DATETIME2,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_config] (
    [id] NVARCHAR(36) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [definition] NVARCHAR(MAX) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [table_name] NVARCHAR(200),
    [parent_table] NVARCHAR(200)
);

CREATE TABLE [dbo].[sw_form_def] (
    [id] NVARCHAR(36) NOT NULL,
    [form_key] NVARCHAR(100) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [logical_table_name] NVARCHAR(100),
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [physical_table_name] NVARCHAR(100),
    [form_version] INT DEFAULT 1 NOT NULL,
    [description] NVARCHAR(500),
    [sub_table_mapping] NVARCHAR(MAX),
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [visibility_scope] NVARCHAR(MAX),
    [form_type] NVARCHAR(20) DEFAULT N'BUSINESS' NOT NULL,
    [app_id] NVARCHAR(36),
    [dir_id] NVARCHAR(36)
);

CREATE TABLE [dbo].[sw_form_ext_query] (
    [id] NVARCHAR(36) NOT NULL,
    [datasource_id] BIGINT NOT NULL,
    [query_key] NVARCHAR(64) NOT NULL,
    [query_version] INT DEFAULT 1 NOT NULL,
    [sql_text] NVARCHAR(MAX) NOT NULL,
    [output_fields] NVARCHAR(MAX) NOT NULL,
    [enabled] SMALLINT DEFAULT 1 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_integration_invocation] (
    [id] NVARCHAR(36) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [form_key] NVARCHAR(128) NOT NULL,
    [form_version] INT NOT NULL,
    [capability_id] NVARCHAR(64) NOT NULL,
    [phase] NVARCHAR(32) NOT NULL,
    [request_key] NVARCHAR(128) NOT NULL,
    [request_hash] NVARCHAR(64),
    [subject_id] BIGINT NOT NULL,
    [status] NVARCHAR(16) NOT NULL,
    [result_json] NVARCHAR(MAX),
    [error_message] NVARCHAR(500),
    [latency_ms] BIGINT
);

CREATE TABLE [dbo].[sw_form_lifecycle_audit] (
    [id] NVARCHAR(36) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [action] NVARCHAR(20) NOT NULL,
    [reason] NVARCHAR(500),
    [operator_id] BIGINT,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_list_config] (
    [id] NVARCHAR(36) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [config_json] NVARCHAR(MAX) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_snapshot] (
    [id] NVARCHAR(36) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [form_version] INT NOT NULL,
    [definition] NVARCHAR(MAX) NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_trace] (
    [id] NVARCHAR(36) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [record_id] NVARCHAR(36) NOT NULL,
    [submit_user_id] BIGINT NOT NULL,
    [submit_ip] NVARCHAR(200),
    [submit_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [device_fingerprint] NVARCHAR(200),
    [user_agent] NVARCHAR(500),
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [submit_idempotency_key] NVARCHAR(128)
);

CREATE TABLE [dbo].[sw_form_txn_action] (
    [id] NVARCHAR(36) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [action_key] NVARCHAR(100) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [action_type] NVARCHAR(20) NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [current_version] INT,
    [config_json] NVARCHAR(MAX),
    [description] NVARCHAR(500),
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_txn_action_version] (
    [id] NVARCHAR(36) NOT NULL,
    [action_id] NVARCHAR(36) NOT NULL,
    [version_no] INT NOT NULL,
    [form_version] INT NOT NULL,
    [config_json] NVARCHAR(MAX) NOT NULL,
    [published_by] BIGINT,
    [published_at] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_txn_invocation] (
    [id] NVARCHAR(36) NOT NULL,
    [action_id] NVARCHAR(36) NOT NULL,
    [action_version] INT NOT NULL,
    [invocation_key] NVARCHAR(200) NOT NULL,
    [request_hash] NVARCHAR(64) NOT NULL,
    [biz_record_id] NVARCHAR(64),
    [status] NVARCHAR(20) NOT NULL,
    [error_code] INT,
    [error_msg] NVARCHAR(500),
    [result_json] NVARCHAR(MAX),
    [duration_ms] BIGINT,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_txn_ledger] (
    [id] NVARCHAR(36) NOT NULL,
    [action_id] NVARCHAR(36) NOT NULL,
    [action_version] INT NOT NULL,
    [invocation_id] NVARCHAR(36) NOT NULL,
    [reservation_id] NVARCHAR(36),
    [entry_type] NVARCHAR(20) NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [record_id] NVARCHAR(64) NOT NULL,
    [quantity] DECIMAL(20,6) NOT NULL,
    [balance_after] DECIMAL(20,6),
    [reserved_after] DECIMAL(20,6),
    [biz_keys_json] NVARCHAR(MAX),
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_form_txn_reservation] (
    [id] NVARCHAR(36) NOT NULL,
    [action_id] NVARCHAR(36) NOT NULL,
    [action_version] INT NOT NULL,
    [form_id] NVARCHAR(36) NOT NULL,
    [record_id] NVARCHAR(64) NOT NULL,
    [biz_keys_json] NVARCHAR(MAX),
    [quantity] DECIMAL(20,6) NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'ACTIVE' NOT NULL,
    [expires_at] DATETIME2 NOT NULL,
    [reserve_invocation_id] NVARCHAR(36),
    [settle_invocation_id] NVARCHAR(36),
    [settled_at] DATETIME2,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_iot_audit_record] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [action] NVARCHAR(64) NOT NULL,
    [object_type] NVARCHAR(64) NOT NULL,
    [object_id] NVARCHAR(128) NOT NULL,
    [result] NVARCHAR(32) NOT NULL,
    [actor_id] BIGINT NOT NULL,
    [system_identity] NVARCHAR(128) NOT NULL,
    [action_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [correlation_id] NVARCHAR(128) NOT NULL,
    [detail] NVARCHAR(1000)
);

CREATE TABLE [dbo].[sw_iot_command] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [device_id] BIGINT NOT NULL,
    [conn_id] BIGINT,
    [provider] NVARCHAR(20) DEFAULT N'MQTT' NOT NULL,
    [capability_type] NVARCHAR(20) NOT NULL,
    [capability_id] NVARCHAR(64),
    [params_json] NVARCHAR(MAX),
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [result_json] NVARCHAR(MAX),
    [error] NVARCHAR(500),
    [idempotent_key] NVARCHAR(128),
    [source_type] NVARCHAR(20) DEFAULT N'MANUAL' NOT NULL,
    [source_ref] NVARCHAR(128),
    [flow_instance_id] NVARCHAR(64),
    [flow_task_id] NVARCHAR(64),
    [qos] INT DEFAULT 1 NOT NULL,
    [sent_time] DATETIME2,
    [reply_time] DATETIME2,
    [timeout_ms] INT DEFAULT 30000 NOT NULL,
    [correlation_id] NVARCHAR(128)
);

CREATE TABLE [dbo].[sw_iot_command_reservation] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [process_instance_id] NVARCHAR(64) NOT NULL,
    [process_def_key] NVARCHAR(128),
    [def_version] INT,
    [form_key] NVARCHAR(128),
    [record_id] NVARCHAR(36),
    [device_key] NVARCHAR(128),
    [product_id] NVARCHAR(128),
    [device_name] NVARCHAR(128),
    [command_key] NVARCHAR(128),
    [command_type] NVARCHAR(16),
    [payload] NVARCHAR(MAX),
    [due_at_utc] DATETIME2 NOT NULL,
    [timezone_id] NVARCHAR(64) NOT NULL,
    [due_local_text] NVARCHAR(64),
    [late_window_seconds] INT NOT NULL,
    [status] NVARCHAR(20) NOT NULL,
    [command_id] BIGINT,
    [reject_reason] NVARCHAR(512),
    [cancel_by] BIGINT,
    [cancel_reason] NVARCHAR(512),
    [cancel_time] DATETIME2
);

CREATE TABLE [dbo].[sw_iot_connection] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [code] NVARCHAR(64) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [conn_type] NVARCHAR(20) NOT NULL,
    [enabled] SMALLINT DEFAULT 1 NOT NULL,
    [health_status] NVARCHAR(20) DEFAULT N'UNKNOWN' NOT NULL,
    [last_check_time] DATETIME2,
    [last_check_result] NVARCHAR(500),
    [host] NVARCHAR(200),
    [port] INT,
    [use_tls] SMALLINT DEFAULT 0 NOT NULL,
    [client_id_prefix] NVARCHAR(64),
    [username] NVARCHAR(128),
    [password_cipher] NVARCHAR(512),
    [keepalive] INT DEFAULT 60 NOT NULL,
    [clean_session] SMALLINT DEFAULT 1 NOT NULL,
    [reconnect_min_sec] INT DEFAULT 1 NOT NULL,
    [reconnect_max_sec] INT DEFAULT 60 NOT NULL,
    [region] NVARCHAR(64),
    [endpoint] NVARCHAR(200),
    [secret_ref] NVARCHAR(256),
    [ext_json] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_iot_device] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [device_key] NVARCHAR(64) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [device_type] NVARCHAR(50),
    [status] NVARCHAR(20) DEFAULT N'OFFLINE' NOT NULL,
    [last_online_time] DATETIME2,
    [product_id] NVARCHAR(64) DEFAULT N'unknown',
    [device_name] NVARCHAR(128) DEFAULT N'unknown',
    [tencent_status] NVARCHAR(32) DEFAULT N'offline',
    [connection_id] BIGINT,
    [product_ref_id] BIGINT,
    [manage_status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [process_access_enabled] SMALLINT DEFAULT 0 NOT NULL,
    [last_report_time] DATETIME2,
    [last_command_time] DATETIME2,
    [labels] NVARCHAR(500),
    [ext_json] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_iot_device_command] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [device_key] NVARCHAR(64) NOT NULL,
    [command_key] NVARCHAR(64) NOT NULL,
    [payload] NVARCHAR(1000),
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [result] NVARCHAR(1000),
    [approval_biz_id] NVARCHAR(64),
    [product_id] NVARCHAR(64) DEFAULT N'unknown' NOT NULL,
    [device_name] NVARCHAR(128) DEFAULT N'unknown' NOT NULL,
    [command_type] NVARCHAR(32) DEFAULT N'PROPERTY',
    [semantic_mode] NVARCHAR(32) DEFAULT N'DEFERRED',
    [idempotent_key] NVARCHAR(128),
    [expiry_time] DATETIME2,
    [retry_count] INT DEFAULT 0,
    [last_error] NVARCHAR(512),
    [tencent_request_id] NVARCHAR(128),
    [client_token] NVARCHAR(128),
    [device_output] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_iot_event_record] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [device_id] BIGINT NOT NULL,
    [event_id] NVARCHAR(64) NOT NULL,
    [payload] NVARCHAR(MAX),
    [occur_time] DATETIME2 NOT NULL,
    [correlation_id] NVARCHAR(128)
);

CREATE TABLE [dbo].[sw_iot_event_rule] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [code] NVARCHAR(64) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [device_id] BIGINT NOT NULL,
    [rule_type] NVARCHAR(20) NOT NULL,
    [condition_json] NVARCHAR(MAX),
    [debounce_ms] BIGINT DEFAULT 0 NOT NULL,
    [cooldown_ms] BIGINT DEFAULT 0 NOT NULL,
    [continuous_count] INT DEFAULT 1 NOT NULL,
    [script_id] BIGINT,
    [process_template_key] NVARCHAR(128),
    [form_mapping_json] NVARCHAR(MAX),
    [process_enabled] SMALLINT DEFAULT 0 NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [rule_version] INT DEFAULT 1 NOT NULL,
    [last_fired_time] DATETIME2,
    [agent_enabled] SMALLINT DEFAULT 0 NOT NULL,
    [agent_graph_key] NVARCHAR(64),
    [agent_input_mapping_json] NVARCHAR(MAX),
    [agent_failure_template_key] NVARCHAR(128),
    [agent_failure_config_json] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_iot_message_log] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [conn_id] BIGINT,
    [device_id] BIGINT,
    [topic] NVARCHAR(200),
    [direction] NVARCHAR(10) DEFAULT N'UP' NOT NULL,
    [message_id] NVARCHAR(128),
    [dedup_key] NVARCHAR(128),
    [payload] NVARCHAR(MAX),
    [payload_type] NVARCHAR(20),
    [parse_status] NVARCHAR(20) DEFAULT N'RECEIVED' NOT NULL,
    [parse_error] NVARCHAR(500),
    [qos] INT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_iot_process_trigger] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [rule_id] BIGINT,
    [script_id] BIGINT,
    [device_id] BIGINT,
    [idempotent_key] NVARCHAR(128) NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [process_instance_id] NVARCHAR(64),
    [form_snapshot] NVARCHAR(MAX),
    [error] NVARCHAR(500),
    [trigger_time] DATETIME2 NOT NULL,
    [process_template_key] NVARCHAR(128),
    [trigger_source] NVARCHAR(20),
    [configured_by] BIGINT,
    [retry_count] INT DEFAULT 0 NOT NULL,
    [next_retry_time] DATETIME2,
    [action_type] NVARCHAR(16) DEFAULT N'FLOW' NOT NULL,
    [agent_graph_key] NVARCHAR(64),
    [agent_execution_id] BIGINT
);

CREATE TABLE [dbo].[sw_iot_product] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [code] NVARCHAR(64) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [conn_id] BIGINT,
    [conn_type] NVARCHAR(20) DEFAULT N'MQTT' NOT NULL,
    [model_status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [published_model_id] BIGINT,
    [description] NVARCHAR(500)
);

CREATE TABLE [dbo].[sw_iot_property_record] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [device_id] BIGINT NOT NULL,
    [property_id] NVARCHAR(64) NOT NULL,
    [value_json] NVARCHAR(1000),
    [pre_value_json] NVARCHAR(1000),
    [report_time] DATETIME2 NOT NULL
);

CREATE TABLE [dbo].[sw_iot_script] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [code] NVARCHAR(64) NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [language] NVARCHAR(10) NOT NULL,
    [trigger_type] NVARCHAR(20) DEFAULT N'MESSAGE' NOT NULL,
    [binding_json] NVARCHAR(MAX),
    [input_schema] NVARCHAR(MAX),
    [output_schema] NVARCHAR(MAX),
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [current_version] INT DEFAULT 1 NOT NULL,
    [published_version] INT,
    [timeout_ms] INT DEFAULT 5000 NOT NULL
);

CREATE TABLE [dbo].[sw_iot_script_exec] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [script_id] BIGINT NOT NULL,
    [script_version] INT NOT NULL,
    [trigger_source] NVARCHAR(20),
    [trigger_ref] NVARCHAR(128),
    [device_ref] NVARCHAR(128),
    [status] NVARCHAR(20) NOT NULL,
    [input_json] NVARCHAR(MAX),
    [output_json] NVARCHAR(MAX),
    [error] NVARCHAR(1000),
    [duration_ms] BIGINT,
    [side_effect] SMALLINT DEFAULT 0 NOT NULL,
    [idempotent_key] NVARCHAR(128),
    [correlation_id] NVARCHAR(128)
);

CREATE TABLE [dbo].[sw_iot_script_version] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [script_id] BIGINT NOT NULL,
    [script_version] INT NOT NULL,
    [source_code] NVARCHAR(MAX),
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [publish_time] DATETIME2
);

CREATE TABLE [dbo].[sw_iot_thing_model] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [product_id] BIGINT NOT NULL,
    [model_version] INT NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'DRAFT' NOT NULL,
    [content_json] NVARCHAR(MAX),
    [publish_time] DATETIME2
);

CREATE TABLE [dbo].[sw_iot_topic] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [conn_id] BIGINT NOT NULL,
    [product_id] BIGINT,
    [topic] NVARCHAR(200) NOT NULL,
    [direction] NVARCHAR(10) DEFAULT N'UP' NOT NULL,
    [qos] INT DEFAULT 1 NOT NULL,
    [retain] SMALLINT DEFAULT 0 NOT NULL,
    [payload_type] NVARCHAR(20) DEFAULT N'RAW' NOT NULL,
    [mapping_json] NVARCHAR(MAX),
    [enabled] SMALLINT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500)
);

CREATE TABLE [dbo].[sw_job_info] (
    [id] BIGINT NOT NULL,
    [job_name] NVARCHAR(128) NOT NULL,
    [job_group] NVARCHAR(128) DEFAULT N'DEFAULT' NOT NULL,
    [job_type] NVARCHAR(16) DEFAULT N'BEAN' NOT NULL,
    [cron_expression] NVARCHAR(128) NOT NULL,
    [status] NVARCHAR(16) DEFAULT N'NORMAL' NOT NULL,
    [concurrent] SMALLINT DEFAULT 0 NOT NULL,
    [misfire_policy] SMALLINT DEFAULT 0 NOT NULL,
    [description] NVARCHAR(512),
    [bean_name] NVARCHAR(256),
    [bean_params] NVARCHAR(MAX),
    [flow_def_key] NVARCHAR(128),
    [form_data] NVARCHAR(MAX),
    [last_fire_time] DATETIME2,
    [next_fire_time] DATETIME2,
    [create_time] DATETIME2,
    [create_by] BIGINT,
    [update_time] DATETIME2,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT
);

CREATE TABLE [dbo].[sw_job_log] (
    [id] BIGINT NOT NULL,
    [job_id] BIGINT NOT NULL,
    [job_name] NVARCHAR(128) NOT NULL,
    [job_group] NVARCHAR(128) DEFAULT N'DEFAULT' NOT NULL,
    [trigger_type] NVARCHAR(16) DEFAULT N'AUTO' NOT NULL,
    [job_params] NVARCHAR(MAX),
    [exec_status] NVARCHAR(16) DEFAULT N'RUNNING' NOT NULL,
    [start_time] DATETIME2,
    [end_time] DATETIME2,
    [duration] BIGINT,
    [result_msg] NVARCHAR(MAX),
    [exception_stack] NVARCHAR(MAX),
    [create_time] DATETIME2,
    [create_by] BIGINT,
    [update_time] DATETIME2,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT
);

CREATE TABLE [dbo].[sw_knowledge_base] (
    [id] BIGINT NOT NULL,
    [name] NVARCHAR(200) NOT NULL,
    [description] NVARCHAR(500),
    [embedding_model_id] BIGINT NOT NULL,
    [chunk_size] INT DEFAULT 800 NOT NULL,
    [chunk_overlap] INT DEFAULT 100 NOT NULL,
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_knowledge_chunk] (
    [id] BIGINT NOT NULL,
    [kb_id] BIGINT NOT NULL,
    [document_id] BIGINT NOT NULL,
    [chunk_index] INT NOT NULL,
    [content] NVARCHAR(MAX) NOT NULL,
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_knowledge_document] (
    [id] BIGINT NOT NULL,
    [kb_id] BIGINT NOT NULL,
    [name] NVARCHAR(255) NOT NULL,
    [file_name] NVARCHAR(255),
    [file_type] NVARCHAR(100),
    [file_size] BIGINT,
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [error_message] NVARCHAR(MAX),
    [chunk_count] INT DEFAULT 0 NOT NULL,
    [content] NVARCHAR(MAX),
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sw_knowledge_vector] (
    [id] BIGINT NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [kb_id] BIGINT NOT NULL,
    [document_id] BIGINT NOT NULL,
    [chunk_id] BIGINT NOT NULL,
    [embedding_text] NVARCHAR(MAX) NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL
);

CREATE TABLE [dbo].[sw_notify_channel_config] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [channel] NVARCHAR(40) NOT NULL,
    [enabled] BIT DEFAULT 0 NOT NULL,
    [sender_display] NVARCHAR(200),
    [config_summary] NVARCHAR(500),
    [config_cipher] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sw_notify_message] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [recipient_id] BIGINT NOT NULL,
    [title] NVARCHAR(200) NOT NULL,
    [content] NVARCHAR(MAX) NOT NULL,
    [biz_type] NVARCHAR(30) NOT NULL,
    [biz_id] NVARCHAR(64),
    [is_read] BIT DEFAULT 0 NOT NULL,
    [channel] NVARCHAR(40) DEFAULT N'IN_APP' NOT NULL,
    [delivery_status] NVARCHAR(20) DEFAULT N'SUCCESS' NOT NULL,
    [external_message_id] NVARCHAR(200),
    [failure_reason] NVARCHAR(500),
    [idempotency_key] NVARCHAR(200),
    [event_type] NVARCHAR(40) DEFAULT N'SYSTEM' NOT NULL,
    [occurrence_no] BIGINT DEFAULT 1 NOT NULL,
    [template_id] BIGINT,
    [template_version] INT,
    [link_type] NVARCHAR(32),
    [link_id] NVARCHAR(64),
    [retry_count] INT DEFAULT 0 NOT NULL,
    [next_retry_time] DATETIME2,
    [failure_class] NVARCHAR(40),
    [receipt_digest] NVARCHAR(200)
);

CREATE TABLE [dbo].[sw_notify_rule] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [rule_code] NVARCHAR(100) NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [event_type] NVARCHAR(40) NOT NULL,
    [channel_priority] NVARCHAR(200) DEFAULT N'IN_APP' NOT NULL,
    [recipient_rule] NVARCHAR(500) NOT NULL,
    [required_flag] SMALLINT DEFAULT 0 NOT NULL,
    [failure_policy] NVARCHAR(20) DEFAULT N'RETRY' NOT NULL,
    [enabled] BIT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500)
);

CREATE TABLE [dbo].[sw_notify_send_attempt] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [message_id] BIGINT NOT NULL,
    [attempt_no] INT DEFAULT 1 NOT NULL,
    [channel] NVARCHAR(32),
    [status] NVARCHAR(20) NOT NULL,
    [failure_reason] NVARCHAR(500),
    [external_message_id] NVARCHAR(200),
    [failure_class] NVARCHAR(40),
    [started_at] DATETIME2,
    [finished_at] DATETIME2
);

CREATE TABLE [dbo].[sw_notify_subject_binding] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [user_id] BIGINT NOT NULL,
    [provider] NVARCHAR(40) NOT NULL,
    [subject_cipher] NVARCHAR(500) NOT NULL,
    [subject_digest] NVARCHAR(128) NOT NULL,
    [bind_status] NVARCHAR(20) DEFAULT N'ACTIVE' NOT NULL
);

CREATE TABLE [dbo].[sw_notify_subscription] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [user_id] BIGINT NOT NULL,
    [event_type] NVARCHAR(40) NOT NULL,
    [channel] NVARCHAR(40) DEFAULT N'IN_APP' NOT NULL,
    [enabled] BIT DEFAULT 1 NOT NULL
);

CREATE TABLE [dbo].[sw_notify_template] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [template_code] NVARCHAR(100) NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [title_template] NVARCHAR(200) NOT NULL,
    [content_template] NVARCHAR(MAX) NOT NULL,
    [enabled] BIT DEFAULT 1 NOT NULL,
    [remark] NVARCHAR(500),
    [event_type] NVARCHAR(40) DEFAULT N'SYSTEM' NOT NULL,
    [channel] NVARCHAR(40) DEFAULT N'IN_APP' NOT NULL,
    [variables_allowed] NVARCHAR(1000),
    [jump_ref] NVARCHAR(200)
);

CREATE TABLE [dbo].[sw_notify_template_version] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [template_id] BIGINT NOT NULL,
    [template_version] INT DEFAULT 1 NOT NULL,
    [event_type] NVARCHAR(40) DEFAULT N'SYSTEM' NOT NULL,
    [channel] NVARCHAR(40) DEFAULT N'IN_APP' NOT NULL,
    [title_template] NVARCHAR(200) NOT NULL,
    [content_template] NVARCHAR(MAX) NOT NULL,
    [variables_allowed] NVARCHAR(1000),
    [jump_ref] NVARCHAR(200),
    [status] NVARCHAR(20) DEFAULT N'RELEASED' NOT NULL
);

CREATE TABLE [dbo].[sw_openapi_app] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(64) NOT NULL,
    [app_name] NVARCHAR(200) NOT NULL,
    [secret_hash] NVARCHAR(128) NOT NULL,
    [scopes] NVARCHAR(200) NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'ENABLED' NOT NULL,
    [act_as_user_id] BIGINT NOT NULL,
    [callback_url] NVARCHAR(500),
    [callback_secret_hash] NVARCHAR(128)
);

CREATE TABLE [dbo].[sw_openapi_callback_log] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(64) NOT NULL,
    [event] NVARCHAR(50) NOT NULL,
    [biz_ref] NVARCHAR(64) NOT NULL,
    [url] NVARCHAR(500),
    [attempt] INT NOT NULL,
    [status] NVARCHAR(20) NOT NULL,
    [response_summary] NVARCHAR(500),
    [delivered_at] DATETIME2
);

CREATE TABLE [dbo].[sw_openapi_callback_task] (
    [id] BIGINT NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [app_id] NVARCHAR(64) NOT NULL,
    [event] NVARCHAR(50) NOT NULL,
    [biz_ref] NVARCHAR(64) NOT NULL,
    [status] NVARCHAR(20) DEFAULT N'PENDING' NOT NULL,
    [attempts] INT DEFAULT 0 NOT NULL,
    [next_retry_time] DATETIME2,
    [last_error] NVARCHAR(500),
    [delivered_at] DATETIME2
);

CREATE TABLE [dbo].[sw_openapi_idempotency] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(64) NOT NULL,
    [idem_key] NVARCHAR(200) NOT NULL,
    [result_ref] NVARCHAR(64)
);

CREATE TABLE [dbo].[sw_openapi_nonce] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(64) NOT NULL,
    [nonce] NVARCHAR(128) NOT NULL,
    [expire_at] DATETIME2 NOT NULL
);

CREATE TABLE [dbo].[sw_storage_file] (
    [id] BIGINT NOT NULL,
    [original_name] NVARCHAR(512) NOT NULL,
    [storage_key] NVARCHAR(512) NOT NULL,
    [storage_name] NVARCHAR(512) NOT NULL,
    [file_size] BIGINT DEFAULT 0 NOT NULL,
    [content_type] NVARCHAR(255),
    [file_ext] NVARCHAR(32),
    [provider_type] NVARCHAR(32) DEFAULT N'local' NOT NULL,
    [bucket_name] NVARCHAR(255),
    [storage_url] NVARCHAR(1024),
    [create_time] DATETIME2,
    [create_by] BIGINT,
    [update_time] DATETIME2,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT
);

CREATE TABLE [dbo].[sys_dept] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [parent_id] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [code] NVARCHAR(50),
    [sort] INT DEFAULT 0 NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [description] NVARCHAR(MAX),
    [leader_id] BIGINT
);

CREATE TABLE [dbo].[sys_dict_data] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [dict_code] NVARCHAR(50) NOT NULL,
    [label] NVARCHAR(100) NOT NULL,
    [dict_value] NVARCHAR(100) NOT NULL,
    [sort] INT DEFAULT 0 NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [is_default] SMALLINT DEFAULT 0 NOT NULL,
    [css_class] NVARCHAR(50),
    [list_class] NVARCHAR(50),
    [description] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sys_dict_type] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [code] NVARCHAR(50) NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [description] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sys_menu] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [parent_id] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(64) NOT NULL,
    [menu_type] SMALLINT DEFAULT 0 NOT NULL,
    [path] NVARCHAR(128),
    [component] NVARCHAR(255),
    [permission] NVARCHAR(128),
    [icon] NVARCHAR(64),
    [sort] INT DEFAULT 0 NOT NULL,
    [title] NVARCHAR(64) NOT NULL,
    [hidden] BIT NOT NULL
);

CREATE TABLE [dbo].[sys_post] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [code] NVARCHAR(50) NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [sort] INT DEFAULT 0 NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [description] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sys_post_delegate] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] INT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [source_post_id] BIGINT NOT NULL,
    [target_post_id] BIGINT NOT NULL,
    [scope_type] NVARCHAR(16) NOT NULL,
    [dept_id] BIGINT,
    [status] NVARCHAR(16) NOT NULL,
    [remark] NVARCHAR(500)
);

CREATE TABLE [dbo].[sys_refresh_token] (
    [id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL,
    [token_hash] NVARCHAR(128) NOT NULL,
    [expires_at] DATETIME2 NOT NULL,
    [revoked] SMALLINT DEFAULT 0 NOT NULL,
    [create_time] DATETIME2,
    [create_by] BIGINT,
    [update_time] DATETIME2,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT
);

CREATE TABLE [dbo].[sys_role] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(64) NOT NULL,
    [code] NVARCHAR(64) NOT NULL,
    [sort] INT DEFAULT 0 NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [data_scope] SMALLINT,
    [built_in] BIT DEFAULT 0 NOT NULL,
    [remark] NVARCHAR(255)
);

CREATE TABLE [dbo].[sys_role_dept] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [role_id] BIGINT NOT NULL,
    [dept_id] BIGINT NOT NULL
);

CREATE TABLE [dbo].[sys_role_menu] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [role_id] BIGINT NOT NULL,
    [menu_id] BIGINT NOT NULL
);

CREATE TABLE [dbo].[sys_sso_audit_record] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [provider] NVARCHAR(20) NOT NULL,
    [event_type] NVARCHAR(40) NOT NULL,
    [result] NVARCHAR(20) NOT NULL,
    [actor_id] BIGINT,
    [local_user_id] BIGINT,
    [external_digest] NVARCHAR(64),
    [detail] NVARCHAR(1000)
);

CREATE TABLE [dbo].[sys_sso_auth_state] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [state_value] NVARCHAR(128) NOT NULL,
    [provider] NVARCHAR(20) NOT NULL,
    [nonce] NVARCHAR(128),
    [redirect_path] NVARCHAR(500),
    [consumed] SMALLINT DEFAULT 0 NOT NULL,
    [expire_at] DATETIME2 NOT NULL,
    [config_digest] NVARCHAR(128)
);

CREATE TABLE [dbo].[sys_sso_provider_config] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [provider] NVARCHAR(20) NOT NULL,
    [enabled] SMALLINT DEFAULT 0 NOT NULL,
    [app_id] NVARCHAR(200) NOT NULL,
    [app_secret_enc] NVARCHAR(1000) NOT NULL,
    [extra_config] NVARCHAR(MAX),
    [redirect_path] NVARCHAR(500)
);

CREATE TABLE [dbo].[sys_sso_user_binding] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [provider] NVARCHAR(20) NOT NULL,
    [external_id] NVARCHAR(200) NOT NULL,
    [external_digest] NVARCHAR(64) NOT NULL,
    [user_id] BIGINT NOT NULL,
    [bind_status] NVARCHAR(20) DEFAULT N'ACTIVE' NOT NULL
);

CREATE TABLE [dbo].[sys_tenant] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [name] NVARCHAR(100) NOT NULL,
    [code] NVARCHAR(50) NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [description] NVARCHAR(MAX),
    [contact_name] NVARCHAR(50),
    [contact_phone] NVARCHAR(20),
    [contact_email] NVARCHAR(100),
    [expire_time] DATETIME2,
    [domain_name] NVARCHAR(100),
    [admin_user_id] BIGINT,
    [creation_request_id] NVARCHAR(64)
);

CREATE TABLE [dbo].[sys_tenant_status_audit] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [action] NVARCHAR(20) NOT NULL,
    [old_status] SMALLINT,
    [new_status] SMALLINT,
    [expire_time] DATETIME2,
    [reason] NVARCHAR(500) NOT NULL,
    [operator_id] BIGINT NOT NULL,
    [occurred_at] DATETIME2 NOT NULL,
    [result] NVARCHAR(200) NOT NULL
);

CREATE TABLE [dbo].[sys_user] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [username] NVARCHAR(50) NOT NULL,
    [password] NVARCHAR(200) NOT NULL,
    [real_name] NVARCHAR(50),
    [email] NVARCHAR(100),
    [phone] NVARCHAR(20),
    [avatar] NVARCHAR(200),
    [sex] SMALLINT DEFAULT 0 NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [dept_id] BIGINT,
    [last_login_time] DATETIME2,
    [last_login_ip] NVARCHAR(50),
    [remark] NVARCHAR(MAX),
    [is_admin] SMALLINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sys_user_group] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2,
    [create_by] BIGINT,
    [update_time] DATETIME2,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [group_code] NVARCHAR(64) NOT NULL,
    [group_name] NVARCHAR(64) NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL,
    [remark] NVARCHAR(255)
);

CREATE TABLE [dbo].[sys_user_group_member] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2,
    [create_by] BIGINT,
    [update_time] DATETIME2,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [group_id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL
);

CREATE TABLE [dbo].[sys_user_post] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2,
    [create_by] BIGINT,
    [update_time] DATETIME2,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [user_id] BIGINT NOT NULL,
    [post_id] BIGINT NOT NULL,
    [dept_id] BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE [dbo].[sys_user_role] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [user_id] BIGINT NOT NULL,
    [role_id] BIGINT NOT NULL
);

CREATE TABLE [dbo].[sys_user_workspace] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [user_id] BIGINT NOT NULL,
    [layout_json] NVARCHAR(MAX)
);

CREATE TABLE [dbo].[sys_workspace_card_type] (
    [id] BIGINT NOT NULL,
    [create_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [create_by] BIGINT,
    [update_time] DATETIME2 DEFAULT SYSDATETIME() NOT NULL,
    [update_by] BIGINT,
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL,
    [type_code] NVARCHAR(64) NOT NULL,
    [display_name] NVARCHAR(100) NOT NULL,
    [renderer_key] NVARCHAR(64) NOT NULL,
    [metadata_json] NVARCHAR(MAX) DEFAULT N'{}' NOT NULL,
    [default_span] SMALLINT DEFAULT 1 NOT NULL,
    [default_order] INT DEFAULT 1 NOT NULL,
    [status] SMALLINT DEFAULT 0 NOT NULL
);

ALTER TABLE [dbo].[sw_bpm_process_favorite] ADD CONSTRAINT [pk_sw_bpm_process_favorite] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_theme_seq] ADD CONSTRAINT [pk_sw_bpm_theme_seq] PRIMARY KEY ([process_key]);
ALTER TABLE [dbo].[sw_agent_graph_debug_node] ADD CONSTRAINT [sw_agent_graph_debug_node_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_graph_debug_session] ADD CONSTRAINT [sw_agent_graph_debug_session_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_graph_def] ADD CONSTRAINT [sw_agent_graph_def_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_graph_execution_event] ADD CONSTRAINT [sw_agent_graph_execution_event_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_graph_execution_node] ADD CONSTRAINT [sw_agent_graph_execution_node_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_graph_execution] ADD CONSTRAINT [sw_agent_graph_execution_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD CONSTRAINT [sw_agent_graph_execution_request_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_graph_snapshot] ADD CONSTRAINT [sw_agent_graph_snapshot_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_message] ADD CONSTRAINT [sw_agent_message_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_model_config] ADD CONSTRAINT [sw_agent_model_config_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_model] ADD CONSTRAINT [sw_agent_model_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_provider] ADD CONSTRAINT [sw_agent_provider_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_session] ADD CONSTRAINT [sw_agent_session_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_tool_call_log] ADD CONSTRAINT [sw_agent_tool_call_log_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_tool_external] ADD CONSTRAINT [sw_agent_tool_external_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_agent_tool_internal] ADD CONSTRAINT [sw_agent_tool_internal_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_action_ref] ADD CONSTRAINT [sw_bpm_action_ref_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_approval_action] ADD CONSTRAINT [sw_bpm_approval_action_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_authorize_rule] ADD CONSTRAINT [sw_bpm_authorize_rule_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_branch_trace] ADD CONSTRAINT [sw_bpm_branch_trace_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_category] ADD CONSTRAINT [sw_bpm_category_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_child_batch] ADD CONSTRAINT [sw_bpm_child_batch_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_child_item] ADD CONSTRAINT [sw_bpm_child_item_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_command_batch_item] ADD CONSTRAINT [sw_bpm_command_batch_item_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_command_batch] ADD CONSTRAINT [sw_bpm_command_batch_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_command_effect] ADD CONSTRAINT [sw_bpm_command_effect_pkey] PRIMARY KEY ([command_id]);
ALTER TABLE [dbo].[sw_bpm_command] ADD CONSTRAINT [sw_bpm_command_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_communication] ADD CONSTRAINT [sw_bpm_communication_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_consensus_vote] ADD CONSTRAINT [sw_bpm_consensus_vote_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_copy_record] ADD CONSTRAINT [sw_bpm_copy_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_draft] ADD CONSTRAINT [sw_bpm_draft_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_dynamic_branch] ADD CONSTRAINT [sw_bpm_dynamic_branch_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_ext_datasource_grant] ADD CONSTRAINT [sw_bpm_ext_datasource_grant_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_ext_datasource] ADD CONSTRAINT [sw_bpm_ext_datasource_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_ext_sql_execution_audit] ADD CONSTRAINT [sw_bpm_ext_sql_execution_audit_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_form_binding] ADD CONSTRAINT [sw_bpm_form_binding_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_handover_item] ADD CONSTRAINT [sw_bpm_handover_item_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_handover] ADD CONSTRAINT [sw_bpm_handover_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_instance_intervention] ADD CONSTRAINT [sw_bpm_instance_intervention_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_instance] ADD CONSTRAINT [sw_bpm_instance_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_node_function_audit] ADD CONSTRAINT [sw_bpm_node_function_audit_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_node_function] ADD CONSTRAINT [sw_bpm_node_function_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_participant_snapshot] ADD CONSTRAINT [sw_bpm_participant_snapshot_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_process_def] ADD CONSTRAINT [sw_bpm_process_def_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_process_def_version] ADD CONSTRAINT [sw_bpm_process_def_version_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_process_template] ADD CONSTRAINT [sw_bpm_process_template_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_resource_policy] ADD CONSTRAINT [sw_bpm_resource_policy_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_resource_reject_log] ADD CONSTRAINT [sw_bpm_resource_reject_log_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_resource_usage] ADD CONSTRAINT [sw_bpm_resource_usage_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_sign_record] ADD CONSTRAINT [sw_bpm_sign_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_task_deadline] ADD CONSTRAINT [sw_bpm_task_deadline_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_task_form_data] ADD CONSTRAINT [sw_bpm_task_form_data_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_task_opinion_draft] ADD CONSTRAINT [sw_bpm_task_opinion_draft_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_trigger_exec] ADD CONSTRAINT [sw_bpm_trigger_exec_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_urge_record] ADD CONSTRAINT [sw_bpm_urge_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_app_dir] ADD CONSTRAINT [sw_form_app_dir_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_app_import_record] ADD CONSTRAINT [sw_form_app_import_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_app] ADD CONSTRAINT [sw_form_app_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_app_scope] ADD CONSTRAINT [sw_form_app_scope_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_app_user] ADD CONSTRAINT [sw_form_app_user_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_c1_policy] ADD CONSTRAINT [sw_form_c1_policy_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_config] ADD CONSTRAINT [sw_form_config_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_def] ADD CONSTRAINT [sw_form_def_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_ext_query] ADD CONSTRAINT [sw_form_ext_query_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_integration_invocation] ADD CONSTRAINT [sw_form_integration_invocation_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_lifecycle_audit] ADD CONSTRAINT [sw_form_lifecycle_audit_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_list_config] ADD CONSTRAINT [sw_form_list_config_form_id_key] UNIQUE ([form_id]);
ALTER TABLE [dbo].[sw_form_list_config] ADD CONSTRAINT [sw_form_list_config_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_snapshot] ADD CONSTRAINT [sw_form_snapshot_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_trace] ADD CONSTRAINT [sw_form_trace_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_txn_action] ADD CONSTRAINT [sw_form_txn_action_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_txn_action_version] ADD CONSTRAINT [sw_form_txn_action_version_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_txn_invocation] ADD CONSTRAINT [sw_form_txn_invocation_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_txn_ledger] ADD CONSTRAINT [sw_form_txn_ledger_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_form_txn_reservation] ADD CONSTRAINT [sw_form_txn_reservation_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_audit_record] ADD CONSTRAINT [sw_iot_audit_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_command] ADD CONSTRAINT [sw_iot_command_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_command_reservation] ADD CONSTRAINT [sw_iot_command_reservation_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_connection] ADD CONSTRAINT [sw_iot_connection_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_device_command] ADD CONSTRAINT [sw_iot_device_command_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_device] ADD CONSTRAINT [sw_iot_device_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_event_record] ADD CONSTRAINT [sw_iot_event_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_event_rule] ADD CONSTRAINT [sw_iot_event_rule_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_message_log] ADD CONSTRAINT [sw_iot_message_log_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_process_trigger] ADD CONSTRAINT [sw_iot_process_trigger_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_product] ADD CONSTRAINT [sw_iot_product_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_property_record] ADD CONSTRAINT [sw_iot_property_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_script_exec] ADD CONSTRAINT [sw_iot_script_exec_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_script] ADD CONSTRAINT [sw_iot_script_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_script_version] ADD CONSTRAINT [sw_iot_script_version_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_thing_model] ADD CONSTRAINT [sw_iot_thing_model_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_iot_topic] ADD CONSTRAINT [sw_iot_topic_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_job_info] ADD CONSTRAINT [sw_job_info_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_job_log] ADD CONSTRAINT [sw_job_log_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_knowledge_base] ADD CONSTRAINT [sw_knowledge_base_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_knowledge_chunk] ADD CONSTRAINT [sw_knowledge_chunk_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_knowledge_document] ADD CONSTRAINT [sw_knowledge_document_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_knowledge_vector] ADD CONSTRAINT [sw_knowledge_vector_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_channel_config] ADD CONSTRAINT [sw_notify_channel_config_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_message] ADD CONSTRAINT [sw_notify_message_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_rule] ADD CONSTRAINT [sw_notify_rule_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_send_attempt] ADD CONSTRAINT [sw_notify_send_attempt_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_subject_binding] ADD CONSTRAINT [sw_notify_subject_binding_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_subscription] ADD CONSTRAINT [sw_notify_subscription_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_template] ADD CONSTRAINT [sw_notify_template_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_notify_template_version] ADD CONSTRAINT [sw_notify_template_version_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_openapi_app] ADD CONSTRAINT [sw_openapi_app_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_openapi_callback_log] ADD CONSTRAINT [sw_openapi_callback_log_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_openapi_callback_task] ADD CONSTRAINT [sw_openapi_callback_task_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_openapi_idempotency] ADD CONSTRAINT [sw_openapi_idempotency_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_openapi_nonce] ADD CONSTRAINT [sw_openapi_nonce_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_storage_file] ADD CONSTRAINT [sw_storage_file_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_dept] ADD CONSTRAINT [sys_dept_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_dict_data] ADD CONSTRAINT [sys_dict_data_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_dict_type] ADD CONSTRAINT [sys_dict_type_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_menu] ADD CONSTRAINT [sys_menu_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_post_delegate] ADD CONSTRAINT [sys_post_delegate_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_post] ADD CONSTRAINT [sys_post_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_refresh_token] ADD CONSTRAINT [sys_refresh_token_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_role_dept] ADD CONSTRAINT [sys_role_dept_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_role_menu] ADD CONSTRAINT [sys_role_menu_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_role] ADD CONSTRAINT [sys_role_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_sso_audit_record] ADD CONSTRAINT [sys_sso_audit_record_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_sso_auth_state] ADD CONSTRAINT [sys_sso_auth_state_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_sso_provider_config] ADD CONSTRAINT [sys_sso_provider_config_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_sso_user_binding] ADD CONSTRAINT [sys_sso_user_binding_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_tenant] ADD CONSTRAINT [sys_tenant_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_tenant_status_audit] ADD CONSTRAINT [sys_tenant_status_audit_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_user] ADD CONSTRAINT [sys_user_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_user_role] ADD CONSTRAINT [sys_user_role_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_user_workspace] ADD CONSTRAINT [sys_user_workspace_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sys_workspace_card_type] ADD CONSTRAINT [sys_workspace_card_type_pkey] PRIMARY KEY ([id]);
ALTER TABLE [dbo].[sw_bpm_process_favorite] ADD CONSTRAINT [uk_sw_bpm_process_favorite] UNIQUE ([tenant_id], [user_id], [process_key]);
ALTER TABLE [dbo].[sw_form_ext_query] ADD CONSTRAINT [uk_sw_form_ext_query] UNIQUE ([tenant_id], [query_key], [query_version]);

CREATE INDEX [idx_bpm_ext_ds_grant_subject] ON [dbo].[sw_bpm_ext_datasource_grant] ([tenant_id], [subject_type], [subject_key]);
CREATE INDEX [idx_bpm_opinion_draft_instance] ON [dbo].[sw_bpm_task_opinion_draft] ([tenant_id], [process_instance_id], [assignee_id]);
CREATE INDEX [idx_form_integration_invocation_form] ON [dbo].[sw_form_integration_invocation] ([tenant_id], [form_key], [form_version]);
CREATE INDEX [idx_genode_debug] ON [dbo].[sw_agent_graph_debug_node] ([debug_session_id], [node_seq], [deleted]);
CREATE INDEX [idx_gexec_debug_expires] ON [dbo].[sw_agent_graph_debug_session] ([expires_at]);
CREATE INDEX [idx_gexec_debug_graph] ON [dbo].[sw_agent_graph_debug_session] ([graph_def_id], [deleted]);
CREATE INDEX [idx_iot_command_expiry] ON [dbo].[sw_iot_device_command] ([expiry_time], [status]);
CREATE INDEX [idx_iot_command_status_device] ON [dbo].[sw_iot_device_command] ([product_id], [device_name], [status]);
CREATE INDEX [idx_srt_user_tenant] ON [dbo].[sys_refresh_token] ([user_id], [tenant_id]);
CREATE INDEX [idx_sw_agent_exec_event_replay] ON [dbo].[sw_agent_graph_execution_event] ([tenant_id], [execution_id], [sequence_no], [deleted]);
CREATE INDEX [idx_sw_agent_exec_req_status] ON [dbo].[sw_agent_graph_execution_request] ([tenant_id], [status], [create_time]);
CREATE INDEX [idx_sw_agent_genode_exec] ON [dbo].[sw_agent_graph_execution_node] ([execution_id], [node_seq], [deleted]);
CREATE INDEX [idx_sw_agent_gexec_graph] ON [dbo].[sw_agent_graph_execution] ([graph_def_id], [deleted]);
CREATE INDEX [idx_sw_agent_gexec_time] ON [dbo].[sw_agent_graph_execution] ([tenant_id], [create_time], [deleted]);
CREATE INDEX [idx_sw_agent_graph_snapshot_def] ON [dbo].[sw_agent_graph_snapshot] ([graph_def_id], [deleted]);
CREATE INDEX [idx_sw_agent_graph_tenant_deleted] ON [dbo].[sw_agent_graph_def] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_agent_model_group] ON [dbo].[sw_agent_model_config] ([tenant_id], [group_key], [sort]);
CREATE INDEX [idx_sw_agent_model_grp] ON [dbo].[sw_agent_model] ([tenant_id], [group_key], [sort]);
CREATE INDEX [idx_sw_agent_model_provider] ON [dbo].[sw_agent_model] ([tenant_id], [provider_id]);
CREATE INDEX [idx_sw_agent_model_td] ON [dbo].[sw_agent_model] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_agent_model_tenant_deleted] ON [dbo].[sw_agent_model_config] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_agent_msg_session] ON [dbo].[sw_agent_message] ([session_id], [msg_order], [deleted]);
CREATE INDEX [idx_sw_agent_provider_tenant_deleted] ON [dbo].[sw_agent_provider] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_agent_session_cfg] ON [dbo].[sw_agent_session] ([agent_model_config_id], [deleted]);
CREATE INDEX [idx_sw_agent_session_user] ON [dbo].[sw_agent_session] ([tenant_id], [create_by], [deleted]);
CREATE INDEX [idx_sw_agent_tcl_session] ON [dbo].[sw_agent_tool_call_log] ([session_id], [deleted]);
CREATE INDEX [idx_sw_agent_tool_external_tenant_deleted] ON [dbo].[sw_agent_tool_external] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_agent_tool_internal_tenant_deleted] ON [dbo].[sw_agent_tool_internal] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_bpm_action_ref_exec] ON [dbo].[sw_bpm_action_ref] ([tenant_id], [exec_id]);
CREATE INDEX [idx_sw_bpm_action_ref_instance] ON [dbo].[sw_bpm_action_ref] ([tenant_id], [process_instance_id]);
CREATE INDEX [idx_sw_bpm_authorize_agent] ON [dbo].[sw_bpm_authorize_rule] ([tenant_id], [agent_id], [status]);
CREATE INDEX [idx_sw_bpm_branch_trace_instance] ON [dbo].[sw_bpm_branch_trace] ([tenant_id], [process_instance_id]);
CREATE INDEX [idx_sw_bpm_child_batch_parent] ON [dbo].[sw_bpm_child_batch] ([tenant_id], [parent_instance_id]);
CREATE INDEX [idx_sw_bpm_child_batch_root] ON [dbo].[sw_bpm_child_batch] ([tenant_id], [root_instance_id]);
CREATE INDEX [idx_sw_bpm_child_item_batch] ON [dbo].[sw_bpm_child_item] ([tenant_id], [batch_id]);
CREATE INDEX [idx_sw_bpm_child_item_target] ON [dbo].[sw_bpm_child_item] ([tenant_id], [target_record_id]);
CREATE INDEX [idx_sw_bpm_comm_task] ON [dbo].[sw_bpm_communication] ([tenant_id], [task_id]);
CREATE INDEX [idx_sw_bpm_command_batch_cmd] ON [dbo].[sw_bpm_command_batch] ([command_id]);
CREATE INDEX [idx_sw_bpm_command_batch_item_status] ON [dbo].[sw_bpm_command_batch_item] ([batch_id], [status]);
CREATE INDEX [idx_sw_bpm_command_deadline] ON [dbo].[sw_bpm_command] ([status], [deadline_at]);
CREATE INDEX [idx_sw_bpm_command_dispatch] ON [dbo].[sw_bpm_command] ([status], [channel], [next_retry_at]);
CREATE INDEX [idx_sw_bpm_command_effect_logical] ON [dbo].[sw_bpm_command_effect] ([logical_command_id]);
CREATE INDEX [idx_sw_bpm_command_resource_open] ON [dbo].[sw_bpm_command] ([resource_released_at], [status]);
CREATE INDEX [idx_sw_bpm_copy_instance] ON [dbo].[sw_bpm_copy_record] ([tenant_id], [process_instance_id]);
CREATE INDEX [idx_sw_bpm_deadline_due] ON [dbo].[sw_bpm_task_deadline] ([run_state], [due_at]);
CREATE INDEX [idx_sw_bpm_def_version_status] ON [dbo].[sw_bpm_process_def_version] ([tenant_id], [def_id], [status]);
CREATE INDEX [idx_sw_bpm_draft_owner] ON [dbo].[sw_bpm_draft] ([tenant_id], [create_by], [form_key]);
CREATE INDEX [idx_sw_bpm_dyn_branch_instance] ON [dbo].[sw_bpm_dynamic_branch] ([process_instance_id], [node_key]);
CREATE INDEX [idx_sw_bpm_ext_audit_create_time] ON [dbo].[sw_bpm_ext_sql_execution_audit] ([create_time]);
CREATE INDEX [idx_sw_bpm_ext_audit_ds_id] ON [dbo].[sw_bpm_ext_sql_execution_audit] ([datasource_id]);
CREATE INDEX [idx_sw_bpm_ext_audit_operator_id] ON [dbo].[sw_bpm_ext_sql_execution_audit] ([operator_id]);
CREATE INDEX [idx_sw_bpm_fn_audit_instance] ON [dbo].[sw_bpm_node_function_audit] ([process_instance_id]);
CREATE INDEX [idx_sw_bpm_handover_item] ON [dbo].[sw_bpm_handover_item] ([handover_id]);
CREATE INDEX [idx_sw_bpm_handover_users] ON [dbo].[sw_bpm_handover] ([from_user_id], [to_user_id]);
CREATE INDEX [idx_sw_bpm_inst_business_key] ON [dbo].[sw_bpm_instance] ([business_key]);
CREATE INDEX [idx_sw_bpm_inst_process_inst] ON [dbo].[sw_bpm_instance] ([process_instance_id]);
CREATE INDEX [idx_sw_bpm_inst_tenant_status] ON [dbo].[sw_bpm_instance] ([tenant_id], [status]);
CREATE INDEX [idx_sw_bpm_itv_instance] ON [dbo].[sw_bpm_instance_intervention] ([process_instance_id]);
CREATE INDEX [idx_sw_bpm_participant_task] ON [dbo].[sw_bpm_participant_snapshot] ([tenant_id], [task_id]);
CREATE INDEX [idx_sw_bpm_proc_def_form] ON [dbo].[sw_bpm_process_def] ([form_key]);
CREATE INDEX [idx_sw_bpm_proc_def_key] ON [dbo].[sw_bpm_process_def] ([process_key]);
CREATE INDEX [idx_sw_bpm_process_favorite_user] ON [dbo].[sw_bpm_process_favorite] ([tenant_id], [user_id], [update_time] DESC);
CREATE INDEX [idx_sw_bpm_resource_policy_status] ON [dbo].[sw_bpm_resource_policy] ([status]);
CREATE INDEX [idx_sw_bpm_resource_reject_tenant_time] ON [dbo].[sw_bpm_resource_reject_log] ([tenant_id], [create_time]);
CREATE INDEX [idx_sw_bpm_sign_task] ON [dbo].[sw_bpm_sign_record] ([tenant_id], [task_id]);
CREATE INDEX [idx_sw_bpm_task_form_data_instance] ON [dbo].[sw_bpm_task_form_data] ([tenant_id], [process_instance_id], [node_key], [round_no]);
CREATE INDEX [idx_sw_bpm_tpl_category] ON [dbo].[sw_bpm_process_template] ([category]);
CREATE INDEX [idx_sw_bpm_tpl_scope] ON [dbo].[sw_bpm_process_template] ([scope_type], [scope_dept_id]);
CREATE INDEX [idx_sw_bpm_trigger_exec_instance] ON [dbo].[sw_bpm_trigger_exec] ([tenant_id], [process_instance_id]);
CREATE INDEX [idx_sw_bpm_urge_instance] ON [dbo].[sw_bpm_urge_record] ([process_instance_id]);
CREATE INDEX [idx_sw_bpm_vote_node] ON [dbo].[sw_bpm_consensus_vote] ([tenant_id], [process_instance_id], [node_key]);
CREATE INDEX [idx_sw_form_app_dir_app] ON [dbo].[sw_form_app_dir] ([tenant_id], [app_id], [deleted], [sort_no]);
CREATE INDEX [idx_sw_form_app_import_record_tenant] ON [dbo].[sw_form_app_import_record] ([tenant_id]);
CREATE INDEX [idx_sw_form_app_scope_app] ON [dbo].[sw_form_app_scope] ([tenant_id], [app_id], [deleted]);
CREATE INDEX [idx_sw_form_app_tenant] ON [dbo].[sw_form_app] ([tenant_id], [deleted], [sort_no]);
CREATE INDEX [idx_sw_form_cfg_parent] ON [dbo].[sw_form_config] ([parent_table]);
CREATE INDEX [idx_sw_form_def_app] ON [dbo].[sw_form_def] ([tenant_id], [app_id], [deleted]);
CREATE INDEX [idx_sw_form_txn_inv_time] ON [dbo].[sw_form_txn_invocation] ([tenant_id], [action_id], [create_time]);
CREATE INDEX [idx_sw_form_txn_ledger_action] ON [dbo].[sw_form_txn_ledger] ([tenant_id], [action_id], [create_time]);
CREATE INDEX [idx_sw_form_txn_ledger_res] ON [dbo].[sw_form_txn_ledger] ([tenant_id], [reservation_id]);
CREATE INDEX [idx_sw_form_txn_res_expire] ON [dbo].[sw_form_txn_reservation] ([tenant_id], [status], [expires_at]);
CREATE INDEX [idx_sw_form_txn_res_record] ON [dbo].[sw_form_txn_reservation] ([tenant_id], [form_id], [record_id]);
CREATE INDEX [idx_sw_iot_audit_correlation] ON [dbo].[sw_iot_audit_record] ([tenant_id], [correlation_id]);
CREATE INDEX [idx_sw_iot_audit_lookup] ON [dbo].[sw_iot_audit_record] ([tenant_id], [action], [object_type], [object_id], [action_time]);
CREATE INDEX [idx_sw_iot_cmd_biz] ON [dbo].[sw_iot_device_command] ([tenant_id], [approval_biz_id]);
CREATE INDEX [idx_sw_iot_cmd_dev] ON [dbo].[sw_iot_command] ([tenant_id], [device_id], [create_time]);
CREATE INDEX [idx_sw_iot_cmd_device] ON [dbo].[sw_iot_device_command] ([tenant_id], [device_key]);
CREATE INDEX [idx_sw_iot_cmd_flow] ON [dbo].[sw_iot_command] ([tenant_id], [flow_instance_id]);
CREATE INDEX [idx_sw_iot_device_key] ON [dbo].[sw_iot_device] ([tenant_id], [device_key]);
CREATE INDEX [idx_sw_iot_event_correlation] ON [dbo].[sw_iot_event_record] ([tenant_id], [correlation_id]);
CREATE INDEX [idx_sw_iot_event_dev] ON [dbo].[sw_iot_event_record] ([tenant_id], [device_id], [occur_time]);
CREATE INDEX [idx_sw_iot_exec_correlation] ON [dbo].[sw_iot_script_exec] ([tenant_id], [correlation_id]);
CREATE INDEX [idx_sw_iot_exec_script] ON [dbo].[sw_iot_script_exec] ([tenant_id], [script_id], [create_time]);
CREATE INDEX [idx_sw_iot_msg_dedup] ON [dbo].[sw_iot_message_log] ([tenant_id], [dedup_key]);
CREATE INDEX [idx_sw_iot_msg_topic] ON [dbo].[sw_iot_message_log] ([tenant_id], [topic], [create_time]);
CREATE INDEX [idx_sw_iot_prop_dev] ON [dbo].[sw_iot_property_record] ([tenant_id], [device_id], [property_id], [report_time]);
CREATE INDEX [idx_sw_iot_reservation_status] ON [dbo].[sw_iot_command_reservation] ([status], [due_at_utc]);
CREATE INDEX [idx_sw_iot_rule_device] ON [dbo].[sw_iot_event_rule] ([tenant_id], [device_id]);
CREATE INDEX [idx_sw_iot_trigger_agent_exec] ON [dbo].[sw_iot_process_trigger] ([tenant_id], [agent_execution_id]);
CREATE INDEX [idx_sw_iot_trigger_recovery] ON [dbo].[sw_iot_process_trigger] ([status], [next_retry_time]);
CREATE INDEX [idx_sw_job_info_tenant_deleted] ON [dbo].[sw_job_info] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_job_log_job_id] ON [dbo].[sw_job_log] ([job_id]);
CREATE INDEX [idx_sw_job_log_tenant_deleted] ON [dbo].[sw_job_log] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_knowledge_base_tenant] ON [dbo].[sw_knowledge_base] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_knowledge_chunk_kb] ON [dbo].[sw_knowledge_chunk] ([tenant_id], [kb_id], [document_id], [deleted]);
CREATE INDEX [idx_sw_knowledge_doc_kb] ON [dbo].[sw_knowledge_document] ([tenant_id], [kb_id], [deleted]);
CREATE INDEX [idx_sw_knowledge_vector_kb] ON [dbo].[sw_knowledge_vector] ([tenant_id], [kb_id], [document_id]);
CREATE INDEX [idx_sw_notify_attempt_message] ON [dbo].[sw_notify_send_attempt] ([message_id]);
CREATE INDEX [idx_sw_notify_msg_delivery] ON [dbo].[sw_notify_message] ([tenant_id], [delivery_status]);
CREATE INDEX [idx_sw_notify_msg_recipient] ON [dbo].[sw_notify_message] ([tenant_id], [recipient_id]);
CREATE INDEX [idx_sw_notify_msg_retry] ON [dbo].[sw_notify_message] ([tenant_id], [event_type], [biz_id]);
CREATE INDEX [idx_sw_notify_rule_event] ON [dbo].[sw_notify_rule] ([tenant_id], [event_type]);
CREATE INDEX [idx_sw_notify_subject_provider] ON [dbo].[sw_notify_subject_binding] ([tenant_id], [provider], [deleted]);
CREATE INDEX [idx_sw_notify_template_tenant_deleted] ON [dbo].[sw_notify_template] ([tenant_id], [deleted]);
CREATE INDEX [idx_sw_notify_tpl_ver_template] ON [dbo].[sw_notify_template_version] ([template_id]);
CREATE INDEX [idx_sw_openapi_cb_app] ON [dbo].[sw_openapi_callback_log] ([app_id], [biz_ref], [event]);
CREATE INDEX [idx_sw_openapi_cb_task_due] ON [dbo].[sw_openapi_callback_task] ([status], [next_retry_time]);
CREATE INDEX [idx_sw_storage_file_tenant_deleted] ON [dbo].[sw_storage_file] ([tenant_id], [deleted]);
CREATE INDEX [idx_sys_dept_parent_id] ON [dbo].[sys_dept] ([parent_id]);
CREATE INDEX [idx_sys_dict_data_dict_code] ON [dbo].[sys_dict_data] ([dict_code]);
CREATE INDEX [idx_sys_menu_parent_id] ON [dbo].[sys_menu] ([parent_id]);
CREATE INDEX [idx_sys_post_delegate_dept] ON [dbo].[sys_post_delegate] ([tenant_id], [dept_id]);
CREATE INDEX [idx_sys_post_delegate_source] ON [dbo].[sys_post_delegate] ([tenant_id], [source_post_id], [status]);
CREATE INDEX [idx_sys_sso_audit_query] ON [dbo].[sys_sso_audit_record] ([tenant_id], [provider], [event_type], [create_time]);
CREATE INDEX [idx_sys_sso_binding_user_all] ON [dbo].[sys_sso_user_binding] ([user_id]);
CREATE INDEX [idx_sys_sso_state_expire] ON [dbo].[sys_sso_auth_state] ([expire_at]);
CREATE INDEX [idx_sys_tenant_status_audit_tenant_time] ON [dbo].[sys_tenant_status_audit] ([tenant_id], [occurred_at]);
CREATE INDEX [idx_sys_user_dept_id] ON [dbo].[sys_user] ([dept_id]);
CREATE INDEX [idx_sys_user_group_member_group] ON [dbo].[sys_user_group_member] ([tenant_id], [group_id], [deleted]);
CREATE INDEX [idx_sys_user_group_member_user] ON [dbo].[sys_user_group_member] ([tenant_id], [user_id], [deleted]);
CREATE INDEX [idx_sys_user_group_name] ON [dbo].[sys_user_group] ([tenant_id], [group_name], [deleted]);
CREATE INDEX [idx_sys_user_group_status] ON [dbo].[sys_user_group] ([tenant_id], [status], [deleted]);
CREATE INDEX [idx_sys_user_post_dept] ON [dbo].[sys_user_post] ([tenant_id], [dept_id], [deleted]);
CREATE INDEX [idx_sys_user_post_post] ON [dbo].[sys_user_post] ([tenant_id], [post_id], [deleted]);
CREATE INDEX [idx_sys_user_post_user] ON [dbo].[sys_user_post] ([tenant_id], [user_id], [deleted]);
CREATE UNIQUE INDEX [uk_bpm_ext_ds_grant] ON [dbo].[sw_bpm_ext_datasource_grant] ([tenant_id], [datasource_id], [subject_type], [subject_key]);
CREATE UNIQUE INDEX [uk_bpm_opinion_draft_task_user] ON [dbo].[sw_bpm_task_opinion_draft] ([tenant_id], [task_id], [assignee_id]);
CREATE UNIQUE INDEX [uk_form_integration_invocation_identity] ON [dbo].[sw_form_integration_invocation] ([tenant_id], [form_key], [form_version], [capability_id], [phase], [request_key], [subject_id]);
CREATE UNIQUE INDEX [uk_iot_command_idempotent] ON [dbo].[sw_iot_device_command] ([idempotent_key]) WHERE idempotent_key IS NOT NULL;
CREATE UNIQUE INDEX [uk_iot_device_product_device] ON [dbo].[sw_iot_device] ([product_id], [device_name], [tenant_id]) WHERE [product_id] IS NOT NULL AND [device_name] IS NOT NULL;
CREATE UNIQUE INDEX [uk_srt_token_hash] ON [dbo].[sys_refresh_token] ([token_hash]);
CREATE UNIQUE INDEX [uk_sw_agent_exec_event_seq] ON [dbo].[sw_agent_graph_execution_event] ([tenant_id], [execution_id], [sequence_no]);
CREATE UNIQUE INDEX [uk_sw_agent_exec_req_idem] ON [dbo].[sw_agent_graph_execution_request] ([tenant_id], [create_by], [idempotency_key]) WHERE [create_by] IS NOT NULL AND [idempotency_key] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_agent_graph_key] ON [dbo].[sw_agent_graph_def] ([tenant_id], [graph_key]);
CREATE UNIQUE INDEX [uk_sw_agent_graph_snapshot_ver] ON [dbo].[sw_agent_graph_snapshot] ([tenant_id], [graph_def_id], [def_version]);
CREATE UNIQUE INDEX [uk_sw_agent_model_name] ON [dbo].[sw_agent_model_config] ([tenant_id], [name]);
CREATE UNIQUE INDEX [uk_sw_agent_model_tenant_name] ON [dbo].[sw_agent_model] ([tenant_id], [name]);
CREATE UNIQUE INDEX [uk_sw_agent_provider_name] ON [dbo].[sw_agent_provider] ([tenant_id], [name]);
CREATE UNIQUE INDEX [uk_sw_bpm_action_ref] ON [dbo].[sw_bpm_action_ref] ([tenant_id], [command_key]);
CREATE UNIQUE INDEX [uk_sw_bpm_approval_task_actor_action] ON [dbo].[sw_bpm_approval_action] ([tenant_id], [task_id], [actor_id], [action]);
CREATE UNIQUE INDEX [uk_sw_bpm_binding_active] ON [dbo].[sw_bpm_form_binding] ([tenant_id], [form_key]) WHERE active = 1;
CREATE UNIQUE INDEX [uk_sw_bpm_child_batch] ON [dbo].[sw_bpm_child_batch] ([tenant_id], [batch_key]);
CREATE UNIQUE INDEX [uk_sw_bpm_child_item] ON [dbo].[sw_bpm_child_item] ([tenant_id], [batch_id], [item_key]);
CREATE UNIQUE INDEX [uk_sw_bpm_comm_instance_receiver] ON [dbo].[sw_bpm_communication] ([tenant_id], [process_instance_id], [task_id], [receiver_id]) WHERE [task_id] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_bpm_command_batch_item_key] ON [dbo].[sw_bpm_command_batch_item] ([batch_id], [item_key]);
CREATE UNIQUE INDEX [uk_sw_bpm_command_batch_key] ON [dbo].[sw_bpm_command_batch] ([tenant_id], [batch_key]);
CREATE UNIQUE INDEX [uk_sw_bpm_command_key] ON [dbo].[sw_bpm_command] ([tenant_id], [command_key]);
CREATE UNIQUE INDEX [uk_sw_bpm_command_logical] ON [dbo].[sw_bpm_command] ([tenant_id], [logical_command_id]) WHERE [logical_command_id] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_bpm_deadline_task] ON [dbo].[sw_bpm_task_deadline] ([tenant_id], [task_id]);
CREATE UNIQUE INDEX [uk_sw_bpm_def_version] ON [dbo].[sw_bpm_process_def_version] ([tenant_id], [def_id], [graph_version]);
CREATE UNIQUE INDEX [uk_sw_bpm_dyn_branch] ON [dbo].[sw_bpm_dynamic_branch] ([tenant_id], [process_instance_id], [node_key], [round_no], [branch_index]) WHERE [round_no] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_bpm_ext_ds_name] ON [dbo].[sw_bpm_ext_datasource] ([name], [deleted]);
CREATE UNIQUE INDEX [uk_sw_bpm_node_func] ON [dbo].[sw_bpm_node_function] ([tenant_id], [func_key], [func_version]);
CREATE UNIQUE INDEX [uk_sw_bpm_resource_policy_version] ON [dbo].[sw_bpm_resource_policy] ([tenant_id], [policy_version]);
CREATE UNIQUE INDEX [uk_sw_bpm_resource_usage_scope] ON [dbo].[sw_bpm_resource_usage] ([scope], [scope_key], [segment]);
CREATE UNIQUE INDEX [uk_sw_bpm_task_form_data] ON [dbo].[sw_bpm_task_form_data] ([tenant_id], [task_id]);
CREATE UNIQUE INDEX [uk_sw_bpm_trigger_exec] ON [dbo].[sw_bpm_trigger_exec] ([tenant_id], [exec_key]);
CREATE UNIQUE INDEX [uk_sw_bpm_urge_cooldown] ON [dbo].[sw_bpm_urge_record] ([cooldown_key]) WHERE [cooldown_key] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_bpm_vote_task_actor] ON [dbo].[sw_bpm_consensus_vote] ([tenant_id], [task_id], [actor_id]);
CREATE UNIQUE INDEX [uk_sw_form_app_user] ON [dbo].[sw_form_app_user] ([tenant_id], [app_id], [user_id]);
CREATE UNIQUE INDEX [uk_sw_form_c1_policy_form] ON [dbo].[sw_form_c1_policy] ([tenant_id], [form_id]);
CREATE UNIQUE INDEX [uk_sw_form_cfg_tname] ON [dbo].[sw_form_config] ([table_name], [deleted]) WHERE [table_name] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_form_def_form_key] ON [dbo].[sw_form_def] ([tenant_id], [form_key], [deleted]);
CREATE UNIQUE INDEX [uk_sw_form_trace_idem] ON [dbo].[sw_form_trace] ([tenant_id], [submit_idempotency_key]) WHERE [submit_idempotency_key] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_form_txn_action_key] ON [dbo].[sw_form_txn_action] ([tenant_id], [form_id], [action_key]);
CREATE UNIQUE INDEX [uk_sw_form_txn_action_ver] ON [dbo].[sw_form_txn_action_version] ([tenant_id], [action_id], [version_no]);
CREATE UNIQUE INDEX [uk_sw_form_txn_inv_key] ON [dbo].[sw_form_txn_invocation] ([tenant_id], [action_id], [invocation_key]);
CREATE UNIQUE INDEX [uk_sw_iot_conn_code] ON [dbo].[sw_iot_connection] ([tenant_id], [code]);
CREATE UNIQUE INDEX [uk_sw_iot_model_ver] ON [dbo].[sw_iot_thing_model] ([tenant_id], [product_id], [model_version]);
CREATE UNIQUE INDEX [uk_sw_iot_product_code] ON [dbo].[sw_iot_product] ([tenant_id], [code]);
CREATE UNIQUE INDEX [uk_sw_iot_reservation_instance] ON [dbo].[sw_iot_command_reservation] ([tenant_id], [process_instance_id]);
CREATE UNIQUE INDEX [uk_sw_iot_rule_code] ON [dbo].[sw_iot_event_rule] ([tenant_id], [code]);
CREATE UNIQUE INDEX [uk_sw_iot_script_code] ON [dbo].[sw_iot_script] ([tenant_id], [code]);
CREATE UNIQUE INDEX [uk_sw_iot_script_ver] ON [dbo].[sw_iot_script_version] ([tenant_id], [script_id], [script_version]);
CREATE UNIQUE INDEX [uk_sw_iot_topic] ON [dbo].[sw_iot_topic] ([tenant_id], [topic]);
CREATE UNIQUE INDEX [uk_sw_iot_trigger_key] ON [dbo].[sw_iot_process_trigger] ([tenant_id], [idempotent_key]);
CREATE UNIQUE INDEX [uk_sw_knowledge_base_name] ON [dbo].[sw_knowledge_base] ([tenant_id], [name]);
CREATE UNIQUE INDEX [uk_sw_knowledge_vector_chunk] ON [dbo].[sw_knowledge_vector] ([tenant_id], [chunk_id]);
CREATE UNIQUE INDEX [uk_sw_notify_channel] ON [dbo].[sw_notify_channel_config] ([tenant_id], [channel], [deleted]);
CREATE UNIQUE INDEX [uk_sw_notify_msg_idempotency] ON [dbo].[sw_notify_message] ([tenant_id], [idempotency_key]) WHERE [idempotency_key] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_notify_msg_identity] ON [dbo].[sw_notify_message] ([tenant_id], [event_type], [biz_id], [occurrence_no], [recipient_id], [channel]) WHERE [biz_id] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sw_notify_rule_code] ON [dbo].[sw_notify_rule] ([tenant_id], [rule_code], [deleted]);
CREATE UNIQUE INDEX [uk_sw_notify_sub] ON [dbo].[sw_notify_subscription] ([tenant_id], [user_id], [event_type], [channel], [deleted]);
CREATE UNIQUE INDEX [uk_sw_notify_subject_binding] ON [dbo].[sw_notify_subject_binding] ([tenant_id], [user_id], [provider], [deleted]);
CREATE UNIQUE INDEX [uk_sw_notify_template_tenant_code] ON [dbo].[sw_notify_template] ([tenant_id], [template_code], [deleted]);
CREATE UNIQUE INDEX [uk_sw_notify_tpl_ver] ON [dbo].[sw_notify_template_version] ([tenant_id], [template_id], [template_version]);
CREATE UNIQUE INDEX [uk_sw_openapi_app] ON [dbo].[sw_openapi_app] ([app_id]);
CREATE UNIQUE INDEX [uk_sw_openapi_cb_task] ON [dbo].[sw_openapi_callback_task] ([tenant_id], [app_id], [event], [biz_ref]);
CREATE UNIQUE INDEX [uk_sw_openapi_idem] ON [dbo].[sw_openapi_idempotency] ([app_id], [idem_key]);
CREATE UNIQUE INDEX [uk_sw_openapi_nonce] ON [dbo].[sw_openapi_nonce] ([app_id], [nonce]);
CREATE UNIQUE INDEX [uk_sys_dict_type_code] ON [dbo].[sys_dict_type] ([code], [deleted]);
CREATE UNIQUE INDEX [uk_sys_role_dept] ON [dbo].[sys_role_dept] ([role_id], [dept_id]);
CREATE UNIQUE INDEX [uk_sys_role_menu_tenant] ON [dbo].[sys_role_menu] ([tenant_id], [role_id], [menu_id], [deleted]);
CREATE UNIQUE INDEX [uk_sys_role_tenant_code] ON [dbo].[sys_role] ([tenant_id], [code], [deleted]);
CREATE UNIQUE INDEX [uk_sys_sso_binding_digest_global] ON [dbo].[sys_sso_user_binding] ([provider], [external_digest], [deleted]);
CREATE UNIQUE INDEX [uk_sys_sso_binding_external] ON [dbo].[sys_sso_user_binding] ([provider], [tenant_id], [external_id], [deleted]);
CREATE UNIQUE INDEX [uk_sys_sso_binding_user] ON [dbo].[sys_sso_user_binding] ([provider], [tenant_id], [user_id], [deleted]);
CREATE UNIQUE INDEX [uk_sys_sso_provider_app] ON [dbo].[sys_sso_provider_config] ([provider], [app_id], [deleted]);
CREATE UNIQUE INDEX [uk_sys_sso_provider_config] ON [dbo].[sys_sso_provider_config] ([tenant_id], [provider], [deleted]);
CREATE UNIQUE INDEX [uk_sys_sso_state_value] ON [dbo].[sys_sso_auth_state] ([state_value]);
CREATE UNIQUE INDEX [uk_sys_tenant_code] ON [dbo].[sys_tenant] ([code], [deleted]);
CREATE UNIQUE INDEX [uk_sys_tenant_creation_request] ON [dbo].[sys_tenant] ([creation_request_id]) WHERE [creation_request_id] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sys_tenant_status_audit_expiry] ON [dbo].[sys_tenant_status_audit] ([tenant_id], [action], [expire_time]) WHERE [expire_time] IS NOT NULL;
CREATE UNIQUE INDEX [uk_sys_user_group_code] ON [dbo].[sys_user_group] ([tenant_id], [group_code], [deleted]);
CREATE UNIQUE INDEX [uk_sys_user_group_member] ON [dbo].[sys_user_group_member] ([tenant_id], [group_id], [user_id], [deleted]);
CREATE UNIQUE INDEX [uk_sys_user_post] ON [dbo].[sys_user_post] ([tenant_id], [user_id], [post_id], [deleted]);
CREATE UNIQUE INDEX [uk_sys_user_role_tenant] ON [dbo].[sys_user_role] ([tenant_id], [user_id], [role_id], [deleted]);
CREATE UNIQUE INDEX [uk_sys_user_tenant_username] ON [dbo].[sys_user] ([tenant_id], [username], [deleted]);
CREATE UNIQUE INDEX [uk_sys_user_workspace] ON [dbo].[sys_user_workspace] ([tenant_id], [user_id]);
CREATE UNIQUE INDEX [uk_sys_workspace_card_type] ON [dbo].[sys_workspace_card_type] ([tenant_id], [type_code], [deleted]);

