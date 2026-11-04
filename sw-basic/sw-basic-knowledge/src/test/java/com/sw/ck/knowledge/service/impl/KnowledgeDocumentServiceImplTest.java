package com.sw.ck.knowledge.service.impl;

import com.sw.ck.knowledge.entity.KnowledgeBase;
import com.sw.ck.knowledge.entity.KnowledgeChunk;
import com.sw.ck.knowledge.entity.KnowledgeDocument;
import com.sw.ck.knowledge.mapper.KnowledgeBaseMapper;
import com.sw.ck.knowledge.mapper.KnowledgeChunkMapper;
import com.sw.ck.knowledge.mapper.KnowledgeDocumentMapper;
import com.sw.ck.knowledge.spi.KnowledgeEmbeddingClient;
import com.sw.ck.knowledge.util.KnowledgeDocumentParser;
import com.sw.ck.knowledge.vector.KnowledgeVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("F1 阶段 3：文档上传/切分/向量化")
class KnowledgeDocumentServiceImplTest {

    private final KnowledgeBaseMapper baseMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
    private final KnowledgeDocumentParser parser = mock(KnowledgeDocumentParser.class);
    private final KnowledgeEmbeddingClient embeddingClient = mock(KnowledgeEmbeddingClient.class);
    private final KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
    private final KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(
            baseMapper, documentMapper, chunkMapper, parser, embeddingClient, vectorStore);

    @Test
    @DisplayName("上传 TXT：切分为 3 片、逐片向量化，状态 READY 且分片数=3")
    void upload_shouldChunkEmbedAndMarkReady() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(5L);
        kb.setEmbeddingModelId(9L);
        kb.setChunkSize(4);
        kb.setChunkOverlap(0);
        when(baseMapper.selectById(5L)).thenReturn(kb);
        when(parser.parse(any())).thenReturn("abcdefghij");
        AtomicLong docId = new AtomicLong(100);
        AtomicLong chunkId = new AtomicLong(200);
        when(documentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            inv.<KnowledgeDocument>getArgument(0).setId(docId.incrementAndGet());
            return 1;
        });
        when(chunkMapper.insert(any(KnowledgeChunk.class))).thenAnswer(inv -> {
            inv.<KnowledgeChunk>getArgument(0).setId(chunkId.incrementAndGet());
            return 1;
        });
        when(embeddingClient.embed(eq(9L), anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(1);
            return texts.stream().map(t -> new float[]{1f, 0f}).toList();
        });
        MockMultipartFile file = new MockMultipartFile("file", "faq.txt", "text/plain",
                "abcdefghij".getBytes(StandardCharsets.UTF_8));

        var dto = service.upload(5L, file);

        assertThat(dto.getStatus()).isEqualTo(KnowledgeDocument.STATUS_READY);
        assertThat(dto.getChunkCount()).isEqualTo(3);
        verify(chunkMapper, times(3)).insert(any(KnowledgeChunk.class));
        verify(vectorStore, times(3)).store(any(), any(float[].class));
        verify(embeddingClient).embed(eq(9L), anyList());
    }
}
