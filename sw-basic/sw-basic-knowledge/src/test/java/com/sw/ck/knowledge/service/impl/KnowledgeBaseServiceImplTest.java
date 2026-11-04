package com.sw.ck.knowledge.service.impl;

import com.sw.ck.knowledge.dto.KnowledgeBaseCreateReqDTO;
import com.sw.ck.knowledge.entity.KnowledgeBase;
import com.sw.ck.knowledge.mapper.KnowledgeBaseMapper;
import com.sw.ck.knowledge.mapper.KnowledgeDocumentMapper;
import com.sw.ck.knowledge.spi.KnowledgeEmbeddingClient;
import com.sw.ck.knowledge.vector.KnowledgeVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("F1 阶段 3：知识库服务")
class KnowledgeBaseServiceImplTest {

    private final KnowledgeBaseMapper baseMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
    private final KnowledgeEmbeddingClient embeddingClient = mock(KnowledgeEmbeddingClient.class);
    private final KnowledgeBaseServiceImpl service =
            new KnowledgeBaseServiceImpl(baseMapper, documentMapper, vectorStore, embeddingClient);

    @Test
    @DisplayName("创建知识库：校验 EMBEDDING 模型并按默认/自定义切分参数落库")
    void create_shouldValidateEmbeddingModelAndSave() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        KnowledgeBaseCreateReqDTO req = new KnowledgeBaseCreateReqDTO();
        req.setName("产品手册");
        req.setEmbeddingModelId(9L);
        req.setChunkSize(500);
        req.setChunkOverlap(50);

        var dto = service.create(req);

        assertThat(dto.getName()).isEqualTo("产品手册");
        assertThat(dto.getEmbeddingModelId()).isEqualTo(9L);
        assertThat(dto.getChunkSize()).isEqualTo(500);
        assertThat(dto.getChunkOverlap()).isEqualTo(50);
        verify(embeddingClient).assertEmbeddingModel(9L);
        verify(baseMapper).insert(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("删除知识库：先清理分片向量，再删除文档与知识库")
    void delete_shouldClearVectorsFirst() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(3L);
        when(baseMapper.selectById(3L)).thenReturn(kb);

        service.delete(3L);

        verify(vectorStore).deleteByKnowledgeBase(3L);
        verify(documentMapper).delete(any());
        verify(baseMapper).deleteById(3L);
    }

    @Test
    @DisplayName("模型不具备 EMBEDDING 能力时拒绝创建（不落库）")
    void create_shouldRejectNonEmbeddingModel() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        doThrow(new IllegalArgumentException("模型不具备 EMBEDDING 能力"))
                .when(embeddingClient).assertEmbeddingModel(anyLong());
        KnowledgeBaseCreateReqDTO req = new KnowledgeBaseCreateReqDTO();
        req.setName("bad");
        req.setEmbeddingModelId(1L);

        assertThatThrownBy(() -> service.create(req)).hasMessageContaining("EMBEDDING");
    }
}
