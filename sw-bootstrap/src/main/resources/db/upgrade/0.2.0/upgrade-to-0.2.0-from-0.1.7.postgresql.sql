-- 0.2.0 发布收敛：存量库升级脚本（postgresql，自已衔接 0.1.7 状态升级）。
-- 适用：库已按 0.1.7 通道衔接（历史为单条 V0.1.7 成功记录、结构为 0.1.7 终态），
--       尚未执行 0.1.8/0.1.9/0.1.10 增量（租户用户名唯一、租户管理员身份、租户状态审计）。
--       执行前须现场核实 flyway_schema_history。
-- 内容 = 0.1.8/0.1.9/0.1.10 增量 DDL/承接（对应文件逐字节嵌入，段头注明来源）
--        + 历史衔接（删除 versioned 记录，写入 V0.2.0__baseline_seed.sql 成功记录，checksum=1897854550）。
-- 边界：只处理 version 非空的历史记录；R__ 可重复迁移记录与业务数据不动。
-- 要求：执行前完成数据库备份；本脚本恰好运行一次（增量段部分语句无 IF NOT EXISTS 守卫，
--       重复执行会在首个无守卫语句处显式失败——这是防重复执行的保护行为，不是缺陷）。
-- 事务：增量 DDL 段逐语句提交由执行方数据库客户端决定；历史衔接段在单事务内执行。

-- ======== 来源: V0.1.8__tenant_username_unique.sql ========
-- 0.2.0 T020：用户名按租户唯一（DR02），登录解析见 LoginUserResolver
DROP INDEX IF EXISTS uk_sys_user_username;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_tenant_username ON sys_user (tenant_id, username, deleted);

-- ======== 来源: V0.1.9__tenant_admin_identity.sql ========
-- T020 / DR02：稳定管理员关联和创建请求去重。只回填唯一、同租户的有效授权关系。
ALTER TABLE sys_tenant ADD COLUMN admin_user_id BIGINT;
ALTER TABLE sys_tenant ADD COLUMN creation_request_id VARCHAR(64);
CREATE UNIQUE INDEX uk_sys_tenant_creation_request ON sys_tenant (creation_request_id);

UPDATE sys_tenant t
SET admin_user_id = (
    SELECT MIN(u.id)
    FROM sys_user u
    JOIN sys_user_role ur ON ur.user_id = u.id AND ur.tenant_id = t.id AND ur.deleted = 0
    JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = t.id AND r.deleted = 0 AND r.status = 1
    WHERE u.tenant_id = t.id AND u.deleted = 0 AND u.status = 0
      AND ((t.id = 0 AND r.code = 'superadmin') OR (t.id <> 0 AND r.code = 'admin'))
    HAVING COUNT(DISTINCT u.id) = 1
)
WHERE t.deleted = 0;

-- 租户管理恢复为真实授权菜单；仅绑定租户 0 的超级管理员角色，不进入普通管理员模板。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9600, current_timestamp, current_timestamp, 0, 0, 1, 'SystemTenant', '租户管理', false, 1, 'system/tenant', 'system/views/TenantList', 'system:tenant:list', 'OfficeBuilding', 60
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9600)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 1 AND name = 'SystemTenant' AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT 9601, current_timestamp, current_timestamp, 0, 0, 0, r.id, 9600
FROM sys_role r
WHERE r.tenant_id = 0 AND r.code = 'superadmin' AND r.deleted = 0
  AND EXISTS (SELECT 1 FROM sys_menu WHERE id = 9600 AND deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE id = 9601)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = r.id AND menu_id = 9600 AND tenant_id = 0 AND deleted = 0);

-- ======== 来源: V0.1.10__tenant_status_audit.sql ========
create table sys_tenant_status_audit (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null,
    version         bigint          not null default 0,
    action          varchar(20)     not null,
    old_status      smallint,
    new_status      smallint,
    expire_time     timestamp,
    reason          varchar(500)    not null,
    operator_id     bigint          not null,
    occurred_at     timestamp       not null,
    result          varchar(200)    not null
);

create index idx_sys_tenant_status_audit_tenant_time
    on sys_tenant_status_audit (tenant_id, occurred_at);

create unique index uk_sys_tenant_status_audit_expiry
    on sys_tenant_status_audit (tenant_id, action, expire_time);

-- ======== 历史衔接：versioned 记录收敛为 V0.2.0 单条 ========
BEGIN;

DELETE FROM "flyway_schema_history" WHERE "version" IS NOT NULL;

INSERT INTO "flyway_schema_history" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success")
SELECT COALESCE(MAX("installed_rank"), 0) + 1,
       '0.2.0', 'baseline seed', 'SQL', 'V0.2.0__baseline_seed.sql', 1897854550,
       current_user, CURRENT_TIMESTAMP, 0, TRUE
FROM "flyway_schema_history";

COMMIT;
