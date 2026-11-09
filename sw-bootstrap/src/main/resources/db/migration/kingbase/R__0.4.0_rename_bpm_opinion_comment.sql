-- 0.4.0 多数据库兼容：comment 是 Oracle/达梦保留字，物理列改为 opinion_comment。
-- 用可重复迁移是因为 V0.4.0 版本化脚本在空库上会先于 R__0.2.0 执行（表尚未创建）；
-- 本脚本在 R__0.2.0 之后运行，幂等检查列是否存在。Java 实体字段与对外 API 字段名保持 comment 不变。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'sw_bpm_task_opinion_draft' AND column_name = 'comment'
    ) THEN
        ALTER TABLE sw_bpm_task_opinion_draft RENAME COLUMN comment TO opinion_comment;
    END IF;
END $$;
