-- ===================================================================
-- V60: P21 IoT 接入 — 流程模板 IoT 接入开关（PostgreSQL）
--
-- 变更：sw_bpm_process_def 新增 iot_access_enabled（默认 false）。
-- 仅「已发布 + iot_access_enabled」的流程模板允许被 IoT 事件规则/受控脚本
-- 发起流程；未开启的模板在规则配置与触发时均被明确拒绝。
-- 幂等：IF NOT EXISTS。
-- ===================================================================

alter table sw_bpm_process_def add column if not exists iot_access_enabled boolean not null default false;
