-- V012-BUG-009 修正：收藏表补 BaseEntity 审计列（create_by/update_by）
ALTER TABLE sw_bpm_process_favorite ADD COLUMN IF NOT EXISTS create_by bigint;
ALTER TABLE sw_bpm_process_favorite ADD COLUMN IF NOT EXISTS update_by bigint;
