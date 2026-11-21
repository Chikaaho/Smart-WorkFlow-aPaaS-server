-- 1.2.0：图执行请求业务关联（P67 E1-7）与日志等级管理菜单种子（P41 E2）。
-- 关联列：business_source（PROCESS/FORM/DIRECT；存量行 NULL 由读侧按 DIRECT 展示）、
-- process_instance_id / node_id（流程侧回查）、form_record_id（表单侧回查）；
-- 支撑执行列表按来源筛选与流程实例详情反查 Graph 执行编号。
ALTER TABLE sw_agent_graph_execution_request ADD (
    business_source VARCHAR2(32),
    process_instance_id VARCHAR2(64),
    node_id VARCHAR2(100),
    form_record_id NUMBER(19)
);
CREATE INDEX idx_sw_agent_exec_req_biz_src ON sw_agent_graph_execution_request (tenant_id, business_source);
CREATE INDEX idx_sw_agent_exec_req_proc_inst ON sw_agent_graph_execution_request (process_instance_id);

-- 日志等级管理（E2）：独立权限点 system:loglevel:manage，查看与调整共用一点；
-- 超管经 PermissionService 短路默认可见可调，默认不授任何角色，其他角色由菜单管理显式授权。
INSERT INTO sys_menu (id, create_time, update_time, deleted, version, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort)
SELECT 9116, current_timestamp, current_timestamp, 0, 0, 1, 'SystemLogLevel', '日志等级', 0, 1, 'system/log-level', 'system/views/LogLevelList', 'system:loglevel:manage', 'Tickets', 44
 from dual WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 9116)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 1 AND name = 'SystemLogLevel' AND deleted = 0);
