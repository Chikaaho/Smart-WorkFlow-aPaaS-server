-- 0.4.1 非 pgvector 数据库的持久化向量表；PostgreSQL 保留 pgvector 链路，本表供其他方言使用。
CREATE TABLE IF NOT EXISTS sw_knowledge_vector (
    id              BIGINT       NOT NULL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    kb_id           BIGINT       NOT NULL,
    document_id     BIGINT       NOT NULL,
    chunk_id        BIGINT       NOT NULL,
    embedding_text  CLOB         NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_knowledge_vector_chunk
    ON sw_knowledge_vector (tenant_id, chunk_id);
CREATE INDEX IF NOT EXISTS idx_sw_knowledge_vector_kb
    ON sw_knowledge_vector (tenant_id, kb_id, document_id);
COMMENT ON TABLE sw_knowledge_vector IS '非 pgvector 数据库的持久化向量（文本数组）';
