package com.sw.ck.knowledge.service;

import com.sw.ck.knowledge.vo.KnowledgeDocumentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface KnowledgeDocumentService {

    List<KnowledgeDocumentVO> list(Long kbId);

    KnowledgeDocumentVO upload(Long kbId, MultipartFile file);

    void delete(Long kbId, Long documentId);
}
