package com.sw.ck.knowledge.dto;

import lombok.Data;

/** 新建知识库请求：绑定一个 EMBEDDING 模型与切分参数。 */
@Data
public class KnowledgeBaseCreateReqDTO {
    private String name;
    private String description;
    private Long embeddingModelId;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
