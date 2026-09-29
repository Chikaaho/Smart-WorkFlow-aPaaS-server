-- ===================================================================
-- V104: sso-admin-config A4 —— 在途授权绑定配置指纹
--
-- sys_sso_auth_state 新增 config_digest：授权发起时刻的 Provider 配置
-- 指纹（SHA-256，材料=provider|appId|解密secret|企业标识|启停）。回调与
-- 未兑换票据兑换时与当前配置比对：appId、secret、身份模式（企业标识有
-- 无）、企业标识、启停任一变化 → 在途授权安全失败并可重新发起，不串用
-- 新旧配置（方向 §三）。
-- 约束：列可空，历史行 NULL 在回调侧一律 fail closed 拒绝；state 本身
-- 一次性/限时语义不变。
-- ===================================================================

alter table sys_sso_auth_state add column config_digest varchar(128);
