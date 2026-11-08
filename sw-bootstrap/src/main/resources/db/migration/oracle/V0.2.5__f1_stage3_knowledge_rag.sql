-- ===================================================================
-- Smart-WorkFlow :: V0.2.5 F1 阶段 3：知识库 / 文档 / 分片（pgvector）
-- D022-07：向量存储使用 PostgreSQL pgvector，按租户隔离。
-- 元数据（知识库/文档/分片）由 MyBatis 维护；向量列由 JdbcTemplate 写入/检索。
-- pgvector 扩展为运行前置条件：扩展可用时创建 vector 列；不可用时迁移仍可完成，
-- 但应用启动期 PgKnowledgeVectorStore 会 fail-fast（不静默降级为内存/CLOB）。
-- ===================================================================

CREATE TABLE sw_knowledge_base (
    id                  NUMBER(19)       NOT NULL PRIMARY KEY,
    name                VARCHAR2(200 CHAR) NOT NULL,
    description         VARCHAR2(500 CHAR),
    embedding_model_id  NUMBER(19)       NOT NULL,
    chunk_size          NUMBER(10)          DEFAULT 800 NOT NULL,
    chunk_overlap       NUMBER(10)          DEFAULT 100 NOT NULL,
    create_time         TIMESTAMP,
    create_by           VARCHAR2(64 CHAR),
    update_time         TIMESTAMP,
    update_by           VARCHAR2(64 CHAR),
    deleted             NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id           NUMBER(19)       DEFAULT 0 NOT NULL,
    version             NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE UNIQUE INDEX uk_sw_knowledge_base_name
    ON sw_knowledge_base (tenant_id, name);
CREATE INDEX idx_sw_knowledge_base_tenant
    ON sw_knowledge_base (tenant_id, deleted);

COMMENT ON TABLE sw_knowledge_base IS 'F1 阶段 3 知识库（一个知识库绑定一个 EMBEDDING 模型）';
COMMENT ON COLUMN sw_knowledge_base.embedding_model_id IS '向量化模型 id（sw_agent_model，能力含 EMBEDDING）';
COMMENT ON COLUMN sw_knowledge_base.chunk_size IS '切分块大小（字符数）';
COMMENT ON COLUMN sw_knowledge_base.chunk_overlap IS '切分重叠字符数';

CREATE TABLE sw_knowledge_document (
    id             NUMBER(19)       NOT NULL PRIMARY KEY,
    kb_id          NUMBER(19)       NOT NULL,
    name           VARCHAR2(255 CHAR) NOT NULL,
    file_name      VARCHAR2(255 CHAR),
    file_type      VARCHAR2(100 CHAR),
    file_size      NUMBER(19),
    status         VARCHAR2(20 CHAR)  DEFAULT 'PENDING' NOT NULL,
    error_message  CLOB,
    chunk_count    NUMBER(10)          DEFAULT 0 NOT NULL,
    content        CLOB,
    create_time    TIMESTAMP,
    create_by      VARCHAR2(64 CHAR),
    update_time    TIMESTAMP,
    update_by      VARCHAR2(64 CHAR),
    deleted        NUMBER(5)     DEFAULT 0 NOT NULL,
    tenant_id      NUMBER(19)       DEFAULT 0 NOT NULL,
    version        NUMBER(19)       DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_knowledge_doc_kb
    ON sw_knowledge_document (tenant_id, kb_id, deleted);

COMMENT ON TABLE sw_knowledge_document IS 'F1 阶段 3 知识库文档（原始抽取文本 + 处理状态）';
COMMENT ON COLUMN sw_knowledge_document.status IS '处理状态：PENDING/PROCESSING/READY/FAILED';
COMMENT ON COLUMN sw_knowledge_document.chunk_count IS '成功向量化的分片数';

CREATE TABLE sw_knowledge_chunk (
    id           NUMBER(19)   NOT NULL PRIMARY KEY,
    kb_id        NUMBER(19)   NOT NULL,
    document_id  NUMBER(19)   NOT NULL,
    chunk_index  NUMBER(10)      NOT NULL,
    content      CLOB     NOT NULL,
    create_time  TIMESTAMP,
    create_by    VARCHAR2(64 CHAR),
    update_time  TIMESTAMP,
    update_by    VARCHAR2(64 CHAR),
    deleted      NUMBER(5) DEFAULT 0 NOT NULL,
    tenant_id    NUMBER(19)   DEFAULT 0 NOT NULL,
    version      NUMBER(19)   DEFAULT 0 NOT NULL
);

CREATE INDEX idx_sw_knowledge_chunk_kb
    ON sw_knowledge_chunk (tenant_id, kb_id, document_id, deleted);

COMMENT ON TABLE sw_knowledge_chunk IS 'F1 阶段 3 文档分片与 pgvector 向量（tenant_id 显式过滤）';

-- pgvector 可用时补 vector 列（维度由所选 Embedding 模型决定，不建固定维度索引）。
-- 扩展不可用（如 CI 的 zonky embedded PG）时迁移仍可完成；应用启用 pgvector 存储
-- 会在启动期明确失败，要求安装扩展，不做静默降级。


