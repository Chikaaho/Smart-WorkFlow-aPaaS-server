-- ===================================================================
-- V99: V012-BUG-009 —— 流程收藏表
--
-- 用户级流程收藏（收藏置顶/常用/最近使用配套）。唯一键 (tenant_id, user_id,
-- process_key)；process_name 为收藏时快照展示名。幂等创建。
-- ===================================================================
CREATE TABLE IF NOT EXISTS sw_bpm_process_favorite (
    id           bigint       NOT NULL,
    create_time  timestamp    NOT NULL DEFAULT current_timestamp,
    update_time  timestamp    NOT NULL DEFAULT current_timestamp,
    deleted      smallint     NOT NULL DEFAULT 0,
    version      bigint       NOT NULL DEFAULT 0,
    tenant_id    bigint       NOT NULL DEFAULT 0,
    user_id      bigint       NOT NULL,
    process_key  varchar(64)  NOT NULL,
    process_name varchar(128),
    CONSTRAINT pk_sw_bpm_process_favorite PRIMARY KEY (id),
    CONSTRAINT uk_sw_bpm_process_favorite UNIQUE (tenant_id, user_id, process_key)
);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_process_favorite_user
    ON sw_bpm_process_favorite (tenant_id, user_id, update_time DESC);
