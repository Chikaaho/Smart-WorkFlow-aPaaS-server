-- I5 复验 03（G1a）：清除 sw_form_def.form_key 上的全局唯一残留。
-- V7(inline UNIQUE) 在 H2 中生成的匿名唯一约束（CONSTRAINT_xx_INDEX_A）未被
-- V13 的 DROP INDEX 移除，导致跨租户同键 form_key 仍被数据库全局拒绝，
-- V83 的租户级唯一被该残留架空。本迁移按 information_schema 动态定位并删除
-- 该匿名 UNIQUE 约束（V83 后表上唯一的 UNIQUE 约束即此残留；租户级唯一是
-- 普通唯一索引 uk_sw_form_def_form_key，不在 TABLE_CONSTRAINTS 中）。
EXECUTE IMMEDIATE 'ALTER TABLE sw_form_def DROP CONSTRAINT IF EXISTS ' || (
    SELECT c.CONSTRAINT_NAME
    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS c
    WHERE c.TABLE_NAME = 'SW_FORM_DEF' AND c.CONSTRAINT_TYPE = 'UNIQUE'
    LIMIT 1);
