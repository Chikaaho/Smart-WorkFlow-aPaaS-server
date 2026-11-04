package com.sw.ck.knowledge.dto;

import lombok.Data;

/** 检索测试/图知识检索节点入参。 */
@Data
public class KnowledgeRetrievalReqDTO {
    private String query;
    private Integer topK;
    private Double similarityThreshold;
}
