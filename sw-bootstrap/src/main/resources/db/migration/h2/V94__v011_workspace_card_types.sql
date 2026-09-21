-- V94: V011-BUG-002 — 租户级工作台卡片类型与低代码元数据契约
-- 卡片类型只保存安全 renderer_key；renderer_key 由 Web 注册表解析，不是可执行路径。

CREATE TABLE IF NOT EXISTS sys_workspace_card_type (
    id              bigint          not null primary key,
    create_time     timestamp       not null default current_timestamp,
    create_by       bigint,
    update_time     timestamp       not null default current_timestamp,
    update_by       bigint,
    deleted         smallint        not null default 0,
    tenant_id       bigint          not null default 0,
    version         bigint          not null default 0,
    type_code       varchar(64)     not null,
    display_name    varchar(100)    not null,
    renderer_key    varchar(64)     not null,
    metadata_json   text            not null default '{}',
    default_span    smallint        not null default 1,
    default_order   integer         not null default 1,
    status          smallint        not null default 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_workspace_card_type
    ON sys_workspace_card_type (tenant_id, type_code, deleted);

INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9401, current_timestamp, current_timestamp, 0, 0, 0, 'todo', '我的待办', 'todo', '{}', 1, 2, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'todo' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9402, current_timestamp, current_timestamp, 0, 0, 0, 'stats', '统计概览', 'stats', '{}', 2, 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'stats' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9403, current_timestamp, current_timestamp, 0, 0, 0, 'favoriteItems', '快捷发起', 'favorites', '{}', 1, 3, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'favoriteItems' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9404, current_timestamp, current_timestamp, 0, 0, 0, 'activity', '业务动态', 'activity', '{}', 1, 4, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'activity' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9405, current_timestamp, current_timestamp, 0, 0, 0, 'efficiency', '流程效能', 'efficiency', '{}', 1, 5, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'efficiency' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9406, current_timestamp, current_timestamp, 0, 0, 0, 'drafts', '草稿', 'drafts', '{}', 1, 6, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'drafts' AND deleted = 0);
INSERT INTO sys_workspace_card_type
    (id, create_time, update_time, deleted, tenant_id, version, type_code, display_name,
     renderer_key, metadata_json, default_span, default_order, status)
SELECT 9407, current_timestamp, current_timestamp, 0, 0, 0, 'messages', '消息', 'messages', '{}', 1, 7, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_workspace_card_type WHERE tenant_id = 0 AND type_code = 'messages' AND deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 326, current_timestamp, current_timestamp, 0, 0, 5, 'WorkspaceCardTypes', '工作台卡片', false, 1,
       'workflow/workspace-card-types', 'workflow/views/WorkspaceCardTypeList', 'workflow:workspace:manage', 'Grid', 26
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 326);
