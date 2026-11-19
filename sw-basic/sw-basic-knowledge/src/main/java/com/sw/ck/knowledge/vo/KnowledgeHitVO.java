package com.sw.ck.knowledge.vo;

import lombok.Data;

/** 检索命中分片（相似度为 cosine，0~1）。 */
@Data
public class KnowledgeHitVO {
    private Long chunkId;
    private Long documentId;
    private String documentName;
    private Integer chunkIndex;
    private String content;
    private Double similarity;
}
