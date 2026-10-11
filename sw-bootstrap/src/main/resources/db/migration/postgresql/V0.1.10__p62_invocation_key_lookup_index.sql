-- P62 性能验证装置处置（NP01-2，RA08/RA09 登记：invocation_key 索引迁移）：
-- V0.1.1 的唯一索引 uk_sw_form_txn_inv_key (tenant_id, action_id, invocation_key) 无法服务
-- 仅按 invocation_key 的查询（提交后可见性轮询/配对回查），隔离库中该类查询为顺序扫描。
-- 本迁移追加非破坏性查找索引；纯追加，不改任何既有列/行。
CREATE INDEX idx_sw_form_txn_inv_key_lookup ON sw_form_txn_invocation (invocation_key);
