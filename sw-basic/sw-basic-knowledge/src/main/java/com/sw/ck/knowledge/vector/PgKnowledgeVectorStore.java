package com.sw.ck.knowledge.vector;

import com.sw.ck.common.security.LoginContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL pgvector 实现（D022-07）：分片向量存于 sw_knowledge_chunk.embedding。
 * 所有 SQL 显式带 tenant_id；检索按 cosine 距离升序取前 topK。
 */
@Component
@ConditionalOnProperty(prefix = "sw.knowledge", name = "vector-store", havingValue = "pgvector", matchIfMissing = true)
public class PgKnowledgeVectorStore implements KnowledgeVectorStore {

    private final JdbcTemplate jdbcTemplate;
    private final LoginContextProvider loginContextProvider;

    private volatile boolean vectorColumnChecked;

    public PgKnowledgeVectorStore(JdbcTemplate jdbcTemplate, LoginContextProvider loginContextProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.loginContextProvider = loginContextProvider;
    }

    /** 首次向量写入/检索时校验 pgvector 列；缺失即明确失败，不静默降级。 */
    private void ensureVectorColumn() {
        if (vectorColumnChecked) {
            return;
        }
        Integer columns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'sw_knowledge_chunk' AND column_name = 'embedding'",
                Integer.class);
        if (columns == null || columns == 0) {
            throw new IllegalStateException(
                    "当前 PostgreSQL 未安装 pgvector 扩展（sw_knowledge_chunk.embedding 缺失）；"
                            + "请先安装 pgvector 后再使用知识库");
        }
        vectorColumnChecked = true;
    }

    @Override
    public void store(Long chunkId, float[] embedding) {
        ensureVectorColumn();
        Long tenantId = requireTenantId();
        jdbcTemplate.update(
                "UPDATE sw_knowledge_chunk SET embedding = CAST(? AS vector), update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND tenant_id = ? AND deleted = 0",
                toVectorLiteral(embedding), chunkId, tenantId);
    }

    @Override
    public void deleteByDocument(Long kbId, Long documentId) {
        Long tenantId = requireTenantId();
        jdbcTemplate.update("DELETE FROM sw_knowledge_chunk WHERE tenant_id = ? AND kb_id = ? AND document_id = ?",
                tenantId, kbId, documentId);
    }

    @Override
    public void deleteByKnowledgeBase(Long kbId) {
        Long tenantId = requireTenantId();
        jdbcTemplate.update("DELETE FROM sw_knowledge_chunk WHERE tenant_id = ? AND kb_id = ?", tenantId, kbId);
    }

    @Override
    public List<KnowledgeVectorHit> search(Long kbId, float[] query, int topK, double threshold) {
        ensureVectorColumn();
        Long tenantId = requireTenantId();
        String vector = toVectorLiteral(query);
        List<KnowledgeVectorHit> hits = jdbcTemplate.query(
                "SELECT c.id, c.document_id, d.name, c.chunk_index, c.content, "
                        + "1 - (c.embedding <=> CAST(? AS vector)) AS similarity "
                        + "FROM sw_knowledge_chunk c "
                        + "JOIN sw_knowledge_document d ON d.id = c.document_id AND d.deleted = 0 "
                        + "WHERE c.tenant_id = ? AND c.kb_id = ? AND c.deleted = 0 AND c.embedding IS NOT NULL "
                        + "ORDER BY c.embedding <=> CAST(? AS vector) LIMIT ?",
                (rs, rowNum) -> new KnowledgeVectorHit(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getString("name"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("similarity")),
                vector, tenantId, kbId, vector, topK);
        List<KnowledgeVectorHit> filtered = new ArrayList<>();
        for (KnowledgeVectorHit hit : hits) {
            if (hit.similarity() >= threshold) {
                filtered.add(hit);
            }
        }
        return filtered;
    }

    private Long requireTenantId() {
        Long tenantId = loginContextProvider.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("租户上下文缺失，无法访问知识库向量");
        }
        return tenantId;
    }

    /** pgvector 文本字面量：[0.1,0.2]；非有限值按 0 处理。 */
    static String toVectorLiteral(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            float v = values[i];
            sb.append(Float.isFinite(v) ? Float.toString(v) : "0");
        }
        return sb.append(']').toString();
    }
}
