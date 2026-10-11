-- P62 性能验证装置处置（补充提示02 G2 观测接缝）：批次项结算时刻列。
-- RA08 登记 item 表 update_time 无生产写者（≡插入时刻），逐项领取/结算无法按真实时点
-- 观测，只能用批命令级代理或全批完成保守上界。本迁移为 sw_bpm_command_batch_item
-- 追加可空 settled_at：BatchInvokeCommandHandler 在逐项独立事务内写终态时同事务写入
-- （真实结算时刻，含队列等待+切片排队因果）。纯追加可空列，不改既有行语义；
-- H2 侧同源（V0.1.11 h2）。
ALTER TABLE sw_bpm_command_batch_item ADD COLUMN IF NOT EXISTS settled_at timestamp NULL;
