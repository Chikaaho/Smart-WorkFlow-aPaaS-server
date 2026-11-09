package com.sw.ck.knowledge.vector;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.sw.ck.common.security.LoginContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 非 pgvector 数据库的持久化向量实现。
 * <p>
 * 向量以文本数组（{@code [0.1,0.2,...]}）存于 {@code sw_knowledge_vector}，应用启动后
 * 按需从数据库载入，进程内计算 cosine 相似度；检索性能低于 pgvector，但重启不丢数据。
 * 所有 SQL 显式带 tenant_id，删除与 PostgreSQL 实现保持同一口径。
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.knowledge", name = "vector-store", havingValue = "database")
public class PersistentKnowledgeVectorStore implements KnowledgeVectorStore {

    private final JdbcTemplate jdbcTemplate;
    private final LoginContextProvider loginContextProvider;

    public PersistentKnowledgeVectorStore(JdbcTemplate jdbcTemplate,
                                          LoginContextProvider loginContextProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.loginContextProvider = loginContextProvider;
    }

    @Override
    public void store(Long chunkId, float[] embedding) {
        Long tenantId = requireTenantId();
        String text = toText(embedding);
        int updated = jdbcTemplate.update(
                "UPDATE sw_knowledge_vector SET embedding_text = ?, update_time = CURRENT_TIMESTAMP "
                        + "WHERE tenant_id = ? AND chunk_id = ?", text, tenantId, chunkId);
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO sw_knowledge_vector "
                                + "(id, tenant_id, kb_id, document_id, chunk_id, embedding_text) "
                                + "SELECT ?, ?, kb_id, document_id, id, ? FROM sw_knowledge_chunk "
                                + "WHERE id = ? AND tenant_id = ?",
                        IdWorker.getId(), tenantId, text, chunkId, tenantId);
            } catch (DuplicateKeyException e) {
                jdbcTemplate.update(
                        "UPDATE sw_knowledge_vector SET embedding_text = ?, update_time = CURRENT_TIMESTAMP "
                                + "WHERE tenant_id = ? AND chunk_id = ?", text, tenantId, chunkId);
            }
        }
    }

    @Override
    public void deleteByDocument(Long kbId, Long documentId) {
        Long tenantId = requireTenantId();
        jdbcTemplate.update("DELETE FROM sw_knowledge_vector WHERE tenant_id = ? AND kb_id = ? AND document_id = ?",
                tenantId, kbId, documentId);
        jdbcTemplate.update("DELETE FROM sw_knowledge_chunk WHERE tenant_id = ? AND kb_id = ? AND document_id = ?",
                tenantId, kbId, documentId);
    }

    @Override
    public void deleteByKnowledgeBase(Long kbId) {
        Long tenantId = requireTenantId();
        jdbcTemplate.update("DELETE FROM sw_knowledge_vector WHERE tenant_id = ? AND kb_id = ?", tenantId, kbId);
        jdbcTemplate.update("DELETE FROM sw_knowledge_chunk WHERE tenant_id = ? AND kb_id = ?", tenantId, kbId);
    }

    @Override
    public List<KnowledgeVectorHit> search(Long kbId, float[] query, int topK, double threshold) {
        Long tenantId = requireTenantId();
        List<KnowledgeVectorHit> hits = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT v.chunk_id, v.embedding_text, c.document_id, d.name, c.chunk_index, c.content "
                        + "FROM sw_knowledge_vector v "
                        + "JOIN sw_knowledge_chunk c ON c.id = v.chunk_id "
                        + "AND c.tenant_id = v.tenant_id AND c.deleted = 0 "
                        + "LEFT JOIN sw_knowledge_document d ON d.id = c.document_id "
                        + "AND d.tenant_id = c.tenant_id AND d.deleted = 0 "
                        + "WHERE v.tenant_id = ? AND v.kb_id = ?",
                rs -> {
                    float[] vector = parse(readText(rs, "embedding_text"));
                    if (vector == null || vector.length != query.length) {
                        return;
                    }
                    double similarity = InMemoryKnowledgeVectorStore.cosine(query, vector);
                    if (similarity >= threshold) {
                        hits.add(new KnowledgeVectorHit(
                                rs.getLong("chunk_id"), rs.getLong("document_id"),
                                rs.getString("name"), rs.getInt("chunk_index"),
                                rs.getString("content"), similarity));
                    }
                },
                tenantId, kbId);
        hits.sort(Comparator.comparingDouble(KnowledgeVectorHit::similarity).reversed());
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    private Long requireTenantId() {
        Long tenantId = loginContextProvider.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("租户上下文缺失，无法访问知识库向量");
        }
        return tenantId;
    }

    private static String readText(ResultSet rs, String column) throws SQLException {
        int type = rs.getMetaData().getColumnType(rs.findColumn(column));
        if (type == Types.CLOB || type == Types.NCLOB) {
            Clob clob = rs.getClob(column);
            return clob == null ? null : clob.getSubString(1, (int) clob.length());
        }
        return rs.getString(column);
    }

    /** 向量文本：[0.1,0.2]；非法/空文本返回 null。 */
    static float[] parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String body = text.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return null;
        }
        String[] parts = body.split(",");
        float[] values = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                values[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return values;
    }

    static String toText(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
