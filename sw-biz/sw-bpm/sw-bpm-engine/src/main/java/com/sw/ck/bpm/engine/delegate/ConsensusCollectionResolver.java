package com.sw.ck.bpm.engine.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.delegate.FlowableCollectionHandler;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Flowable 多实例 collection 的受控解析出口。 */
@Component("consensusParticipantResolver")
public class ConsensusCollectionResolver extends NodeDelegateSupport
        implements FlowableCollectionHandler {
    public ConsensusCollectionResolver(RepositoryService repositoryService, ObjectMapper objectMapper,
                                       ParticipantResolverRegistry participantResolverRegistry) {
        super(repositoryService, objectMapper, participantResolverRegistry);
    }

    public List<String> resolve(DelegateExecution execution, String nodeKey) {
        var config = nodeConfigByKey(execution, nodeKey);
        NodeParticipantContext context = participantContext(execution, config);
        return participantResolverRegistry.resolve(context);
    }

    /**
     * Flowable 7.1 的 multi-instance delegateExpression 要求 bean 实现
     * FlowableCollectionHandler；普通 EL 方法调用返回 List 会在运行时被拒绝。
     * collection 参数由 Flowable 传入，节点身份从当前 FlowElement 取得，
     * 再复用统一参与人解析入口。
     */
    @Override
    public Collection<?> resolveCollection(Object collection, DelegateExecution execution) {
        var flowElement = execution.getCurrentFlowElement();
        if (flowElement == null || flowElement.getId() == null || flowElement.getId().isBlank()) {
            throw new IllegalStateException("会签节点上下文缺失");
        }
        return resolve(execution, flowElement.getId());
    }

    private java.util.Map<String, Object> nodeConfigByKey(DelegateExecution execution, String nodeKey) {
        var model = repositoryService.getBpmnModel(execution.getProcessDefinitionId());
        var element = model == null ? null : model.getFlowElement(nodeKey);
        String json = element == null ? null : element.getAttributeValue(FLOWABLE_NS, "nodeConfig");
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("会签配置读取失败", e);
        }
    }
}
