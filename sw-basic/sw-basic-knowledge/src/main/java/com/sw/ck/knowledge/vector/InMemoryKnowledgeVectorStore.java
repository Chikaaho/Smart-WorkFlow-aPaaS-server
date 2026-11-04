package com.sw.ck.knowledge.vector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.knowledge.entity.KnowledgeChunk;
import com.sw.ck.knowledge.entity.KnowledgeDocument;
import com.sw.ck.knowledge.mapper.KnowledgeChunkMapper;
import com.sw.ck.knowledge.mapper.KnowledgeDocumentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2/测试用内存向量实现（D022-07）：向量保存在进程内，分片/文档元数据仍走 MyBatis，
 * 以保证租户隔离与状态可见性逻辑与生产一致。
 */
@Component
@ConditionalOnProperty(prefix = "sw.knowledge", name = "vector-store", havingValue = "memory")
public class InMemoryKnowledgeVectorStore implements KnowledgeVectorStore {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    /** chunkId → 向量 */
    private final Map<Long, float[]> vectors = new ConcurrentHashMap<>();

    public InMemoryKnowledgeVectorStore(KnowledgeChunkMapper chunkMapper,
                                        KnowledgeDocumentMapper documentMapper) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
    }

    @Override
    public void store(Long chunkId, float[] embedding) {
        vectors.put(chunkId, embedding.clone());
    }

    @Override
    public void deleteByDocument(Long kbId, Long documentId) {
        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                Wrappers.<KnowledgeChunk>lambdaQuery()
                        .eq(KnowledgeChunk::getKbId, kbId)
                        .eq(KnowledgeChunk::getDocumentId, documentId));
        for (KnowledgeChunk chunk : chunks) {
            vectors.remove(chunk.getId());
        }
        chunkMapper.delete(Wrappers.<KnowledgeChunk>lambdaQuery()
                .eq(KnowledgeChunk::getKbId, kbId)
                .eq(KnowledgeChunk::getDocumentId, documentId));
    }

    @Override
    public void deleteByKnowledgeBase(Long kbId) {
        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                Wrappers.<KnowledgeChunk>lambdaQuery().eq(KnowledgeChunk::getKbId, kbId));
        for (KnowledgeChunk chunk : chunks) {
            vectors.remove(chunk.getId());
        }
        chunkMapper.delete(Wrappers.<KnowledgeChunk>lambdaQuery().eq(KnowledgeChunk::getKbId, kbId));
    }

    @Override
    public List<KnowledgeVectorHit> search(Long kbId, float[] query, int topK, double threshold) {
        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                Wrappers.<KnowledgeChunk>lambdaQuery()
                        .eq(KnowledgeChunk::getKbId, kbId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) {
            return List.of();
        }
        Map<Long, KnowledgeDocument> docs = new HashMap<>();
        for (KnowledgeDocument doc : documentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery().eq(KnowledgeDocument::getKbId, kbId))) {
            docs.put(doc.getId(), doc);
        }
        List<KnowledgeVectorHit> hits = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            float[] vector = vectors.get(chunk.getId());
            if (vector == null || vector.length != query.length) {
                continue;
            }
            double similarity = cosine(query, vector);
            if (similarity < threshold) {
                continue;
            }
            KnowledgeDocument doc = docs.get(chunk.getDocumentId());
            hits.add(new KnowledgeVectorHit(chunk.getId(), chunk.getDocumentId(),
                    doc == null ? null : doc.getName(), chunk.getChunkIndex(),
                    chunk.getContent(), similarity));
        }
        hits.sort(Comparator.comparingDouble(KnowledgeVectorHit::similarity).reversed());
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
