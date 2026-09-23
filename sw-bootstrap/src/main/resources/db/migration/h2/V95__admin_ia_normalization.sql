-- ===================================================================
-- V95: 管理后台信息架构规整（表单管理 / 流程管理 / 路径规范化）
--
-- 背景（现状由 dev 库 sys_menu 实测）：
--   1) 目录标题不达意：「低代码」应为「表单管理」，「流程引擎」应为「流程管理」；
--      叶子「低代码概览」就是表单列表页，且路径 form/overview 与应用内真实入口
--      /form/form-def-list（表单设计器「返回表单列表」也用它）不一致；
--   2) 5 个流程页面错挂在「智能体」目录下（实例监控干预/流程模板中心/批量审批/
--      流程交接/流程分析），后台看起来「流程管理」里只有监控与定义；
--   3) 部分叶子存的是**裸段名**（dict/user/role/dept/post、inbox/template/batch-send/
--      record/preference/channel、graph-def/model/tool）。菜单叶子是**平铺注册**成路由的
--      （buildRoutesFromMenu 把子节点按自身 path 直接挂到根），因此裸段名会变成根路径
--      /dict、/user、/inbox…，与「/system/xxx」「/notify/xxx」的预期不符，也让
--      前后台归属判定失准（收件箱本属前台 notify/inbox，却因路径是 inbox 被判成后台）。
--
-- 约束：
--   · 只改展示与归类字段（title / path / parent_id / sort）；不动 id、name（= 路由名，
--     前端标题键映射与既有测试依赖它）、component、permission、hidden 与按钮行，
--     故权限点与既有角色授权不受影响；
--   · 幂等：每条 UPDATE 均带「旧值守卫」，重复执行无副作用。
-- ===================================================================

-- 1) 目录更名
UPDATE sys_menu SET title = '表单管理', update_time = current_timestamp
WHERE id = 2 AND title = '低代码';

UPDATE sys_menu SET title = '流程管理', update_time = current_timestamp
WHERE id = 5 AND title = '流程引擎';

-- 2) 低代码概览 → 表单列表（路径对齐应用内真实入口 /form/form-def-list）
UPDATE sys_menu SET title = '表单列表', path = 'form/form-def-list', update_time = current_timestamp
WHERE id = 3 AND path = 'form/overview';

-- 3) 流程管理：把流程实例/监控的措辞对齐后台语义
UPDATE sys_menu SET title = '流程实例', update_time = current_timestamp
WHERE id = 22 AND title = '流程监控';

-- 4) 系统管理子页：裸段名 → 全路径
UPDATE sys_menu SET path = 'system/dict', update_time = current_timestamp WHERE id = 10 AND path = 'dict';
UPDATE sys_menu SET path = 'system/user', update_time = current_timestamp WHERE id = 11 AND path = 'user';
UPDATE sys_menu SET path = 'system/role', update_time = current_timestamp WHERE id = 12 AND path = 'role';
UPDATE sys_menu SET path = 'system/dept', update_time = current_timestamp WHERE id = 13 AND path = 'dept';
UPDATE sys_menu SET path = 'system/post', update_time = current_timestamp WHERE id = 14 AND path = 'post';

-- 5) 通知子页：裸段名 → 全路径（收件箱归前台，路径对齐 PORTAL_MENU_PATHS 'notify/inbox'）
UPDATE sys_menu SET path = 'notify/inbox', update_time = current_timestamp WHERE id = 215 AND path = 'inbox';
UPDATE sys_menu SET path = 'notify/template', update_time = current_timestamp WHERE id = 216 AND path = 'template';
UPDATE sys_menu SET path = 'notify/batch-send', update_time = current_timestamp WHERE id = 218 AND path = 'batch-send';
UPDATE sys_menu SET path = 'notify/record', update_time = current_timestamp WHERE id = 324 AND path = 'record';
UPDATE sys_menu SET path = 'notify/preference', update_time = current_timestamp WHERE id = 220 AND path = 'preference';
UPDATE sys_menu SET path = 'notify/channel', update_time = current_timestamp WHERE id = 328 AND path = 'channel';

-- 6) 智能体子页：裸段名 → 全路径
UPDATE sys_menu SET path = 'agent/graph-def', update_time = current_timestamp WHERE id = 15 AND path = 'graph-def';
UPDATE sys_menu SET path = 'agent/model', update_time = current_timestamp WHERE id = 209 AND path = 'model';
UPDATE sys_menu SET path = 'agent/tool', update_time = current_timestamp WHERE id = 212 AND path = 'tool';

-- 7) 流程页面归位：从「智能体」(id=7) 收回「流程管理」(id=5)，排在流程实例/流程定义之后
UPDATE sys_menu SET parent_id = 5, sort = 50, update_time = current_timestamp WHERE id = 368 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 60, update_time = current_timestamp WHERE id = 370 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 70, update_time = current_timestamp WHERE id = 365 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 80, update_time = current_timestamp WHERE id = 366 AND parent_id = 7;
UPDATE sys_menu SET parent_id = 5, sort = 90, update_time = current_timestamp WHERE id = 367 AND parent_id = 7;
-- 8) 开放接口、文件管理并入「系统管理」（不再单列顶层分区）
UPDATE sys_menu SET parent_id = 1, sort = 60, update_time = current_timestamp WHERE id = 9 AND parent_id = 0;
UPDATE sys_menu SET parent_id = 1, sort = 70, update_time = current_timestamp WHERE id = 16 AND parent_id = 0;

-- 9) 表单设计是设计器内部页签（表单列表 → 编辑进入），不在侧栏单列入口；
--    hidden 只约束侧栏，静态路由 /form/designer/:id 深链照常可用。
UPDATE sys_menu SET hidden = true, update_time = current_timestamp WHERE id = 4 AND hidden = false;
