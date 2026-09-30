-- P62 分级执行与统一命令：后台批量（S3）——批次受理 / 稳定项键 / 逐项持久结果（追加、非破坏）。
-- 依据：product/p62-lowcode-transaction-bpm-tiering/ready/direction-p62-tiered-execution-unified-command.md（U02/U07）。
-- 全部 IF NOT EXISTS 可重复执行；批次重放返回原批次，同项同键异载荷拒绝，重复消费不重做已成功项。

-- 1) 批次受理（同租户批次键唯一：重放返回原批次）
CREATE TABLE IF NOT EXISTS sw_bpm_command_batch (
    id              bigint        NOT NULL PRIMARY KEY,
    create_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       bigint,
    update_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       bigint,
    deleted         smallint      NOT NULL DEFAULT 0,
    tenant_id       bigint        NOT NULL DEFAULT 0,
    version         bigint        NOT NULL DEFAULT 0,
    batch_key       varchar(128)  NOT NULL,
    action_id       varchar(64)   NOT NULL,
    action_version  integer,
    status          varchar(20)   NOT NULL DEFAULT 'PENDING',
    total_count     integer       NOT NULL DEFAULT 0,
    succeeded_count integer       NOT NULL DEFAULT 0,
    failed_count    integer       NOT NULL DEFAULT 0,
    command_id      bigint,
    initiator_id    bigint
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_command_batch_key ON sw_bpm_command_batch (tenant_id, batch_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_batch_cmd ON sw_bpm_command_batch (command_id);

-- 2) 批次项（批次内稳定项键唯一；逐项独立事务与持久结果，可独立追踪与恢复）
CREATE TABLE IF NOT EXISTS sw_bpm_command_batch_item (
    id              bigint        NOT NULL PRIMARY KEY,
    create_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       bigint,
    update_time     timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       bigint,
    deleted         smallint      NOT NULL DEFAULT 0,
    tenant_id       bigint        NOT NULL DEFAULT 0,
    version         bigint        NOT NULL DEFAULT 0,
    batch_id        bigint        NOT NULL,
    item_key        varchar(128)  NOT NULL,
    record_id       varchar(64),
    quantity        varchar(40),
    status          varchar(20)   NOT NULL DEFAULT 'PENDING',
    invocation_id   varchar(64),
    error_code      integer,
    error_msg       varchar(500),
    attempt_count   integer       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_command_batch_item_key ON sw_bpm_command_batch_item (batch_id, item_key);
CREATE INDEX IF NOT EXISTS idx_sw_bpm_command_batch_item_status ON sw_bpm_command_batch_item (batch_id, status);

