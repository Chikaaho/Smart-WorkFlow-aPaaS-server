-- V0.1.5 (P63): 动态并行语义 v2。
-- 语义版本 2：按对象身份生成分支（USER=人、DEPT=部门→负责人），同负责人不同部门保持独立分支；
-- 轮次以多实例根 executionId 标识（同 execution 恢复复用、退回重入新轮次，旧轮次行保留）；
-- source_refs 保留全部来源位置（表格行 id / 字段引用）。
-- semantic_version IS NULL = 旧语义行（按负责人合并），行为不变。
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN semantic_version SMALLINT;
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN object_type VARCHAR(16);
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN object_id VARCHAR(64);
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN round_no BIGINT;
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN execution_id VARCHAR(64);
ALTER TABLE sw_bpm_dynamic_branch ADD COLUMN source_refs TEXT;
DROP INDEX IF EXISTS uk_sw_bpm_dyn_branch;
CREATE UNIQUE INDEX uk_sw_bpm_dyn_branch
    ON sw_bpm_dynamic_branch (tenant_id, process_instance_id, node_key, round_no, branch_index);
