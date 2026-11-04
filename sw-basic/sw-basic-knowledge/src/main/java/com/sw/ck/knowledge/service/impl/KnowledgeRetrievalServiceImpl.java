package com.sw.ck.knowledge.service.impl;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.knowledge.dto.KnowledgeHitDTO;
import com.sw.ck.knowledge.entity.KnowledgeBase;
import com.sw.ck.knowledge.mapper.KnowledgeBaseMapper;
import com.sw.ck.knowledge.service.KnowledgeRetrievalService;
import com.sw.ck.knowledge.spi.KnowledgeEmbeddingClient;
import com.sw.ck.knowledge.vector.KnowledgeVectorHit;
import com.sw.ck.knowledge.vector.KnowledgeVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/** 检索测试与 graph 知识节点共用：校验知识库 → 向量化 query → pgvector 检索。 */
@Service
@ConditionalOnProperty(prefix = "sw.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final double DEFAULT_THRESHOLD = 0.0;

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeEmbeddingClient embeddingClient;
    private final KnowledgeVectorStore vectorStore;

    public KnowledgeRetrievalServiceImpl(KnowledgeBaseMapper baseMapper,
                                         KnowledgeEmbeddingClient embeddingClient,
                                         KnowledgeVectorStore vectorStore) {
        this.baseMapper = baseMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeHitDTO> retrieve(Long kbId, String query, Integer topK,
                                          Double similarityThreshold) {
        if (kbId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "知识库 id 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "检索问题不能为空");
        }
        // 知识库不存在/跨租户 → 404（租户拦截器生效）
        KnowledgeBase base = baseMapper.selectById(kbId);
        if (base == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "知识库不存在");
        }
        int limit = topK == null ? DEFAULT_TOP_K : Math.max(1, Math.min(MAX_TOP_K, topK));
        double threshold = similarityThreshold == null
                ? DEFAULT_THRESHOLD : Math.max(0.0, Math.min(1.0, similarityThreshold));
        List<float[]> vectors = embeddingClient.embed(base.getEmbeddingModelId(), List.of(query.trim()));
        if (vectors == null || vectors.isEmpty() || vectors.get(0) == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "检索问题向量化失败");
        }
        return vectorStore.search(kbId, vectors.get(0), limit, threshold).stream()
                .map(this::toDTO)
                .toList();
    }

    private KnowledgeHitDTO toDTO(KnowledgeVectorHit hit) {
        KnowledgeHitDTO dto = new KnowledgeHitDTO();
        dto.setChunkId(hit.chunkId());
        dto.setDocumentId(hit.documentId());
        dto.setDocumentName(hit.documentName());
        dto.setChunkIndex(hit.chunkIndex());
        dto.setContent(hit.content());
        dto.setSimilarity(hit.similarity());
        return dto;
    }
}
