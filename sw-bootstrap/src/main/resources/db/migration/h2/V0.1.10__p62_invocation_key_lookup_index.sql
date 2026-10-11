-- P62 性能验证装置处置（NP01-2，RA08/RA09 登记：invocation_key 索引迁移）：
-- 追加非破坏性查找索引（与 postgresql 链同版本同义）；纯追加，不改任何既有列/行。
CREATE INDEX idx_sw_form_txn_inv_key_lookup ON sw_form_txn_invocation (invocation_key);
