-- ===================================================================
-- Smart-WorkFlow :: V0.2.5 F1 阶段 3：知识库 / 文档 / 分片（KingbaseES）
-- D022-07：向量存储使用 V0.4.0 的 sw_knowledge_vector 文本表，按租户隔离。
-- 元数据（知识库/文档/分片）由 MyBatis 维护；向量由 PersistentKnowledgeVectorStore 写入/检索。
-- ===================================================================

CREATE TABLE sw_knowledge_base (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    description         VARCHAR(500),
    embedding_model_id  BIGINT       NOT NULL,
    chunk_size          INT          NOT NULL DEFAULT 800,
    chunk_overlap       INT          NOT NULL DEFAULT 100,
    create_time         TIMESTAMP,
    create_by           VARCHAR(64),
    update_time         TIMESTAMP,
    update_by           VARCHAR(64),
    deleted             SMALLINT     NOT NULL DEFAULT 0,
    tenant_id           BIGINT       NOT NULL DEFAULT 0,
    version             BIGINT       NOT NULL DEFAULT 0
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
    id             BIGINT       NOT NULL PRIMARY KEY,
    kb_id          BIGINT       NOT NULL,
    name           VARCHAR(255) NOT NULL,
    file_name      VARCHAR(255),
    file_type      VARCHAR(100),
    file_size      BIGINT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message  TEXT,
    chunk_count    INT          NOT NULL DEFAULT 0,
    content        TEXT,
    create_time    TIMESTAMP,
    create_by      VARCHAR(64),
    update_time    TIMESTAMP,
    update_by      VARCHAR(64),
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    tenant_id      BIGINT       NOT NULL DEFAULT 0,
    version        BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_knowledge_doc_kb
    ON sw_knowledge_document (tenant_id, kb_id, deleted);

COMMENT ON TABLE sw_knowledge_document IS 'F1 阶段 3 知识库文档（原始抽取文本 + 处理状态）';
COMMENT ON COLUMN sw_knowledge_document.status IS '处理状态：PENDING/PROCESSING/READY/FAILED';
COMMENT ON COLUMN sw_knowledge_document.chunk_count IS '成功向量化的分片数';

CREATE TABLE sw_knowledge_chunk (
    id           BIGINT   NOT NULL PRIMARY KEY,
    kb_id        BIGINT   NOT NULL,
    document_id  BIGINT   NOT NULL,
    chunk_index  INT      NOT NULL,
    content      TEXT     NOT NULL,
    create_time  TIMESTAMP,
    create_by    VARCHAR(64),
    update_time  TIMESTAMP,
    update_by    VARCHAR(64),
    deleted      SMALLINT NOT NULL DEFAULT 0,
    tenant_id    BIGINT   NOT NULL DEFAULT 0,
    version      BIGINT   NOT NULL DEFAULT 0
);

CREATE INDEX idx_sw_knowledge_chunk_kb
    ON sw_knowledge_chunk (tenant_id, kb_id, document_id, deleted);

COMMENT ON TABLE sw_knowledge_chunk IS 'F1 阶段 3 文档分片（tenant_id 显式过滤）';

-- KingbaseES 由非 pgvector 的 sw_knowledge_vector 文本表持久化向量。

