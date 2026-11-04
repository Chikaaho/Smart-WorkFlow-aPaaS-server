package com.sw.ck.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库分片元数据（F1 阶段 3）。{@code embedding} 列由
 * {@code KnowledgeVectorStore} 经 JDBC 写入/检索，不映射到实体（pgvector 类型
 * 无法走 MyBatis 默认类型处理器）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_knowledge_chunk")
public class KnowledgeChunk extends BaseEntity {

    private Long kbId;

    private Long documentId;

    private Integer chunkIndex;

    private String content;
}
