-- ===================================================================
-- Smart-WorkFlow :: V0.2.3 F1 阶段 2：graph 发布快照 + 图级执行超时 + 节点工具轨迹
-- D022-06：发布生成不可变版本快照，执行/调试绑定快照版本；版本列表可查。
-- 同时为阶段 2 的图级超时与 LLM 节点工具循环预留列。
-- ===================================================================

CREATE TABLE sw_agent_graph_snapshot (
    id            NUMBER(19)       NOT NULL PRIMARY KEY,
    graph_def_id  NUMBER(19)       NOT NULL,
    def_version   NUMBER(10)          NOT NULL,
    graph_key     VARCHAR2(100 CHAR),
    name          VARCHAR2(200 CHAR),
    graph_json    CLOB         NOT NULL,
    create_time   TIMESTAMP,
    create_by     VARCHAR2(64 CHAR),
    update_time   TIMESTAMP,
    update_by     VARCHAR2(64 CHAR),
    deleted       NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id     NUMBER(19)       DEFAULT 0 NOT NULL,
    version       NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE UNIQUE INDEX uk_sw_agent_graph_snapshot_ver
    ON sw_agent_graph_snapshot (tenant_id, graph_def_id, def_version);
CREATE INDEX idx_sw_agent_graph_snapshot_def
    ON sw_agent_graph_snapshot (graph_def_id, deleted);

COMMENT ON TABLE  sw_agent_graph_snapshot IS 'F1 阶段 2 图发布快照（不可变，执行/调试按版本绑定）';
COMMENT ON COLUMN sw_agent_graph_snapshot.graph_def_id IS '图定义 id（sw_agent_graph_def）';
COMMENT ON COLUMN sw_agent_graph_snapshot.def_version IS '发布版本号（与 sw_agent_graph_def.def_version 对齐）';
COMMENT ON COLUMN sw_agent_graph_snapshot.graph_json IS '该版本的完整 ProcessGraph CLOB 快照';

-- 回填：已发布定义按当前 def_version 生成一条快照。
-- 升级前更早版本的内容未被保存，无法重建；从本次升级起每次发布都会新增快照。
INSERT INTO sw_agent_graph_snapshot (id, graph_def_id, def_version, graph_key, name, graph_json,
                                     create_time, create_by, update_time, update_by,
                                     deleted, tenant_id, version)
SELECT -id, id, def_version, graph_key, name, graph_json,
       update_time, update_by, update_time, update_by, 0, tenant_id, 0
FROM sw_agent_graph_def
WHERE status = 'PUBLISHED' AND deleted = 0 AND graph_json IS NOT NULL;

-- 图级执行超时（秒），默认 300 秒；执行服务在超时后把执行记录置为 FAILED 并返回超时错误
ALTER TABLE sw_agent_graph_def add execution_timeout_seconds NUMBER(10) DEFAULT 300 NOT NULL;
COMMENT ON COLUMN sw_agent_graph_def.execution_timeout_seconds IS '图级执行超时（秒，默认 300）';

-- LLM 节点工具调用轨迹（CLOB 数组：tool/arguments/result）
ALTER TABLE sw_agent_graph_execution_node add tool_calls CLOB;
COMMENT ON COLUMN sw_agent_graph_execution_node.tool_calls IS 'LLM 节点工具调用轨迹（CLOB 数组，非工具节点为 NULL）';

-- 工具白名单 enabled 与实体/查询语义统一为 NUMBER(1)（原 NUMBER(5) 在 PG 上无法与
-- NUMBER(1) 参数比较/写入，阶段 2 LLM 节点挂工具依赖该列）

alter table sw_agent_tool_internal modify (enabled NUMBER(1));
alter table sw_agent_tool_internal modify (enabled default 1);


alter table sw_agent_tool_external modify (enabled NUMBER(1));
alter table sw_agent_tool_external modify (enabled default 1);
