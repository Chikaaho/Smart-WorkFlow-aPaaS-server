package com.sw.ck.knowledge.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.knowledge.dto.KnowledgeBaseCreateReqDTO;
import com.sw.ck.knowledge.dto.KnowledgeBaseDTO;
import com.sw.ck.knowledge.entity.KnowledgeBase;
import com.sw.ck.knowledge.entity.KnowledgeDocument;
import com.sw.ck.knowledge.mapper.KnowledgeBaseMapper;
import com.sw.ck.knowledge.mapper.KnowledgeDocumentMapper;
import com.sw.ck.knowledge.service.KnowledgeBaseService;
import com.sw.ck.knowledge.spi.KnowledgeEmbeddingClient;
import com.sw.ck.knowledge.vector.KnowledgeVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 知识库管理（F1 阶段 3）：创建/分页/详情/删除（删除时一并清理分片与向量）。 */
@Service
@ConditionalOnProperty(prefix = "sw.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;
    private static final int MIN_CHUNK_SIZE = 100;
    private static final int MAX_CHUNK_SIZE = 4000;

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeVectorStore vectorStore;
    private final KnowledgeEmbeddingClient embeddingClient;

    public KnowledgeBaseServiceImpl(KnowledgeBaseMapper baseMapper,
                                    KnowledgeDocumentMapper documentMapper,
                                    KnowledgeVectorStore vectorStore,
                                    KnowledgeEmbeddingClient embeddingClient) {
        this.baseMapper = baseMapper;
        this.documentMapper = documentMapper;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
    }

    @Override
    public PageResult<KnowledgeBaseDTO> page(PageParam pageParam, String nameKeyword) {
        String keyword = nameKeyword == null || nameKeyword.isBlank() ? null : nameKeyword;
        IPage<KnowledgeBase> page = baseMapper.selectPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .like(keyword != null, KnowledgeBase::getName, keyword)
                        .orderByDesc(KnowledgeBase::getId));
        return PageResult.of(page.convert(this::toDTO));
    }

    @Override
    public KnowledgeBaseDTO get(Long id) {
        return toDTO(require(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseDTO create(KnowledgeBaseCreateReqDTO req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "知识库名称不能为空");
        }
        if (req.getEmbeddingModelId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "请选择 Embedding 模型");
        }
        int chunkSize = req.getChunkSize() == null ? DEFAULT_CHUNK_SIZE : req.getChunkSize();
        int chunkOverlap = req.getChunkOverlap() == null ? DEFAULT_CHUNK_OVERLAP : req.getChunkOverlap();
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "切分块大小必须在 " + MIN_CHUNK_SIZE + "~" + MAX_CHUNK_SIZE + " 之间");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "切分重叠必须大于等于 0 且小于块大小");
        }
        Long exists = baseMapper.selectCount(
                Wrappers.<KnowledgeBase>lambdaQuery().eq(KnowledgeBase::getName, req.getName().trim()));
        if (exists != null && exists > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "知识库名称已存在: " + req.getName());
        }
        // 校验模型存在、启用、供应商启用且能力含 EMBEDDING（实现位于 agent 模块）
        embeddingClient.assertEmbeddingModel(req.getEmbeddingModelId());

        KnowledgeBase entity = new KnowledgeBase();
        entity.setName(req.getName().trim());
        entity.setDescription(req.getDescription());
        entity.setEmbeddingModelId(req.getEmbeddingModelId());
        entity.setChunkSize(chunkSize);
        entity.setChunkOverlap(chunkOverlap);
        baseMapper.insert(entity);
        return toDTO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        // 先物理删除分片与向量（PG），再逻辑删除文档与知识库
        vectorStore.deleteByKnowledgeBase(id);
        documentMapper.delete(Wrappers.<KnowledgeDocument>lambdaQuery().eq(KnowledgeDocument::getKbId, id));
        baseMapper.deleteById(id);
    }

    private KnowledgeBase require(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "知识库 id 不能为空");
        }
        KnowledgeBase entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "知识库不存在");
        }
        return entity;
    }

    private KnowledgeBaseDTO toDTO(KnowledgeBase entity) {
        KnowledgeBaseDTO dto = new KnowledgeBaseDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setEmbeddingModelId(entity.getEmbeddingModelId());
        dto.setChunkSize(entity.getChunkSize());
        dto.setChunkOverlap(entity.getChunkOverlap());
        dto.setCreateTime(entity.getCreateTime());
        return dto;
    }
}
