-- ===================================================================
-- V63: P21 A6 — 流程模板设备动作配置（PostgreSQL/H2 通用幂等）
-- iot_device_action_json: {"enabled","deviceSource":"FIXED|FORM_FIELD|VARIABLE",
--   "deviceId","deviceField","variableName","commandKey","paramField","failurePolicy"}
-- ===================================================================
alter table sw_bpm_process_def add column if not exists iot_device_action_json text;
