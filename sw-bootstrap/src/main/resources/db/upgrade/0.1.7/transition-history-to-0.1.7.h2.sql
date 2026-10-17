-- 0.1.7 发布收敛：历史衔接脚本（h2）。
-- 适用：在役库已随开发分支运行 0.1.7 期迁移（历史为 V0.1.6__baseline_seed.sql 与
--       V0.1.7__bugfix017_menu_and_app_scope.sql 两条 versioned 记录），结构已达 0.1.7 终态。
-- 作用：把 flyway_schema_history 中全部 versioned 记录替换为一条 V0.1.7__baseline_seed.sql
--       成功记录（checksum=-1195786777，与发布种子逐字节对应），使 validate-on-migrate 通过且重复启动零重复执行。
-- 边界：只处理 version 非空行；R__ 可重复迁移记录与全部业务数据一律不动。
-- 要求：执行前完成数据库备份；本脚本在单事务内执行，恰好运行一次。
BEGIN;

DELETE FROM "flyway_schema_history" WHERE "version" IS NOT NULL;

INSERT INTO "flyway_schema_history" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success")
SELECT COALESCE(MAX("installed_rank"), 0) + 1,
       '0.1.7', 'baseline seed', 'SQL', 'V0.1.7__baseline_seed.sql', -1195786777,
       USER, CURRENT_TIMESTAMP, 0, TRUE
FROM "flyway_schema_history";

COMMIT;
