package com.sw.ck.knowledge.vector;

import java.util.List;

/**
 * 向量存取抽象（D022-07）：PostgreSQL 使用 pgvector 列；H2 测试使用内存实现。
 * 所有方法均按当前登录租户隔离，调用方无需重复传 tenantId。
 */
public interface KnowledgeVectorStore {

    /** 写入/覆盖分片向量（SQL 层按 tenant_id 限定）。 */
    void store(Long chunkId, float[] embedding);

    /** 删除某文档的全部分片及其向量（物理删除，确保向量不残留）。 */
    void deleteByDocument(Long kbId, Long documentId);

    /** 删除某知识库的全部分片及其向量（物理删除）。 */
    void deleteByKnowledgeBase(Long kbId);

    /** 相似度检索：返回 cosine 相似度 >= threshold 的前 topK 条。 */
    List<KnowledgeVectorHit> search(Long kbId, float[] query, int topK, double threshold);
}
