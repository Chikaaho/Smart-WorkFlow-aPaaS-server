-- ===================================================================
-- V67: P60 I1 组织与权限底座 — PostgreSQL/H2 通用方言
--
-- 内容：
--   1. sys_user_post 增加任职部门 dept_id（岗位在部门内承担，方向 §3.1）：
--      NOT NULL DEFAULT 0 起步，再按用户主部门回填，0 仅保留给
--      主部门为空的历史数据（只参与岗位级选人，不参与部门+岗位选人）。
--   2. sw_bpm_participant_snapshot 增加参与人展示名快照 participant_name：
--      节点进入时冻结办理人可读身份，后续改名/停用不再重写历史展示
--      （历史流程身份不被改写，方向 §3.1）。
--   3. 部门/岗位管理按钮权限种子（menu_type=2，id 340-345，避开既有
--      1-337），父菜单按 permission 定位（system:dept:list /
--      system:post:list，V15 已建），并授予角色 2（管理员，对齐 V45
--      「3000 + 菜单 id」手法）。DeptController/PostController 将补
--      @PreAuthorize，无该批权限的非超管用户此前可越权增删改。
--
-- 约束：
--   · 全部幂等/前向：不加回滚段，ALTER 仅在列不存在时执行效果为幂等
--     （Flyway 版本链保证单次执行）；种子 NOT EXISTS 守卫可重复执行
--   · 与既有 V45/V47/V61 种子风格一致，不硬编码父 id
-- ===================================================================

-- -------------------- 1. 岗位任职部门 --------------------
ALTER TABLE sys_user_post ADD COLUMN dept_id bigint NOT NULL DEFAULT 0;

UPDATE sys_user_post
SET dept_id = COALESCE(
    (SELECT u.dept_id FROM sys_user u
      WHERE u.id = sys_user_post.user_id AND u.deleted = 0),
    0)
WHERE dept_id = 0;

CREATE INDEX idx_sys_user_post_dept ON sys_user_post (tenant_id, dept_id, deleted);

-- -------------------- 2. 参与人展示名快照 --------------------
ALTER TABLE sw_bpm_participant_snapshot ADD COLUMN participant_name varchar(100);

-- -------------------- 3. 部门管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 340, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptCreate', '部门新增', false, 2, '', '', 'system:dept:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 341, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptUpdate', '部门修改', false, 2, '', '', 'system:dept:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 342, current_timestamp, current_timestamp, 0, 0, p.id, 'DeptDelete', '部门删除', false, 2, '', '', 'system:dept:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:dept:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:dept:delete' AND m.deleted = 0);

-- -------------------- 4. 岗位管理按钮 --------------------
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 343, current_timestamp, current_timestamp, 0, 0, p.id, 'PostCreate', '岗位新增', false, 2, '', '', 'system:post:create', '', 11
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:create' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 344, current_timestamp, current_timestamp, 0, 0, p.id, 'PostUpdate', '岗位修改', false, 2, '', '', 'system:post:update', '', 12
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:update' AND m.deleted = 0);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 345, current_timestamp, current_timestamp, 0, 0, p.id, 'PostDelete', '岗位删除', false, 2, '', '', 'system:post:delete', '', 13
FROM sys_menu p
WHERE p.permission = 'system:post:list' AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.permission = 'system:post:delete' AND m.deleted = 0);

-- -------------------- 5. 授予角色 2（管理员） --------------------
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version, tenant_id, role_id, menu_id)
SELECT 3000 + m.id, current_timestamp, current_timestamp, 0, 0, 0, 2, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND m.permission IN ('system:dept:create', 'system:dept:update', 'system:dept:delete',
                       'system:post:create', 'system:post:update', 'system:post:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 2 AND rm.menu_id = m.id AND rm.deleted = 0)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = 2 AND r.deleted = 0);
