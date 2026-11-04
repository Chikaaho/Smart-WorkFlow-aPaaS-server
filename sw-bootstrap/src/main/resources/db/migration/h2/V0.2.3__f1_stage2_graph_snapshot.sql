-- ===================================================================
-- Smart-WorkFlow :: V0.2.3 F1 阶段 2：graph 发布快照 + 图级执行超时 + 节点工具轨迹 (H2)
-- 与 PostgreSQL 版同构：graph_json/tool_calls 用 CLOB（参照 V19 惯例）。
-- ===================================================================

CREATE TABLE sw_agent_graph_snapshot (
    id            BIGINT       NOT NULL PRIMARY KEY,
    graph_def_id  BIGINT       NOT NULL,
    def_version   INT          NOT NULL,
    graph_key     VARCHAR(100),
    name          VARCHAR(200),
    graph_json    CLOB         NOT NULL,
    create_time   TIMESTAMP,
    create_by     VARCHAR(64),
    update_time   TIMESTAMP,
    update_by     VARCHAR(64),
    deleted       SMALLINT     NOT NULL DEFAULT 0,
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_sw_agent_graph_snapshot_ver
    ON sw_agent_graph_snapshot (tenant_id, graph_def_id, def_version);
CREATE INDEX idx_sw_agent_graph_snapshot_def
    ON sw_agent_graph_snapshot (graph_def_id, deleted);

COMMENT ON TABLE  sw_agent_graph_snapshot IS 'F1 阶段 2 图发布快照（不可变，执行/调试按版本绑定）';
COMMENT ON COLUMN sw_agent_graph_snapshot.graph_def_id IS '图定义 id（sw_agent_graph_def）';
COMMENT ON COLUMN sw_agent_graph_snapshot.def_version IS '发布版本号（与 sw_agent_graph_def.def_version 对齐）';
COMMENT ON COLUMN sw_agent_graph_snapshot.graph_json IS '该版本的完整 ProcessGraph JSON 快照';

-- 回填：已发布定义按当前 def_version 生成一条快照（升级前更早版本无法重建）
INSERT INTO sw_agent_graph_snapshot (id, graph_def_id, def_version, graph_key, name, graph_json,
                                     create_time, create_by, update_time, update_by,
                                     deleted, tenant_id, version)
SELECT -id, id, def_version, graph_key, name, graph_json,
       update_time, update_by, update_time, update_by, 0, tenant_id, 0
FROM sw_agent_graph_def
WHERE status = 'PUBLISHED' AND deleted = 0 AND graph_json IS NOT NULL;

ALTER TABLE sw_agent_graph_def ADD COLUMN execution_timeout_seconds INT NOT NULL DEFAULT 300;
COMMENT ON COLUMN sw_agent_graph_def.execution_timeout_seconds IS '图级执行超时（秒，默认 300）';

ALTER TABLE sw_agent_graph_execution_node ADD COLUMN tool_calls CLOB;
COMMENT ON COLUMN sw_agent_graph_execution_node.tool_calls IS 'LLM 节点工具调用轨迹（JSON 数组，非工具节点为 NULL）';

-- 工具白名单 enabled 统一为 BOOLEAN（H2 用 新列 + 数据转换 + 改名）
ALTER TABLE sw_agent_tool_internal ADD COLUMN enabled_bool BOOLEAN DEFAULT TRUE;
UPDATE sw_agent_tool_internal SET enabled_bool = (enabled <> 0);
ALTER TABLE sw_agent_tool_internal DROP COLUMN enabled;
ALTER TABLE sw_agent_tool_internal ALTER COLUMN enabled_bool RENAME TO enabled;
ALTER TABLE sw_agent_tool_internal ALTER COLUMN enabled SET NOT NULL;

ALTER TABLE sw_agent_tool_external ADD COLUMN enabled_bool BOOLEAN DEFAULT TRUE;
UPDATE sw_agent_tool_external SET enabled_bool = (enabled <> 0);
ALTER TABLE sw_agent_tool_external DROP COLUMN enabled;
ALTER TABLE sw_agent_tool_external ALTER COLUMN enabled_bool RENAME TO enabled;
ALTER TABLE sw_agent_tool_external ALTER COLUMN enabled SET NOT NULL;
