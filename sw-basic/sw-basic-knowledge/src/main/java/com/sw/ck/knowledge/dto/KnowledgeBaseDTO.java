package com.sw.ck.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 知识库列表/详情（F1 阶段 3）。 */
@Data
public class KnowledgeBaseDTO {
    private Long id;
    private String name;
    private String description;
    private Long embeddingModelId;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private LocalDateTime createTime;
}
