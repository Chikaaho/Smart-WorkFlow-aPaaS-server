-- ===================================================================
-- V92 (I6): sw_notify 模板标志列统一为 BOOLEAN（双方言同版本身份）
-- 修复真实 PostgreSQL 行为缺陷：V38 起模板 enabled 为 smallint，
-- 而实体（MyBatis-Plus Boolean 字段）在 PG 写入 boolean 触发类型错误。
-- H2 因宽松类型转换未在单库测试中暴露；G2e-T 真实 PG 发送链暴露。
-- 幂等：仅当列仍为 smallint 时执行转换；默认值沿用 1→true 语义。
-- ===================================================================
do $$
begin
  if exists (select 1 from information_schema.columns where table_name = 'sw_notify_template' and column_name = 'enabled' and data_type = 'smallint') then
    alter table sw_notify_template alter column enabled drop default;
    alter table sw_notify_template alter column enabled type boolean using (case when enabled not in (0) then true else false end);
    alter table sw_notify_template alter column enabled set default true;
  end if;
end $$;
