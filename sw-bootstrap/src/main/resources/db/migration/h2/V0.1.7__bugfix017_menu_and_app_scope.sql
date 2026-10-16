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
