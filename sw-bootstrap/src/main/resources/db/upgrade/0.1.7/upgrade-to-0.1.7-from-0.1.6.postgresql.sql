-- 0.1.7 发布收敛：存量库升级脚本（postgresql，自已衔接 0.1.6 状态升级）。
-- 适用：库已按 0.1.6 通道衔接（历史为单条 V0.1.6 成功记录、结构为 0.1.6 终态），
--       尚未执行 0.1.7 期增量（菜单调整与应用可见范围多维度）。执行前须现场核实 flyway_schema_history。
-- 内容 = 0.1.7 期增量 DDL/承接（V0.1.7__bugfix017_menu_and_app_scope.sql 逐字节嵌入，段头注明来源）
--        + 历史衔接（删除 versioned 记录，写入 V0.1.7__baseline_seed.sql 成功记录，checksum=2016046975）。
-- 边界：只处理 version 非空的历史记录；R__ 可重复迁移记录与业务数据不动。
-- 要求：执行前完成数据库备份；本脚本恰好运行一次（增量段部分语句无 IF NOT EXISTS 守卫，
--       重复执行会在首个无守卫语句处显式失败——这是防重复执行的保护行为，不是缺陷）。
-- 事务：增量 DDL 段逐语句提交由执行方数据库客户端决定；历史衔接段在单事务内执行。

-- ======== 来源: V0.1.7__bugfix017_menu_and_app_scope.sql ========
-- ===================================================================
-- 0.1.7 Bug 修复列车（1.0.7bug 清单）
-- 1) 后台菜单调整（0.1.7 #6）：定时任务并入系统管理；应用管理上提为顶级第二项
-- 2) 应用可见范围多维度（0.1.7 #1）：新增 sw_form_app_scope（USER/DEPT/ROLE/POST/TENANT），
--    存量指定用户迁移为 USER 维度；sw_form_app_user 保留为历史只读，不再写入
-- ===================================================================

-- ======== 1. 菜单调整（按 id 定点 UPDATE，幂等） ========
-- 定时任务（顶级目录 id=17）并入系统管理（id=1），组内排序 90
UPDATE sys_menu SET parent_id = 1, sort = 90 WHERE id = 17 AND deleted = 0;
-- 应用管理（id=9130）上提为顶级菜单，排序 15 = 系统管理(10) 与 表单管理(20) 之间的第二项
UPDATE sys_menu SET parent_id = 0, sort = 15 WHERE id = 9130 AND deleted = 0;

-- ======== 2. 应用可见范围多维度表 ========
CREATE TABLE sw_form_app_scope (
    id          VARCHAR(36) PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    app_id      VARCHAR(36) NOT NULL,
    scope_type  VARCHAR(20) NOT NULL,
    scope_id    VARCHAR(64) NOT NULL,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by   BIGINT,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by   BIGINT,
    version     BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_form_app_scope_app ON sw_form_app_scope (tenant_id, app_id, deleted);

-- 存量「指定用户」迁移为 USER 维度（沿用源行 id 保证唯一；关联行是纯配置关系）
INSERT INTO sw_form_app_scope (id, tenant_id, app_id, scope_type, scope_id,
                               create_time, update_time, deleted, version)
SELECT id, tenant_id, app_id, 'USER', CAST(user_id AS VARCHAR(64)),
       create_time, update_time, deleted, version
FROM sw_form_app_user;

-- ======== 历史衔接：versioned 记录收敛为 V0.1.7 单条 ========
BEGIN;

DELETE FROM "flyway_schema_history" WHERE "version" IS NOT NULL;

INSERT INTO "flyway_schema_history" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success")
SELECT COALESCE(MAX("installed_rank"), 0) + 1,
       '0.1.7', 'baseline seed', 'SQL', 'V0.1.7__baseline_seed.sql', 2016046975,
       current_user, CURRENT_TIMESTAMP, 0, TRUE
FROM "flyway_schema_history";

COMMIT;
