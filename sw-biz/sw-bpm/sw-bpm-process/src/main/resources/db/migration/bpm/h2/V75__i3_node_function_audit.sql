-- V75 (I3 G13a/G13b): 节点函数调用审计（§4.9 运行必须完整审计）。
-- 每次参与人解析/结果处理调用（成功或失败）各写一行：函数身份、调用位置、
-- 结果或错误码、幂等键与耗时；生产任意脚本入口仍不存在。
create table sw_bpm_node_function_audit (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    func_key varchar(128) not null,
    func_version int not null,
    func_type varchar(32) not null,
    process_instance_id varchar(64),
    node_key varchar(128),
    idempotent_key varchar(128),
    actor_id bigint,
    outcome varchar(32) not null,
    error_code int,
    summary varchar(2000),
    duration_ms bigint
);
create index idx_sw_bpm_fn_audit_instance on sw_bpm_node_function_audit (process_instance_id);

-- 内建结果函数注册：func_result_echo v1（白名单变量写回证据链）
INSERT INTO sw_bpm_node_function (id, func_key, func_version, func_type, impl_bean, config, allowed_nodes, timeout_ms, failure_strategy, enabled) SELECT 9003,'func_result_echo',1,'HANDLE_RESULT','func_result_echo','{"input":"nodeResult","output":"summaryResultVariables"}','[]',5000,'FRAMEWORK',1 WHERE NOT EXISTS (SELECT 1 FROM sw_bpm_node_function WHERE id = 9003);
