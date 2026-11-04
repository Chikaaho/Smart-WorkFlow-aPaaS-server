package com.sw.ck.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库（F1 阶段 3，D022-07）：一个知识库绑定一个 EMBEDDING 能力模型，
 * 并保存该库统一使用的切分参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_knowledge_base")
public class KnowledgeBase extends BaseEntity {

    private String name;

    private String description;

    /** 向量化模型 id（sw_agent_model，capabilities 含 EMBEDDING） */
    private Long embeddingModelId;

    /** 切分块大小（字符数） */
    private Integer chunkSize;

    /** 切分重叠字符数 */
    private Integer chunkOverlap;
}
