package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.knowledge.service.KnowledgeRetrievalService;
import com.sw.ck.knowledge.vo.KnowledgeHitVO;

import java.util.List;
import java.util.Locale;

/**
 * 知识检索节点执行（自 AgentGraphInterpreter 拆出，行为不变，F1 阶段 3）：
 * 读取 knowledgeBaseId/topK/similarityThreshold，检索结果按"文档 + 相似度 + 分片正文"
 * 拼成文本写入输出变量，供下游 LLM 的 userPromptTemplate 直接引用。空结果写固定提示，
 * 避免下游模型把空字符串误当上下文。
 */
final class AgentGraphKnowledgeNodeRunner {

    private AgentGraphKnowledgeNodeRunner() {
    }

    /** 知识检索节点（图执行与单步调试共用）。 */
    static String retrieveKnowledge(KnowledgeRetrievalService service, GraphElement node,
                                    String query, AgentGraphInterpreter.NodeExecutionTrace trace) {
        trace.setInputText(query);
        if (service == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "知识检索服务未装配，无法执行 KNOWLEDGE 节点: " + node.getId());
        }
        Long kbId = AgentGraphNodeConfigs.parseConfigLong(node.getConfig() == null
                ? null : node.getConfig().get(AgentGraphInterpreter.CONFIG_KEY_KNOWLEDGE_BASE_ID));
        if (kbId == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "KNOWLEDGE 节点缺少知识库引用: " + node.getId());
        }
        int topK = AgentGraphNodeConfigs.configInt(node, AgentGraphInterpreter.CONFIG_KEY_TOP_K,
                AgentGraphInterpreter.DEFAULT_KNOWLEDGE_TOP_K);
        double threshold = AgentGraphNodeConfigs.configDouble(node, AgentGraphInterpreter.CONFIG_KEY_SIMILARITY_THRESHOLD, 0.0);
        List<KnowledgeHitVO> hits;
        try {
            hits = service.retrieve(kbId, query, topK, threshold);
        } catch (AgentGraphInterpreter.GraphExecutionException e) {
            throw e;
        } catch (BaseException e) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_KNOWLEDGE_RETRIEVAL_FAILED,
                    e.getMessage(), e);
        } catch (Exception e) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_KNOWLEDGE_RETRIEVAL_FAILED,
                    e.getMessage(), e);
        }
        String output = formatKnowledgeHits(hits);
        trace.setOutputText(output);
        return output;
    }

    private static String formatKnowledgeHits(List<KnowledgeHitVO> hits) {
        if (hits == null || hits.isEmpty()) {
            return "未检索到相关内容。";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (KnowledgeHitVO hit : hits) {
            if (sb.length() > 0) {
                sb.append("\n\n---\n\n");
            }
            sb.append('[').append(index++).append("] 文档：")
                    .append(hit.getDocumentName() == null ? "-" : hit.getDocumentName())
                    .append("（相似度 ").append(String.format(Locale.ROOT, "%.3f",
                            hit.getSimilarity() == null ? 0.0 : hit.getSimilarity()))
                    .append("）\n").append(hit.getContent());
        }
        return sb.toString();
    }
}
