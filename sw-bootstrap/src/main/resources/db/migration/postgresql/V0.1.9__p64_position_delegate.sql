-- V0.1.9 (P64 阶段Ⅱ 人员与父子协作): 后台源岗位→受托岗位委托关系表（组织域通用配置）。
-- 追加新表，不 ALTER 既有表；未配置委托关系时岗位解析沿 P63 原义（源岗位有效人员）。
-- scope_type: ORG（显式组织默认范围）/ DEPT（精确部门范围，优先于 ORG）；dept_id 仅 DEPT 有效。
-- status: ENABLED / DISABLED（启停、审计；每个源岗位/适用范围最多一条有效关系，
--         同优先级重叠由服务层配置校验拒绝——自委托/循环/跨租户/越权映射同样在配置与生效时拒绝）。
-- 回退：新代码回滚后旧代码不读写本表，配置数据保留，不通过破坏性 DDL 删除事实。
create table sys_post_delegate (
    id bigint not null primary key,
    create_time timestamp not null default current_timestamp,
    create_by bigint,
    update_time timestamp not null default current_timestamp,
    update_by bigint,
    deleted int not null default 0,
    tenant_id bigint not null default 0,
    version bigint not null default 0,
    source_post_id bigint not null,
    target_post_id bigint not null,
    scope_type varchar(16) not null,
    dept_id bigint,
    status varchar(16) not null,
    remark varchar(500)
);
create index idx_sys_post_delegate_source on sys_post_delegate (tenant_id, source_post_id, status);
create index idx_sys_post_delegate_dept on sys_post_delegate (tenant_id, dept_id);
