package com.sw.ck.knowledge.service;

import com.sw.ck.knowledge.dto.KnowledgeDocumentDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface KnowledgeDocumentService {

    List<KnowledgeDocumentDTO> list(Long kbId);

    KnowledgeDocumentDTO upload(Long kbId, MultipartFile file);

    void delete(Long kbId, Long documentId);
}
