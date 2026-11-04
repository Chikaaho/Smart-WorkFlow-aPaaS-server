package com.sw.ck.agent.knowledge;

import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.knowledge.spi.KnowledgeEmbeddingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库 Embedding SPI 的 Agent 实现（F1 阶段 3）：复用模型运行时服务的启停校验、
 * 单例装配与密钥解密，knowledge 模块不直接依赖 agent 模块。
 */
@Component
@ConditionalOnProperty(prefix = "sw.agent", name = "enabled", havingValue = "true")
public class AgentKnowledgeEmbeddingClient implements KnowledgeEmbeddingClient {

    private final AgentModelRuntimeService runtimeService;

    public AgentKnowledgeEmbeddingClient(AgentModelRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public void assertEmbeddingModel(Long modelId) {
        runtimeService.assertEmbeddingUsable(modelId);
    }

    @Override
    public List<float[]> embed(Long modelId, List<String> texts) {
        return runtimeService.embedAll(modelId, texts);
    }
}
