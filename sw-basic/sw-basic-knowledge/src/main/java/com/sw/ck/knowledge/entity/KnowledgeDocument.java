package com.sw.ck.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库文档（F1 阶段 3）：保存上传文件的抽取文本与处理状态，
 * 分片/向量见 {@link KnowledgeChunk}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private Long kbId;

    private String name;

    private String fileName;

    private String fileType;

    private Long fileSize;

    /** PENDING / PROCESSING / READY / FAILED */
    private String status;

    private String errorMessage;

    /** 成功写入向量库的分片数 */
    private Integer chunkCount;

    /** 抽取后的纯文本（供切分/重新处理） */
    private String content;
}
