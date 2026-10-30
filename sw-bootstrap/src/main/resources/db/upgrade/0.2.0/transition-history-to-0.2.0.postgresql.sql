-- 0.2.0 发布收敛：历史衔接脚本（postgresql）。
-- 适用：在役库已随开发分支运行 0.1.8/0.1.9/0.1.10 增量（历史为 V0.1.7__baseline_seed.sql、
--       V0.1.8__tenant_username_unique.sql、V0.1.9__tenant_admin_identity.sql 与
--       V0.1.10__tenant_status_audit.sql 四条 versioned 记录），结构已达 0.2.0 终态。
-- 作用：把 flyway_schema_history 中全部 versioned 记录替换为一条 V0.2.0__baseline_seed.sql
--       成功记录（checksum=1897854550，与发布种子逐字节对应），使 validate-on-migrate 通过且重复启动零重复执行。
-- 边界：只处理 version 非空行；R__ 可重复迁移记录与全部业务数据一律不动。
-- 要求：执行前完成数据库备份；本脚本在单事务内执行，恰好运行一次。

-- ======== 历史衔接：versioned 记录收敛为 V0.2.0 单条 ========
BEGIN;

DELETE FROM "flyway_schema_history" WHERE "version" IS NOT NULL;

INSERT INTO "flyway_schema_history" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success")
SELECT COALESCE(MAX("installed_rank"), 0) + 1,
       '0.2.0', 'baseline seed', 'SQL', 'V0.2.0__baseline_seed.sql', 1897854550,
       current_user, CURRENT_TIMESTAMP, 0, TRUE
FROM "flyway_schema_history";

COMMIT;
