-- 0.2.2 F2 应用导入导出：导入记录表 + 应用管理导出/导入按钮权限（D022-10～15）。
--
-- 导入记录（D022-15）：每次成功导入留一条记录（操作人、时间、包名、结果与导入报告）。
-- 记录行与导入写入处于同一事务，任一步失败整体回滚，不留部分导入数据。
-- 报告 JSON 存 report_json，供导入后查看与下载（原 key → 新 key、复用对象、置空引用）。
--
-- 权限：导出 form:app:export 与导入 form:app:import 为两个独立按钮权限，
-- 默认授予所有已持有应用管理（form:app:manage，菜单 9130）授权的角色；
-- 其他角色仍须经角色菜单管理显式授权，superadmin 按平台既有语义旁路。
-- 菜单 id 段 9100—9199（已占 9130/9131，本脚本用 9132/9133）；
-- sys_role_menu id 按 MAX(id)+ROW_NUMBER() 顺延（同 R__bf20261020_form_data_admin_grants 口径）。

create table sw_form_app_import_record (
    id              varchar(36)     not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    package_name    varchar(200)    not null,
    file_name       varchar(255),
    app_id          varchar(36),
    app_name        varchar(200),
    result          varchar(20)     not null,
    report_json     text
);

create index idx_sw_form_app_import_record_tenant on sw_form_app_import_record (tenant_id);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 9132, current_timestamp, current_timestamp, 0, 0, 9130, 'FormAppExport', '应用导出', false, 2,
       '', '', 'form:app:export', '', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9132)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'FormAppExport' AND parent_id = 9130 AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 9133, current_timestamp, current_timestamp, 0, 0, 9130, 'FormAppImport', '应用导入', false, 2,
       '', '', 'form:app:import', '', 2
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9133)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE name = 'FormAppImport' AND parent_id = 9130 AND deleted = 0);

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY rm.tenant_id, rm.role_id),
       current_timestamp, current_timestamp, 0, 0, rm.tenant_id, rm.role_id, 9132
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0
WHERE rm.menu_id = 9130
  AND rm.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu x
    WHERE x.tenant_id = rm.tenant_id
      AND x.role_id = rm.role_id
      AND x.menu_id = 9132
      AND x.deleted = 0
  );

INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT (SELECT COALESCE(MAX(id), 0) FROM sys_role_menu) + ROW_NUMBER() OVER (ORDER BY rm.tenant_id, rm.role_id),
       current_timestamp, current_timestamp, 0, 0, rm.tenant_id, rm.role_id, 9133
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0
WHERE rm.menu_id = 9130
  AND rm.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu x
    WHERE x.tenant_id = rm.tenant_id
      AND x.role_id = rm.role_id
      AND x.menu_id = 9133
      AND x.deleted = 0
  );
