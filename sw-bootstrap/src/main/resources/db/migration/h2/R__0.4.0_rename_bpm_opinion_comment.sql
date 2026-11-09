-- 0.4.0 多数据库兼容：comment 是 Oracle/达梦保留字，物理列改为 opinion_comment。
-- 用可重复迁移是因为 V0.4.0 版本化脚本在空库上会先于 R__0.2.0 执行（表尚未创建）。
-- H2 的 RENAME COLUMN 不支持 IF EXISTS，借助 Java 别名做列存在性检查。
CREATE ALIAS IF NOT EXISTS SW_RENAME_COLUMN_IF_EXISTS FOR "com.sw.ck.bootstrap.migration.MigrationSupport.renameColumnIfExists";
CALL SW_RENAME_COLUMN_IF_EXISTS('SW_BPM_TASK_OPINION_DRAFT', 'COMMENT', 'OPINION_COMMENT');
DROP ALIAS IF EXISTS SW_RENAME_COLUMN_IF_EXISTS;
