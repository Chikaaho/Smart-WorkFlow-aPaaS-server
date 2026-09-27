-- ===================================================================
-- V97: V012-BUG-011/017/019 —— 菜单信息架构与租户名称唯一化
--
-- 019：区分 V2(id=1, code=default) 与 V85(id=0, code=tenant-0) 两个同名
--      「默认租户」，使登录页租户名称精确解析可用（重名 fail-closed 已实现）。
-- 017：系统管理组子项按 用户/部门/角色/岗位/字典/菜单/文件/开放接口 排序；
--      字典管理转两级目录（字典类型 + 字典数据管理）。
-- 011：新增菜单管理入口（icon/title/sort/hidden 受控配置）。
--
-- 约束：全部幂等（UPDATE 带条件 / INSERT 带 NOT EXISTS 守卫）；不 seed sys_role_menu。
-- ===================================================================

-- 1) 019：租户名称唯一化
UPDATE sys_tenant SET name = '演示租户', update_time = current_timestamp
WHERE id = 1 AND code = 'default' AND name = '默认租户';

-- 2) 017：系统管理组排序
UPDATE sys_menu SET sort = 10, update_time = current_timestamp WHERE id = 11;
UPDATE sys_menu SET sort = 20, update_time = current_timestamp WHERE id = 13;
UPDATE sys_menu SET sort = 30, update_time = current_timestamp WHERE id = 12;
UPDATE sys_menu SET sort = 40, update_time = current_timestamp WHERE id = 14;
UPDATE sys_menu SET sort = 50, update_time = current_timestamp WHERE id = 10;
UPDATE sys_menu SET sort = 70, update_time = current_timestamp WHERE id = 16;
UPDATE sys_menu SET sort = 80, update_time = current_timestamp WHERE id = 9;

-- 3) 017：字典两级 —— 字典管理转目录，子项 字典类型/字典数据管理
UPDATE sys_menu SET menu_type = 0, component = NULL, icon = 'Collection', update_time = current_timestamp
WHERE id = 10;

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 400, current_timestamp, current_timestamp, 0, 0, 10, 'DictType', '字典类型', false, 1, 'system/dict', 'system/views/DictTypeList', 'system:dict:view', 'Collection', 10
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 400);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 401, current_timestamp, current_timestamp, 0, 0, 10, 'DictData', '字典数据管理', false, 1, 'system/dict-data', 'system/views/DictDataList', 'system:dict:view', 'Document', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 401);

-- 4) 011：菜单管理入口
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 410, current_timestamp, current_timestamp, 0, 0, 1, 'MenuManage', '菜单管理', false, 1, 'system/menu', 'system/views/MenuList', 'system:menu:manage', 'Menu', 60
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 410);
