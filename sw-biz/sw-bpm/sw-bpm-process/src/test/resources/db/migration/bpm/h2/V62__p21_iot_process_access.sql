-- ===================================================================
-- V62: P21 IoT 接入 — 流程模板 IoT 接入开关（bpm 模块链，H2）
-- 与主库 V60 同构幂等：bpm 隔离链（如 p4overlap 测试库）亦能获得该列。
-- ===================================================================

alter table sw_bpm_process_def add column if not exists iot_access_enabled boolean not null default false;
