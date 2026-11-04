package com.sw.ck.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.knowledge.dto.KnowledgeDocumentDTO;
import com.sw.ck.knowledge.entity.KnowledgeBase;
import com.sw.ck.knowledge.entity.KnowledgeChunk;
import com.sw.ck.knowledge.entity.KnowledgeDocument;
import com.sw.ck.knowledge.mapper.KnowledgeBaseMapper;
import com.sw.ck.knowledge.mapper.KnowledgeChunkMapper;
import com.sw.ck.knowledge.mapper.KnowledgeDocumentMapper;
import com.sw.ck.knowledge.service.KnowledgeDocumentService;
import com.sw.ck.knowledge.spi.KnowledgeEmbeddingClient;
import com.sw.ck.knowledge.util.KnowledgeDocumentParser;
import com.sw.ck.knowledge.util.KnowledgeTextChunker;
import com.sw.ck.knowledge.vector.KnowledgeVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文档上传/切分/向量化（F1 阶段 3）。同步处理并落状态：
 * PENDING → PROCESSING → READY/FAILED；失败时保留文档记录与可读原因，清理已写入分片。
 */
@Service
@ConditionalOnProperty(prefix = "sw.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int EMBED_BATCH_SIZE = 32;
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "txt", "md", "markdown");

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentParser parser;
    private final KnowledgeEmbeddingClient embeddingClient;
    private final KnowledgeVectorStore vectorStore;

    public KnowledgeDocumentServiceImpl(KnowledgeBaseMapper baseMapper,
                                        KnowledgeDocumentMapper documentMapper,
                                        KnowledgeChunkMapper chunkMapper,
                                        KnowledgeDocumentParser parser,
                                        KnowledgeEmbeddingClient embeddingClient,
                                        KnowledgeVectorStore vectorStore) {
        this.baseMapper = baseMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.parser = parser;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeDocumentDTO> list(Long kbId) {
        requireBase(kbId);
        return documentMapper.selectList(
                        Wrappers.<KnowledgeDocument>lambdaQuery()
                                .eq(KnowledgeDocument::getKbId, kbId)
                                .orderByDesc(KnowledgeDocument::getId))
                .stream().map(this::toDTO).toList();
    }

    @Override
    public KnowledgeDocumentDTO upload(Long kbId, MultipartFile file) {
        KnowledgeBase base = requireBase(kbId);
        if (file == null || file.isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "文件超过 5MB 上限");
        }
        String fileName = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "仅支持 PDF、Word、TXT、Markdown 文档");
        }
        String text;
        try {
            text = parser.parse(file.getBytes());
        } catch (IllegalArgumentException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, e.getMessage());
        } catch (Exception e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "读取上传文件失败: " + e.getMessage());
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setName(fileName);
        doc.setFileName(fileName);
        doc.setFileType(extension);
        doc.setFileSize(file.getSize());
        doc.setStatus(KnowledgeDocument.STATUS_PENDING);
        doc.setChunkCount(0);
        doc.setContent(text);
        documentMapper.insert(doc);
        Long docId = doc.getId();

        try {
            updateStatus(doc, KnowledgeDocument.STATUS_PROCESSING, null, 0);
            List<String> parts = KnowledgeTextChunker.chunk(text, base.getChunkSize(), base.getChunkOverlap());
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("切分后没有有效内容");
            }
            List<KnowledgeChunk> chunks = new ArrayList<>(parts.size());
            for (int i = 0; i < parts.size(); i++) {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setKbId(kbId);
                chunk.setDocumentId(docId);
                chunk.setChunkIndex(i);
                chunk.setContent(parts.get(i));
                chunkMapper.insert(chunk);
                chunks.add(chunk);
            }
            for (int from = 0; from < chunks.size(); from += EMBED_BATCH_SIZE) {
                int to = Math.min(from + EMBED_BATCH_SIZE, chunks.size());
                List<KnowledgeChunk> batch = chunks.subList(from, to);
                List<String> texts = batch.stream().map(KnowledgeChunk::getContent).toList();
                List<float[]> vectors = embeddingClient.embed(base.getEmbeddingModelId(), texts);
                if (vectors == null || vectors.size() != batch.size()) {
                    throw new IllegalStateException("Embedding 返回数量与分片数量不一致");
                }
                for (int i = 0; i < batch.size(); i++) {
                    vectorStore.store(batch.get(i).getId(), vectors.get(i));
                }
            }
            updateStatus(doc, KnowledgeDocument.STATUS_READY, null, chunks.size());
            return toDTO(doc);
        } catch (Exception e) {
            // 失败保留文档记录与原因；清理已写入的分片/向量，便于重传
            try {
                vectorStore.deleteByDocument(kbId, docId);
            } catch (Exception ignored) {
                // 清理失败不掩盖原始错误
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            updateStatus(doc, KnowledgeDocument.STATUS_FAILED, truncate(message, 500), 0);
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "文档处理失败: " + message);
        }
    }

    @Override
    public void delete(Long kbId, Long documentId) {
        requireBase(kbId);
        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null || !kbId.equals(doc.getKbId())) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "文档不存在");
        }
        vectorStore.deleteByDocument(kbId, documentId);
        documentMapper.deleteById(documentId);
    }

    private KnowledgeBase requireBase(Long kbId) {
        if (kbId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "知识库 id 不能为空");
        }
        KnowledgeBase base = baseMapper.selectById(kbId);
        if (base == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "知识库不存在");
        }
        return base;
    }

    private void updateStatus(KnowledgeDocument doc, String status, String error, int chunkCount) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(doc.getId());
        update.setStatus(status);
        update.setErrorMessage(error);
        update.setChunkCount(chunkCount);
        documentMapper.updateById(update);
  
        doc.setStatus(status);
        doc.setErrorMessage(error);
        doc.setChunkCount(chunkCount);
    }

    private String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private KnowledgeDocumentDTO toDTO(KnowledgeDocument entity) {
        KnowledgeDocumentDTO dto = new KnowledgeDocumentDTO();
        dto.setId(entity.getId());
        dto.setKbId(entity.getKbId());
        dto.setName(entity.getName());
        dto.setFileName(entity.getFileName());
        dto.setFileType(entity.getFileType());
        dto.setFileSize(entity.getFileSize());
        dto.setStatus(entity.getStatus());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setChunkCount(entity.getChunkCount());
        dto.setCreateTime(entity.getCreateTime());
        return dto;
    }
}
