-- B021-007 批次5（设计01/工作台配置）：统计概览拆分为四张独立指标卡——
-- 画布、预览与首页均按独立统计小卡呈现（可单独拖放/调宽高）。
-- 原 'stats' 组合卡类型保留为遗留兼容渲染（旧布局按四联小卡打包呈现）。
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9411, current_timestamp, current_timestamp, 0, 0, 0, 'statTodo', '待我处理', 'statMetric', '{"metricKey":"todo"}', 1, 21, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'statTodo' AND deleted = 0);

INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9412, current_timestamp, current_timestamp, 0, 0, 0, 'statInitiated', '我发起的', 'statMetric', '{"metricKey":"initiated"}', 1, 22, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'statInitiated' AND deleted = 0);

INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9413, current_timestamp, current_timestamp, 0, 0, 0, 'statProcessed', '本周已办', 'statMetric', '{"metricKey":"processed"}', 1, 23, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'statProcessed' AND deleted = 0);

INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9414, current_timestamp, current_timestamp, 0, 0, 0, 'statDrafts', '草稿', 'statMetric', '{"metricKey":"drafts"}', 1, 24, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'statDrafts' AND deleted = 0);
