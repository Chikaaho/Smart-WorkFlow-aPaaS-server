-- ===================================================================
-- V83 (I5): sw_form_def form_key 唯一约束收敛为租户级
-- ===================================================================
-- 目的：相同业务 formKey 允许不同租户独立使用，同一租户内保持唯一（I5 §3.1）。
-- 语义：
--   1. uk_sw_form_def_form_key 从 (form_key, deleted) 改为 (tenant_id, form_key, deleted)。
--   2. sw_form_config(table_name) 保持数据库全局唯一不变——table_name 是动态宽表
--      物理表名（sw_form_{nanoId}），属于数据库全局命名空间；nanoId 由服务端随机
--      生成，天然全局无碰撞，不降级为租户级（I5 §3.1：不得把物理表名唯一约束改成
--      租户级而制造两个配置争用同一物理表）。
--   3. 历史数据全部 tenant_id=0（V1 默认），改约束不产生冲突行；迁移不改写任何
--      历史行的归属租户（I5 §7 兼容边界）。
-- 兼容：前向迁移，仅重建索引；H2 与 PostgreSQL 逐字节语义一致。
-- ===================================================================

DROP INDEX IF EXISTS uk_sw_form_def_form_key;
CREATE UNIQUE INDEX uk_sw_form_def_form_key ON sw_form_def (tenant_id, form_key, deleted);
