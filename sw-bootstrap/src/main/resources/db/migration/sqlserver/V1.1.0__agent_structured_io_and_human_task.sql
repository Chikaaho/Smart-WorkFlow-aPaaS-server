-- 1.1.0：图执行结构化入参/出参（G1-4）、父子执行关联（G3 子图节点）、人工节点任务表（G3）。
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD [inputs_json] NVARCHAR(MAX);
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD [outputs_json] NVARCHAR(MAX);
ALTER TABLE [dbo].[sw_agent_graph_execution_request] ADD [parent_execution_id] BIGINT;
CREATE INDEX [idx_sw_agent_exec_req_parent] ON [dbo].[sw_agent_graph_execution_request] ([parent_execution_id]);

CREATE TABLE [dbo].[sw_agent_graph_human_task] (
    [id] BIGINT NOT NULL,
    [execution_id] BIGINT NOT NULL,
    [node_id] NVARCHAR(100) NOT NULL,
    [status] NVARCHAR(32) NOT NULL,
    [assignee_user_id] BIGINT,
    [assignee_role_id] BIGINT,
    [payload_json] NVARCHAR(MAX),
    [result_json] NVARCHAR(MAX),
    [submitted_by] BIGINT,
    [submitted_at] DATETIME2,
    [finished_at] DATETIME2,
    [create_time] DATETIME2,
    [create_by] NVARCHAR(64),
    [update_time] DATETIME2,
    [update_by] NVARCHAR(64),
    [deleted] SMALLINT DEFAULT 0 NOT NULL,
    [tenant_id] BIGINT DEFAULT 0 NOT NULL,
    [version] BIGINT DEFAULT 0 NOT NULL
);
ALTER TABLE [dbo].[sw_agent_graph_human_task] ADD CONSTRAINT [sw_agent_graph_human_task_pkey] PRIMARY KEY ([id]);
CREATE INDEX [idx_sw_agent_human_exec] ON [dbo].[sw_agent_graph_human_task] ([tenant_id], [execution_id], [status]);
CREATE INDEX [idx_sw_agent_human_assignee] ON [dbo].[sw_agent_graph_human_task] ([tenant_id], [assignee_user_id], [status]);
