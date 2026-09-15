-- ===================================================================
-- V93 (I6): sw_notify 规则/订阅/渠道标志列统一为 BOOLEAN（双方言同版本身份）
-- ===================================================================
-- 与 postgresql/V93 同版本身份：把 sw_notify_rule / sw_notify_subscription /
-- sw_notify_channel_config 的 enabled 由 smallint 收敛为 boolean，与
-- postgresql 侧及实体（MyBatis-Plus Boolean 字段）口径一致，避免单库测试
-- 依赖 H2 的 smallint↔boolean 隐式转换而掩盖真实差异。
-- H2 的 SET DATA TYPE 直接做 0/1 → false/true 转换，且重复执行安全。
-- ===================================================================
alter table sw_notify_rule alter column enabled set data type boolean;
alter table sw_notify_rule alter column enabled set default true;

alter table sw_notify_subscription alter column enabled set data type boolean;
alter table sw_notify_subscription alter column enabled set default true;

alter table sw_notify_channel_config alter column enabled set data type boolean;
alter table sw_notify_channel_config alter column enabled set default false;

-- H2 侧 V92 为无操作化确认，模板列在此补齐，使双方言链尾口径一致。
alter table sw_notify_template alter column enabled set data type boolean;
alter table sw_notify_template alter column enabled set default true;
