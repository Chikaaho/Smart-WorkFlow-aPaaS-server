-- V012-BUG-010（bpm 隔离测试链）：主题规则/实例主题/自增计数表
ALTER TABLE sw_bpm_process_def ADD COLUMN IF NOT EXISTS theme_rule varchar(200);
ALTER TABLE sw_bpm_instance ADD COLUMN IF NOT EXISTS theme varchar(200);
CREATE TABLE IF NOT EXISTS sw_bpm_theme_seq (
    process_key varchar(64) NOT NULL,
    next_val    bigint      NOT NULL DEFAULT 1,
    CONSTRAINT pk_sw_bpm_theme_seq PRIMARY KEY (process_key)
);
