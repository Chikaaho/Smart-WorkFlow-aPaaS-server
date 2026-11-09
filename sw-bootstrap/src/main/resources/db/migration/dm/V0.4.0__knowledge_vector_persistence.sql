-- 0.4.0 非 pgvector 数据库的持久化向量表；PostgreSQL 保留 pgvector 链路，本表供其他方言使用。
create table sw_knowledge_vector (
    id              NUMBER(19)       NOT NULL PRIMARY KEY,
    tenant_id       NUMBER(19)       DEFAULT 0 NOT NULL,
    kb_id           NUMBER(19)       NOT NULL,
    document_id     NUMBER(19)       NOT NULL,
    chunk_id        NUMBER(19)       NOT NULL,
    embedding_text  CLOB         NOT NULL,
    create_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    update_time     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);
create UNIQUE index uk_sw_knowledge_vector_chunk
    ON sw_knowledge_vector (tenant_id, chunk_id);
create index idx_sw_knowledge_vector_kb
    ON sw_knowledge_vector (tenant_id, kb_id, document_id);
COMMENT ON TABLE sw_knowledge_vector IS '非 pgvector 数据库的持久化向量（文本数组）';
