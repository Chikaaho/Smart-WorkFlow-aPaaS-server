-- ===================================================================
-- V60: P21 IoT 接入 — 流程模板 IoT 接入开关（H2）
-- 变更同 PostgreSQL 版本；幂等：IF NOT EXISTS。
-- ===================================================================

alter table sw_bpm_process_def add column if not exists iot_access_enabled boolean not null default false;
