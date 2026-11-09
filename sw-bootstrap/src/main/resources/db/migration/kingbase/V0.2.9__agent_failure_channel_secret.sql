-- ===================================================================
-- V0.2.9（0.2.4 示例二/三补充）
-- 1) sw_iot_event_rule.agent_failure_config_json：研判执行失败升级的通用配置
--    （升级流程模板覆盖、升级级别等表单补充、失败告警渠道与负责人），
--    使"失败即升级"可配置出 P0/异常工单并给负责人发站内信+邮件，单次失败只升级一次。
-- 2) sw_notify_channel_config.config_cipher：渠道级凭据密文（与模型 Key 同构口径：
--    AES-256-GCM，base64(IV||ciphertext)，前缀加密主键由运行环境注入）。
--    仅保存密文，接口不回读明文；生产凭据仍可继续走 yml/env（未配置密文时回退）。
-- ===================================================================

alter table sw_iot_event_rule add column if not exists agent_failure_config_json text;
alter table sw_notify_channel_config add column if not exists config_cipher text;
