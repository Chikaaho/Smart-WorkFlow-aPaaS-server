-- ===================================================================
-- Smart-WorkFlow :: V0.2.4 F1 阶段 2 补充：执行节点真实输入/输出与时间
-- F1S2-G1：执行详情节点卡片此前只有变量快照，前端读取的 input/output/
-- startTime/endTime 后端从未返回（空栏 + Invalid Date）。本迁移补齐节点级
-- 真实输入、输出、开始/结束时间与执行状态，供执行详情节点卡片展示。
-- ===================================================================

ALTER TABLE sw_agent_graph_execution_node add input_text  CLOB;
ALTER TABLE sw_agent_graph_execution_node add output_text CLOB;
ALTER TABLE sw_agent_graph_execution_node add start_time  TIMESTAMP;
ALTER TABLE sw_agent_graph_execution_node add end_time    TIMESTAMP;
ALTER TABLE sw_agent_graph_execution_node add status      VARCHAR2(20) DEFAULT 'SUCCESS' NOT NULL;
ALTER TABLE sw_agent_graph_execution_node add error_message CLOB;

COMMENT ON COLUMN sw_agent_graph_execution_node.input_text IS '节点真实输入（LLM=实际发送的用户消息；TOOL=工具入参文本）';
COMMENT ON COLUMN sw_agent_graph_execution_node.output_text IS '节点真实输出（LLM=模型最终回答；TOOL=工具返回文本）';
COMMENT ON COLUMN sw_agent_graph_execution_node.start_time IS '节点开始时间（出队时点）';
COMMENT ON COLUMN sw_agent_graph_execution_node.end_time IS '节点结束时间（本步执行/路由完成时点）';
COMMENT ON COLUMN sw_agent_graph_execution_node.status IS '节点执行状态（SUCCESS/FAILED）';
COMMENT ON COLUMN sw_agent_graph_execution_node.error_message IS '节点失败原因（成功时为 NULL）';
