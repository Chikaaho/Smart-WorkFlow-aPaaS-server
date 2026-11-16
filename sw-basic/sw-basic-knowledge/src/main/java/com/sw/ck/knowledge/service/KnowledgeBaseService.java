package com.sw.ck.knowledge.service;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.knowledge.dto.KnowledgeBaseCreateReqDTO;
import com.sw.ck.knowledge.vo.KnowledgeBaseVO;

public interface KnowledgeBaseService {

    PageResult<KnowledgeBaseVO> page(PageParam pageParam, String nameKeyword);

    KnowledgeBaseVO get(Long id);

    KnowledgeBaseVO create(KnowledgeBaseCreateReqDTO req);

    void delete(Long id);
}
