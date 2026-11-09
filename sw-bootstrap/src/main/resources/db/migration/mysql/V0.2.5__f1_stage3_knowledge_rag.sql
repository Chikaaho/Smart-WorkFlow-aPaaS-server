-- ===================================================================
-- Smart-WorkFlow :: V0.2.5 F1 阶段 3：知识库 / 文档 / 分片（pgvector）
-- D022-07：向量存储使用 PostgreSQL pgvector，按租户隔离。
-- 元数据（知识库/文档/分片）由 MyBatis 维护；向量列由 JdbcTemplate 写入/检索。
-- pgvector 扩展为运行前置条件：扩展可用时创建 vector 列；不可用时迁移仍可完成，
-- 但应用启动期 PgKnowledgeVectorStore 会 fail-fast（不静默降级为内存/JSON）。
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

CREATE TABLE sw_knowledge_document (
    id             BIGINT       NOT NULL PRIMARY KEY,
    kb_id          BIGINT       NOT NULL,
    name           VARCHAR(255) NOT NULL,
    file_name      VARCHAR(255),
    file_type      VARCHAR(100),
    file_size      BIGINT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message  longtext,
    chunk_count    INT          NOT NULL DEFAULT 0,
    content        longtext,
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

CREATE TABLE sw_knowledge_chunk (
    id           BIGINT   NOT NULL PRIMARY KEY,
    kb_id        BIGINT   NOT NULL,
    document_id  BIGINT   NOT NULL,
    chunk_index  INT      NOT NULL,
    content      longtext     NOT NULL,
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

-- pgvector 可用时补 vector 列（维度由所选 Embedding 模型决定，不建固定维度索引）。
-- 扩展不可用（如 CI 的 zonky embedded PG）时迁移仍可完成；应用启用 pgvector 存储
-- 会在启动期明确失败，要求安装扩展，不做静默降级。


