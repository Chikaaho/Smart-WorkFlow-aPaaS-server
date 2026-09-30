-- P62 分级执行与统一命令：命令统一身份 / 准入截止 / 效果权威账本（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-tiered-execution-unified-command.md 与 ADR-P62-002。
-- 全部 IF NOT EXISTS 可重复执行；既有行新列保持 NULL，旧语义与旧消费者不受影响。

-- 1) 统一逻辑命令身份与载荷指纹（跨通道复用同一操作身份；允许由既有物理记录映射）
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS logical_command_id varchar(200);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS payload_fingerprint varchar(64);

-- 2) 受理时冻结的形态/完成点/准入截止（改配置只影响新受理对象）
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS tier varchar(30);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS completion_point varchar(200);
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS deadline_at timestamp;
ALTER TABLE sw_bpm_command ADD COLUMN IF NOT EXISTS overdue_at timestamp;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_command_logical ON sw_bpm_command (tenant_id, logical_command_id);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_deadline ON sw_bpm_command (status, deadline_at);

-- 3) 效果权威账本（sw_bpm_command_effect）：与业务效果同事务写入；
--    命令未完成（如提交后崩溃/完成记录未写）时，恢复路径据本表确定结果，不再猜测。
CREATE TABLE IF NOT EXISTS sw_bpm_command_effect (
    command_id          bigint       NOT NULL PRIMARY KEY,
    logical_command_id  varchar(200),
    claim_token         varchar(64),
    result_json         text,
    biz_ref             varchar(200),
    tenant_id           bigint       NOT NULL DEFAULT 0,
    create_time         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_effect_logical ON sw_bpm_command_effect (logical_command_id);

COMMENT ON COLUMN sw_bpm_command.logical_command_id IS '统一逻辑命令身份（跨通道同一操作；物理记录可映射）';
COMMENT ON COLUMN sw_bpm_command.payload_fingerprint IS '载荷指纹（同身份不同载荷必须拒绝）';
COMMENT ON COLUMN sw_bpm_command.tier IS '执行形态/等级（受理时冻结）';
COMMENT ON COLUMN sw_bpm_command.completion_point IS '业务完成点声明（受理时冻结）';
COMMENT ON COLUMN sw_bpm_command.deadline_at IS '准入截止（受理时按 1—300s 冻结）';
COMMENT ON COLUMN sw_bpm_command.overdue_at IS '执行中超过截止的标记时间（不据此判失败）';
COMMENT ON TABLE  sw_bpm_command_effect IS '命令效果权威账本（与业务效果同事务写入，供确定恢复）';
