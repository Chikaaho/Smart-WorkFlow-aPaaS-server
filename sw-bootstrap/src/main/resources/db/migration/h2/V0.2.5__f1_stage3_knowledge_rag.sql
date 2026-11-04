-- ===================================================================
-- Smart-WorkFlow :: V0.2.5 F1 阶段 3：知识库 / 文档 / 分片 (H2)
-- D022-07：H2 使用可替换的内存向量实现，仅用于测试；embedding 列保留
-- CLOB 占位以维持双方言表结构对齐，实际向量存于 InMemoryKnowledgeVectorStore。
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
    error_message  CLOB,
    chunk_count    INT          NOT NULL DEFAULT 0,
    content        CLOB,
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
    content      CLOB     NOT NULL,
    embedding    CLOB,
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
