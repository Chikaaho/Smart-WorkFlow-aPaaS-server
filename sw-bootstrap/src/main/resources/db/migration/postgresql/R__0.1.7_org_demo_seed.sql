-- 0.1.7 #41（Owner 需求）：组织架构演示种子——两个租户、同一棵组织树、每个节点 2 个用户。
-- 幂等可重复：全部 INSERT ... SELECT ... WHERE NOT EXISTS，重复执行不产生重复行。
-- 租户：9001 demo-org-a「演示集团A」、9002 demo-org-b「演示集团B」（status=0 启用）。
-- 组织树（每租户 7 节点）：总公司 ├ 分公司1 ├ 分公司2 ├ 部门1 / └ 部门2 └ 团队1 └ 分公司3。
-- 用户命名：demo_{a|b}_{hq|br1|br2|br3|dept1|dept2|team1}_{1|2}，每节点 2 人（共 14 人/租户）。
-- 密码（BCrypt cost=10，与系统 PasswordEncoder 同参）：
--   * 每个租户「总公司」第 1 人（demo_{a|b}_hq_1）＝租户管理员，口令 AdminP@ssw0rd，持 demo_admin 角色
--     （授权集＝既有 admin 角色(role_id=2) 的产品授权集 ∪ 前台 7 页，不自行全量授权）；
--   * 其余演示用户口令 123456，持 demo_user 角色（仅前台 7 个页面：待办/已办/我发起的/我的草稿/我的已办/流程中心/抄送我的）。
-- 数据范围：demo_admin = ALL(0)，demo_user = SELF(3)。
-- 说明：本文件是演示种子（用户名以 demo_ 开头、租户 code 以 demo-org- 开头），
--   如需在其它环境停用/移除，删除本文件并清理 9001/9002 租户数据即可；不影响既有 tenant-0/default 数据。

INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description)
SELECT 9001, current_timestamp, current_timestamp, 0, 0, 0, '演示集团A', 'demo-org-a', 0, '0.1.7 #41 组织架构演示租户'
WHERE NOT EXISTS (SELECT 1 FROM sys_tenant WHERE id = 9001);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 91001, current_timestamp, current_timestamp, 0, 9001, 0, 0, '总公司', 'demo-a-hq', 10, 0, '', 91101
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 91001);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 91002, current_timestamp, current_timestamp, 0, 9001, 0, 91001, '分公司1', 'demo-a-br1', 20, 0, '', 91103
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 91002);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 91003, current_timestamp, current_timestamp, 0, 9001, 0, 91001, '分公司2', 'demo-a-br2', 30, 0, '', 91105
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 91003);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 91004, current_timestamp, current_timestamp, 0, 9001, 0, 91001, '分公司3', 'demo-a-br3', 40, 0, '', 91107
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 91004);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 91005, current_timestamp, current_timestamp, 0, 9001, 0, 91003, '部门1', 'demo-a-dept1', 10, 0, '', 91109
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 91005);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 91006, current_timestamp, current_timestamp, 0, 9001, 0, 91003, '部门2', 'demo-a-dept2', 20, 0, '', 91111
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 91006);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 91007, current_timestamp, current_timestamp, 0, 9001, 0, 91006, '团队1', 'demo-a-team1', 10, 0, '', 91113
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 91007);
INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version, name, code, sort, status, data_scope, remark, built_in)
SELECT 9501, current_timestamp, current_timestamp, 0, 9001, 0, '租户管理员', 'demo_admin', 10, 1, 0, '0.1.7 #41 演示种子：与既有管理员同权（数据范围 ALL）', false
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 9501);
INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version, name, code, sort, status, data_scope, remark, built_in)
SELECT 9502, current_timestamp, current_timestamp, 0, 9001, 0, '普通用户', 'demo_user', 20, 1, 3, '0.1.7 #41 演示种子：前台流程中心/我的门户（数据范围 SELF）', false
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 9502);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91101, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_hq_1', '$2a$10$sj2aFVKYCMigtP4Lf31Y7O.UPgp5nKY3Xh6E2v4/FsU9ldYxzi95.', '张伟', 91001, 0, 0, 0, '0.1.7 #41 演示账号（租户管理员）'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91101);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9601, current_timestamp, current_timestamp, 0, 9001, 0, 91101, 9501
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9601);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91102, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_hq_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '王芳', 91001, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91102);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9602, current_timestamp, current_timestamp, 0, 9001, 0, 91102, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9602);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91103, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_br1_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '李强', 91002, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91103);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9603, current_timestamp, current_timestamp, 0, 9001, 0, 91103, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9603);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91104, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_br1_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '刘洋', 91002, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91104);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9604, current_timestamp, current_timestamp, 0, 9001, 0, 91104, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9604);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91105, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_br2_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '陈静', 91003, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91105);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9605, current_timestamp, current_timestamp, 0, 9001, 0, 91105, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9605);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91106, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_br2_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '杨帆', 91003, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91106);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9606, current_timestamp, current_timestamp, 0, 9001, 0, 91106, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9606);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91107, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_br3_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '韩磊', 91004, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91107);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9607, current_timestamp, current_timestamp, 0, 9001, 0, 91107, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9607);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91108, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_br3_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '曹丽', 91004, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91108);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9608, current_timestamp, current_timestamp, 0, 9001, 0, 91108, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9608);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91109, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_dept1_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '赵磊', 91005, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91109);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9609, current_timestamp, current_timestamp, 0, 9001, 0, 91109, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9609);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91110, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_dept1_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '孙悦', 91005, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91110);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9610, current_timestamp, current_timestamp, 0, 9001, 0, 91110, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9610);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91111, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_dept2_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '周涛', 91006, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91111);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9611, current_timestamp, current_timestamp, 0, 9001, 0, 91111, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9611);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91112, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_dept2_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '吴敏', 91006, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91112);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9612, current_timestamp, current_timestamp, 0, 9001, 0, 91112, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9612);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91113, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_team1_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '郑凯', 91007, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91113);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9613, current_timestamp, current_timestamp, 0, 9001, 0, 91113, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9613);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 91114, current_timestamp, current_timestamp, 0, 9001, 0, 'demo_a_team1_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '冯雪', 91007, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 91114);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9614, current_timestamp, current_timestamp, 0, 9001, 0, 91114, 9502
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9614);
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT 950000 + m.id, current_timestamp, current_timestamp, 0, 9001, 0, 9501, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND (m.id = 5 OR m.path IN ('workflow/todo', 'workflow/processed', 'workflow/my-instances', 'workflow/my-drafts', 'workflow/my-processed', 'workflow/catalog', 'workflow/my-cc')
       OR EXISTS (SELECT 1 FROM sys_role_menu rm2 WHERE rm2.role_id = 2 AND rm2.menu_id = m.id AND rm2.deleted = 0))
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.tenant_id = 9001 AND rm.role_id = 9501 AND rm.menu_id = m.id AND rm.deleted = 0);
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT 951000 + m.id, current_timestamp, current_timestamp, 0, 9001, 0, 9502, m.id
FROM sys_menu m
WHERE m.deleted = 0 AND (m.id = 5 OR m.path IN ('workflow/todo', 'workflow/processed', 'workflow/my-instances', 'workflow/my-drafts', 'workflow/my-processed', 'workflow/catalog', 'workflow/my-cc'))
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.tenant_id = 9001 AND rm.role_id = 9502 AND rm.menu_id = m.id AND rm.deleted = 0);
INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description)
SELECT 9002, current_timestamp, current_timestamp, 0, 0, 0, '演示集团B', 'demo-org-b', 0, '0.1.7 #41 组织架构演示租户'
WHERE NOT EXISTS (SELECT 1 FROM sys_tenant WHERE id = 9002);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 92001, current_timestamp, current_timestamp, 0, 9002, 0, 0, '总公司', 'demo-b-hq', 10, 0, '', 92101
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 92001);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 92002, current_timestamp, current_timestamp, 0, 9002, 0, 92001, '分公司1', 'demo-b-br1', 20, 0, '', 92103
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 92002);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 92003, current_timestamp, current_timestamp, 0, 9002, 0, 92001, '分公司2', 'demo-b-br2', 30, 0, '', 92105
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 92003);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 92004, current_timestamp, current_timestamp, 0, 9002, 0, 92001, '分公司3', 'demo-b-br3', 40, 0, '', 92107
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 92004);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 92005, current_timestamp, current_timestamp, 0, 9002, 0, 92003, '部门1', 'demo-b-dept1', 10, 0, '', 92109
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 92005);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 92006, current_timestamp, current_timestamp, 0, 9002, 0, 92003, '部门2', 'demo-b-dept2', 20, 0, '', 92111
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 92006);
INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description, leader_id)
SELECT 92007, current_timestamp, current_timestamp, 0, 9002, 0, 92006, '团队1', 'demo-b-team1', 10, 0, '', 92113
WHERE NOT EXISTS (SELECT 1 FROM sys_dept WHERE id = 92007);
INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version, name, code, sort, status, data_scope, remark, built_in)
SELECT 9503, current_timestamp, current_timestamp, 0, 9002, 0, '租户管理员', 'demo_admin', 10, 1, 0, '0.1.7 #41 演示种子：与既有管理员同权（数据范围 ALL）', false
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 9503);
INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version, name, code, sort, status, data_scope, remark, built_in)
SELECT 9504, current_timestamp, current_timestamp, 0, 9002, 0, '普通用户', 'demo_user', 20, 1, 3, '0.1.7 #41 演示种子：前台流程中心/我的门户（数据范围 SELF）', false
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 9504);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92101, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_hq_1', '$2a$10$sj2aFVKYCMigtP4Lf31Y7O.UPgp5nKY3Xh6E2v4/FsU9ldYxzi95.', '张伟', 92001, 0, 0, 0, '0.1.7 #41 演示账号（租户管理员）'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92101);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9621, current_timestamp, current_timestamp, 0, 9002, 0, 92101, 9503
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9621);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92102, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_hq_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '王芳', 92001, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92102);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9622, current_timestamp, current_timestamp, 0, 9002, 0, 92102, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9622);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92103, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_br1_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '李强', 92002, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92103);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9623, current_timestamp, current_timestamp, 0, 9002, 0, 92103, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9623);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92104, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_br1_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '刘洋', 92002, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92104);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9624, current_timestamp, current_timestamp, 0, 9002, 0, 92104, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9624);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92105, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_br2_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '陈静', 92003, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92105);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9625, current_timestamp, current_timestamp, 0, 9002, 0, 92105, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9625);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92106, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_br2_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '杨帆', 92003, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92106);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9626, current_timestamp, current_timestamp, 0, 9002, 0, 92106, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9626);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92107, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_br3_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '韩磊', 92004, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92107);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9627, current_timestamp, current_timestamp, 0, 9002, 0, 92107, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9627);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92108, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_br3_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '曹丽', 92004, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92108);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9628, current_timestamp, current_timestamp, 0, 9002, 0, 92108, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9628);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92109, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_dept1_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '赵磊', 92005, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92109);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9629, current_timestamp, current_timestamp, 0, 9002, 0, 92109, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9629);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92110, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_dept1_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '孙悦', 92005, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92110);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9630, current_timestamp, current_timestamp, 0, 9002, 0, 92110, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9630);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92111, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_dept2_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '周涛', 92006, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92111);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9631, current_timestamp, current_timestamp, 0, 9002, 0, 92111, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9631);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92112, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_dept2_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '吴敏', 92006, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92112);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9632, current_timestamp, current_timestamp, 0, 9002, 0, 92112, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9632);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92113, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_team1_1', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '郑凯', 92007, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92113);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9633, current_timestamp, current_timestamp, 0, 9002, 0, 92113, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9633);
INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, sex, is_admin, remark)
SELECT 92114, current_timestamp, current_timestamp, 0, 9002, 0, 'demo_b_team1_2', '$2a$10$MgHL5oBmgozpnU670jyioOZvOjDEZZcIJ5DmsijTMfHA2ZJYHeW/q', '冯雪', 92007, 0, 0, 0, '0.1.7 #41 演示账号'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 92114);
INSERT INTO sys_user_role (id, create_time, update_time, deleted, tenant_id, version, user_id, role_id)
SELECT 9634, current_timestamp, current_timestamp, 0, 9002, 0, 92114, 9504
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE id = 9634);
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT 952000 + m.id, current_timestamp, current_timestamp, 0, 9002, 0, 9503, m.id
FROM sys_menu m
WHERE m.deleted = 0
  AND (m.id = 5 OR m.path IN ('workflow/todo', 'workflow/processed', 'workflow/my-instances', 'workflow/my-drafts', 'workflow/my-processed', 'workflow/catalog', 'workflow/my-cc')
       OR EXISTS (SELECT 1 FROM sys_role_menu rm2 WHERE rm2.role_id = 2 AND rm2.menu_id = m.id AND rm2.deleted = 0))
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.tenant_id = 9002 AND rm.role_id = 9503 AND rm.menu_id = m.id AND rm.deleted = 0);
INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id)
SELECT 953000 + m.id, current_timestamp, current_timestamp, 0, 9002, 0, 9504, m.id
FROM sys_menu m
WHERE m.deleted = 0 AND (m.id = 5 OR m.path IN ('workflow/todo', 'workflow/processed', 'workflow/my-instances', 'workflow/my-drafts', 'workflow/my-processed', 'workflow/catalog', 'workflow/my-cc'))
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.tenant_id = 9002 AND rm.role_id = 9504 AND rm.menu_id = m.id AND rm.deleted = 0);