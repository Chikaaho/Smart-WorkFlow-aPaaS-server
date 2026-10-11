-- P64 阶段Ⅱ H2 测试库保真补丁：sw_bpm_child_item.source_rows_json
-- （实体 BpmChildItem.sourceRowsJson 多行授权来源行集合列；主库迁移已含，
--   本模块 H2 子集迁移 V106 建表时缺失，导致真实映射集成查询失败。
--   仅测试 H2 子集追加，IF NOT EXISTS 可重复执行。）

ALTER TABLE sw_bpm_child_item ADD COLUMN IF NOT EXISTS source_rows_json text;
