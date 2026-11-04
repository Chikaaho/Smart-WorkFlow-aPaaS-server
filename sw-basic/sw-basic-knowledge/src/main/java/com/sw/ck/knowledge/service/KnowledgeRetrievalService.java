package com.sw.ck.knowledge.service;

import com.sw.ck.knowledge.dto.KnowledgeHitDTO;

import java.util.List;

/** 检索服务（检索测试与 graph 知识检索节点共用）。 */
public interface KnowledgeRetrievalService {

    List<KnowledgeHitDTO> retrieve(Long kbId, String query, Integer topK, Double similarityThreshold);
}
