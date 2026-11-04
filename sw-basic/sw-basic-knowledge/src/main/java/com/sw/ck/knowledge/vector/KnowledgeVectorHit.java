package com.sw.ck.knowledge.vector;

/** 向量检索命中（元数据 + cosine 相似度）。 */
public record KnowledgeVectorHit(Long chunkId, Long documentId, String documentName,
                                 Integer chunkIndex, String content, double similarity) {
}
