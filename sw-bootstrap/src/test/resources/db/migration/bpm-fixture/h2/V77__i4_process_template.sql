-- V77 (I4 §3.2): 流程模板中心。
-- 模板是创建流程定义的受控来源：不直接改写已发布定义、运行实例或历史；
-- 派生定义通过 sw_bpm_process_def.source_template_id/version 保持可追溯。
-- 分级授权：scope_type=GLOBAL 全局可见；DEPT 仅 scope_dept_id 同部门可见/可复制。
create table sw_bpm_process_template (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    name varchar(200) not null,
    category varchar(100),
    description varchar(500),
    form_key varchar(200) not null,
    graph_json text,
    template_version int not null default 1,
    status varchar(20) not null default 'ENABLED',
    scope_type varchar(20) not null default 'GLOBAL',
    scope_dept_id bigint,
    source_def_id bigint,
    source_def_version int
);
create index idx_sw_bpm_tpl_category on sw_bpm_process_template (category);
create index idx_sw_bpm_tpl_scope on sw_bpm_process_template (scope_type, scope_dept_id);

alter table sw_bpm_process_def add column source_template_id bigint;
alter table sw_bpm_process_def add column source_template_version int;
