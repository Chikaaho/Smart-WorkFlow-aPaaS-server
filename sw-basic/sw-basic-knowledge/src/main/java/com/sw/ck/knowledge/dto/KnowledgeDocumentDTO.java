package com.sw.ck.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 知识库文档（状态/分片数可见）。 */
@Data
public class KnowledgeDocumentDTO {
    private Long id;
    private Long kbId;
    private String name;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String status;
    private String errorMessage;
    private Integer chunkCount;
    private LocalDateTime createTime;
}
