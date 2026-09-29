-- V74 (I3 G10b): 代理接管审计行与真实办理动作行共存。
-- 原 uk_sw_bpm_approval_task_actor(tenant, task, actor) 把 AUTHORIZE 的 PROXY_JOINED
-- 审计行与代理人后续真实 APPROVE 行判为重复，代理人实际办理时撞唯一键 500。
-- 迁移为 (tenant, task, actor, action) 四列唯一：同动作重复仍被幂等阻断，
-- 不同动作（PROXY_JOINED 审计 vs APPROVE 办理）各自保留一行。
drop index if exists uk_sw_bpm_approval_task_actor;
create unique index uk_sw_bpm_approval_task_actor_action
    on sw_bpm_approval_action (tenant_id, task_id, actor_id, action);
