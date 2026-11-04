-- ===================================================================
-- Smart-WorkFlow :: V0.2.4 F1 阶段 2 补充：执行节点真实输入/输出与时间 (H2)
-- F1S2-G1：执行详情节点卡片此前只有变量快照，前端读取的 input/output/
-- startTime/endTime 后端从未返回（空栏 + Invalid Date）。本迁移补齐节点级
-- 真实输入、输出、开始/结束时间与执行状态，供执行详情节点卡片展示。
-- ===================================================================

ALTER TABLE sw_agent_graph_execution_node ADD COLUMN input_text  CLOB;
ALTER TABLE sw_agent_graph_execution_node ADD COLUMN output_text CLOB;
ALTER TABLE sw_agent_graph_execution_node ADD COLUMN start_time  TIMESTAMP;
ALTER TABLE sw_agent_graph_execution_node ADD COLUMN end_time    TIMESTAMP;
ALTER TABLE sw_agent_graph_execution_node ADD COLUMN status      VARCHAR(20) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE sw_agent_graph_execution_node ADD COLUMN error_message CLOB;
