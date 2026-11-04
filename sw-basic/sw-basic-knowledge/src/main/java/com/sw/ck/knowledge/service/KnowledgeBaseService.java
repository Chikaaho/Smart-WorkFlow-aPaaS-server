package com.sw.ck.knowledge.service;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.knowledge.dto.KnowledgeBaseCreateReqDTO;
import com.sw.ck.knowledge.dto.KnowledgeBaseDTO;

public interface KnowledgeBaseService {

    PageResult<KnowledgeBaseDTO> page(PageParam pageParam, String nameKeyword);

    KnowledgeBaseDTO get(Long id);

    KnowledgeBaseDTO create(KnowledgeBaseCreateReqDTO req);

    void delete(Long id);
}
