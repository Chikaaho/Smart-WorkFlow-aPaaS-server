package com.sw.ck.knowledge.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.knowledge.dto.KnowledgeBaseCreateReqDTO;
import com.sw.ck.knowledge.dto.KnowledgeBaseDTO;
import com.sw.ck.knowledge.dto.KnowledgeDocumentDTO;
import com.sw.ck.knowledge.dto.KnowledgeHitDTO;
import com.sw.ck.knowledge.dto.KnowledgeRetrievalReqDTO;
import com.sw.ck.knowledge.service.KnowledgeBaseService;
import com.sw.ck.knowledge.service.KnowledgeDocumentService;
import com.sw.ck.knowledge.service.KnowledgeRetrievalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库管理（F1 阶段 3）：知识库 CRUD、文档上传/删除、检索测试。
 * 权限沿用 Agent 模块既有权限码（读 view / 写 manage），不新增菜单权限。
 */
@RestController
@ConditionalOnProperty(prefix = "sw.knowledge", name = "enabled", havingValue = "true")
@RequestMapping("/agent/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService baseService;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeBaseController(KnowledgeBaseService baseService,
                                   KnowledgeDocumentService documentService,
                                   KnowledgeRetrievalService retrievalService) {
        this.baseService = baseService;
        this.documentService = documentService;
        this.retrievalService = retrievalService;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<PageResult<KnowledgeBaseDTO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize,
                                                @RequestParam(required = false) String nameKeyword) {
        PageParam param = new PageParam();
        param.setPageNum(pageNum);
        param.setPageSize(pageSize);
        return R.ok(baseService.page(param, nameKeyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<KnowledgeBaseDTO> get(@PathVariable Long id) {
        return R.ok(baseService.get(id));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<KnowledgeBaseDTO> create(@RequestBody KnowledgeBaseCreateReqDTO req) {
        return R.ok(baseService.create(req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Void> delete(@PathVariable Long id) {
        baseService.delete(id);
        return R.ok();
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<KnowledgeDocumentDTO>> documents(@PathVariable Long id) {
        return R.ok(documentService.list(id));
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<KnowledgeDocumentDTO> uploadDocument(@PathVariable Long id,
                                                  @RequestParam("file") MultipartFile file) {
        return R.ok(documentService.upload(id, file));
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Void> deleteDocument(@PathVariable Long id, @PathVariable Long documentId) {
        documentService.delete(id, documentId);
        return R.ok();
    }

    @PostMapping("/{id}/retrieve")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<KnowledgeHitDTO>> retrieve(@PathVariable Long id,
                                             @RequestBody KnowledgeRetrievalReqDTO req) {
        return R.ok(retrievalService.retrieve(id, req == null ? null : req.getQuery(),
                req == null ? null : req.getTopK(),
                req == null ? null : req.getSimilarityThreshold()));
    }
}
