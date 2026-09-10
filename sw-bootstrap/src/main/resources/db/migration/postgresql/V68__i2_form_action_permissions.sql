-- ===================================================================
-- V68: P60 I2 低代码表单收口 — 表单数据动作权限按钮
--
-- 方向：direction-stage-i2-low-code-form-closure §4.6 —— 动作权限覆盖
-- 创建、查看、编辑、删除/停用、导入、导出；页面按钮、深链与真实接口
-- 结果必须一致，服务端 @PreAuthorize 是唯一权威。
--
-- 内容：
--   注册 I2 数据动作按钮权限（menu_type=2，挂低代码→表单设计菜单 parent_id=4）：
--     form:data:submit   —— 表单填报/正式提交
--     form:data:edit     —— 记录更新
--     form:data:delete   —— 记录删除
--     form:data:query    —— 记录列表/详情查询
--   （form:data:import/export 已由 V43 登记，此处不重复）
--   停用/启用与列表配置沿用既有 form:design:publish / form:design:save 基线码。
--
-- 约束：
--   · 按钮 id 使用 350-353（避开既有 1-349）
--   · 全部幂等：NOT EXISTS 守卫，可安全重复执行
-- ===================================================================

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 350, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataSubmit', '表单填报提交', false, 2,
       '', '', 'form:data:submit', '', 11
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 350);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 351, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataEdit', '记录编辑', false, 2,
       '', '', 'form:data:edit', '', 12
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 351);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 352, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataDelete', '记录删除', false, 2,
       '', '', 'form:data:delete', '', 13
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 352);

INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
SELECT 353, current_timestamp, current_timestamp, 0, 0, 4, 'FormDataQuery', '记录查询', false, 2,
       '', '', 'form:data:query', '', 14
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 353);
