-- ===================================================================
-- V100: V012-BUG-010 —— 流程主题生成规则与实例主题
--
-- 1) sw_bpm_process_def.theme_rule：流程系统级设置的主题生成规则
--    （占位符 {TIMESTAMP}/{YYYYMMDD}/{YYYYMMDDHHMMSS}/{SEQ}，其余为字面量）。
-- 2) sw_bpm_instance.theme：发起时按规则生成的实例主题（待办/已办/详情展示）。
-- 3) sw_bpm_theme_seq：每流程自增序号计数器（{SEQ} 行锁自增，事务内一致）。
-- 幂等：ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS。
-- ===================================================================
ALTER TABLE sw_bpm_process_def ADD COLUMN IF NOT EXISTS theme_rule varchar(200);
ALTER TABLE sw_bpm_instance ADD COLUMN IF NOT EXISTS theme varchar(200);

CREATE TABLE IF NOT EXISTS sw_bpm_theme_seq (
    process_key varchar(64) NOT NULL,
    next_val    bigint      NOT NULL DEFAULT 1,
    CONSTRAINT pk_sw_bpm_theme_seq PRIMARY KEY (process_key)
);
