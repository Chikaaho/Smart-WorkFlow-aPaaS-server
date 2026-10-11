-- P62 性能验证装置处置（补充提示02 G2 观测接缝）：批次项结算时刻列（H2 同源）。
ALTER TABLE sw_bpm_command_batch_item ADD COLUMN IF NOT EXISTS settled_at timestamp NULL;
