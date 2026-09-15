-- ===================================================================
-- V93 (I6): sw_notify 规则/订阅/渠道标志列统一为 BOOLEAN（双方言同版本身份）
-- ===================================================================
-- V92 只覆盖 sw_notify_template.enabled。同一实体口径（MyBatis-Plus Boolean
-- 字段）在 sw_notify_rule / sw_notify_subscription / sw_notify_channel_config
-- 上仍为 smallint，真实 PostgreSQL 对 smallint 列与 boolean 参数/值的比较和
-- 写入都直接报错（operator does not exist: smallint = boolean /
-- column "enabled" is of type smallint but expression is of type boolean），
-- 命中规则解析、规则启停、订阅偏好保存等生产路径。
-- H2 对 smallint 与 boolean 的比较做隐式转换，单库测试不会暴露该差异。
-- 幂等：仅当列仍为 smallint 时转换；默认值按 1→true、0→false 语义保持。
-- ===================================================================
do $$
begin
  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_rule' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_rule alter column enabled drop default;
    alter table sw_notify_rule alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_rule alter column enabled set default true;
  end if;

  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_subscription' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_subscription alter column enabled drop default;
    alter table sw_notify_subscription alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_subscription alter column enabled set default true;
  end if;

  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_channel_config' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_channel_config alter column enabled drop default;
    alter table sw_notify_channel_config alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_channel_config alter column enabled set default false;
  end if;

  -- V92 已覆盖模板列；此处兜底 V92 未能生效的环境，保持同版本终态口径一致。
  if exists (select 1 from information_schema.columns
             where table_name = 'sw_notify_template' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_template alter column enabled drop default;
    alter table sw_notify_template alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_template alter column enabled set default true;
  end if;
end $$;
