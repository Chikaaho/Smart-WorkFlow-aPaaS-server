-- V82 (I4 §3.3/§3.2/§3.5/§3.6): 流程运营菜单与按钮权限种子。
-- 挂在既有「流程管理」域（parent=7）之下：
--   365 批量审批（workflow:task:batch）
--   366 流程交接（workflow:handover:manage）
--   367 流程分析（workflow:analytics:view，与 monitor:view 同权，占独立菜单项）
--   368 监控干预页（workflow:monitor:view）+ 369 干预操作按钮
--   370 模板中心（workflow:template:list）+ 371—375 view/create/save/copy/delete 按钮
-- 注意：I4 修订前本迁移曾占用 360—364，与 V73 的按钮段（354—364）冲突，
-- NOT EXISTS 守卫会静默吞掉监控/模板种子；已改用 368+ 空闲段（修复记录见回执 03）。
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 365, current_timestamp, current_timestamp, 0, 0, 7, 'BatchApproval', '批量审批', false, 1,
       'workflow/batch-approval', 'workflow/views/BatchApproval', 'workflow:task:batch', 'Checked', 33
where not exists (select 1 from sys_menu where id = 365);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 366, current_timestamp, current_timestamp, 0, 0, 7, 'TaskHandover', '流程交接', false, 1,
       'workflow/handover', 'workflow/views/TaskHandover', 'workflow:handover:manage', 'Position', 34
where not exists (select 1 from sys_menu where id = 366);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 367, current_timestamp, current_timestamp, 0, 0, 7, 'ProcessAnalytics', '流程分析', false, 1,
       'workflow/analytics', 'workflow/views/ProcessAnalytics', 'workflow:monitor:view', 'TrendCharts', 35
where not exists (select 1 from sys_menu where id = 367);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 368, current_timestamp, current_timestamp, 0, 0, 7, 'InstanceMonitor', '实例监控干预', false, 1,
       'workflow/monitor', 'workflow/views/InstanceMonitor', 'workflow:monitor:view', 'Monitor', 31
where not exists (select 1 from sys_menu where id = 368);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 369, current_timestamp, current_timestamp, 0, 0, 368, 'InstanceMonitorManage', '监控干预操作', false, 2,
       '', '', 'workflow:monitor:manage', '', 1
where not exists (select 1 from sys_menu where id = 369);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 370, current_timestamp, current_timestamp, 0, 0, 7, 'TemplateCenter', '流程模板中心', false, 1,
       'workflow/templates', 'workflow/views/TemplateCenter', 'workflow:template:list', 'Files', 32
where not exists (select 1 from sys_menu where id = 370);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 371, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateView', '模板查看', false, 2,
       '', '', 'workflow:template:view', '', 1
where not exists (select 1 from sys_menu where id = 371);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 372, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateCreate', '模板新建', false, 2,
       '', '', 'workflow:template:create', '', 2
where not exists (select 1 from sys_menu where id = 372);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 373, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateSave', '模板编辑', false, 2,
       '', '', 'workflow:template:save', '', 3
where not exists (select 1 from sys_menu where id = 373);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 374, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateCopy', '模板复制', false, 2,
       '', '', 'workflow:template:copy', '', 4
where not exists (select 1 from sys_menu where id = 374);
insert into sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title,
                      hidden, menu_type, path, component, permission, icon, sort)
select 375, current_timestamp, current_timestamp, 0, 0, 370, 'TemplateDelete', '模板删除', false, 2,
       '', '', 'workflow:template:delete', '', 5
where not exists (select 1 from sys_menu where id = 375);
