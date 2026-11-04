package com.sw.ck.knowledge.service.impl;

import com.sw.ck.knowledge.entity.KnowledgeBase;
import com.sw.ck.knowledge.mapper.KnowledgeBaseMapper;
import com.sw.ck.knowledge.spi.KnowledgeEmbeddingClient;
import com.sw.ck.knowledge.vector.KnowledgeVectorHit;
import com.sw.ck.knowledge.vector.KnowledgeVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("F1 阶段 3：知识检索服务")
class KnowledgeRetrievalServiceImplTest {

    private final KnowledgeBaseMapper baseMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeEmbeddingClient embeddingClient = mock(KnowledgeEmbeddingClient.class);
    private final KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
    private final KnowledgeRetrievalServiceImpl service =
            new KnowledgeRetrievalServiceImpl(baseMapper, embeddingClient, vectorStore);

    @Test
    @DisplayName("检索：查询向量化后按 topK/阈值检索并映射命中")
    void retrieve_shouldEmbedAndMapHits() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(8L);
        kb.setEmbeddingModelId(9L);
        when(baseMapper.selectById(8L)).thenReturn(kb);
        when(embeddingClient.embed(eq(9L), any())).thenReturn(List.of(new float[]{1f, 0f}));
        when(vectorStore.search(eq(8L), any(float[].class), eq(3), eq(0.4)))
                .thenReturn(List.of(new KnowledgeVectorHit(1L, 2L, "a.md", 0, "内容", 0.9)));

        var hits = service.retrieve(8L, "问题", 3, 0.4);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getDocumentName()).isEqualTo("a.md");
        assertThat(hits.get(0).getSimilarity()).isEqualTo(0.9);
        verify(embeddingClient).embed(9L, List.of("问题"));
    }
}
