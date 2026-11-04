package com.sw.ck.knowledge.spi;

import java.util.List;

/**
 * 知识库对 Embedding 能力的 SPI（实现位于 sw-basic-agent，避免 knowledge 反向依赖 agent）。
 * <p>
 * 实现方必须校验模型存在、启用、供应商启用且具备 EMBEDDING 能力；密钥只在实现内部解密，
 * 不得出现在异常文本、日志或返回值中。
 * </p>
 */
public interface KnowledgeEmbeddingClient {

    /** 校验模型可用于向量化；不可用时抛出可读业务异常。 */
    void assertEmbeddingModel(Long modelId);

    /** 批量向量化（顺序与入参一致）；任一条失败抛异常。 */
    List<float[]> embed(Long modelId, List<String> texts);
}
