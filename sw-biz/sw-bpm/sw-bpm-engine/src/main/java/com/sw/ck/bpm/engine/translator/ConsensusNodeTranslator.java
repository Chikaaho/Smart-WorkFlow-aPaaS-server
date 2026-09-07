package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.CollectionHandler;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;

/** 会签节点：并行多实例 + 统一参与人集合 + 受控结算函数。 */
@Component
public class ConsensusNodeTranslator implements NodeTypeTranslator {
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private final ObjectMapper objectMapper;

    public ConsensusNodeTranslator(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override public String type() { return "CONSENSUS"; }

    @Override
    public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata("会签", "多人并行审批节点", "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(new BpmNodeConfigField("participant", "参与人", "object", true, Map.of()),
                        new BpmNodeConfigField("mode", "结算方式", "string", true,
                                Map.of("values", List.of("ALL", "ANY", "RATIO"))),
                        new BpmNodeConfigField("ratio", "通过比例", "integer", false,
                                Map.of("min", 1, "max", 100))),
                "1", EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                        BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false, false, false, true);
    }

    @Override
    public List<GraphValidationError> validateConfig(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null || !(config.get("participant") instanceof Map<?, ?>)) {
            return List.of(error(node, "会签缺少参与人配置"));
        }
        Map<?, ?> participant = (Map<?, ?>) config.get("participant");
        String strategy = participant.get("strategy") == null ? null : String.valueOf(participant.get("strategy"));
        if (strategy == null || !List.of(ParticipantStrategy.FIXED_USER, ParticipantStrategy.ROLE,
                ParticipantStrategy.EXPRESSION, ParticipantStrategy.ADAPTER).contains(strategy.toUpperCase())) {
            return List.of(error(node, "会签参与人策略不合法"));
        }
        Object participantValue = participant.get("value");
        if (!ParticipantStrategy.ADAPTER.equalsIgnoreCase(strategy)
                && (participantValue == null || String.valueOf(participantValue).isBlank())) {
            return List.of(error(node, "会签参与人值不能为空"));
        }
        if (ParticipantStrategy.ADAPTER.equalsIgnoreCase(strategy)
                && (participant.get("adapterId") == null || String.valueOf(participant.get("adapterId")).isBlank())) {
            return List.of(error(node, "会签适配器标识不能为空"));
        }
        if (ParticipantStrategy.FIXED_USER.equalsIgnoreCase(strategy)) {
            Collection<?> values = participantValue instanceof Collection<?> collection
                    ? collection : List.of(participantValue);
            if (values.stream().anyMatch(item -> {
                try { return Long.parseLong(String.valueOf(item)) <= 0; }
                catch (Exception e) { return true; }
            })) return List.of(error(node, "会签 FIXED_USER 只能配置正整数用户 ID"));
        } else if (ParticipantStrategy.EXPRESSION.equalsIgnoreCase(strategy)) {
            try { RestrictedExpressionEvaluator.value(String.valueOf(participantValue), Map.of()); }
            catch (RuntimeException e) { return List.of(error(node, "会签 EXPRESSION 语法不合法")); }
        }
        String mode = config.get("mode") == null ? null : String.valueOf(config.get("mode"));
        if (!List.of("ALL", "ANY", "RATIO").contains(mode)) return List.of(error(node, "会签方式不合法"));
        if ("RATIO".equals(mode)) {
            try {
                int ratio = Integer.parseInt(String.valueOf(config.get("ratio")));
                if (ratio < 1 || ratio > 100) return List.of(error(node, "会签比例必须为 1-100"));
            } catch (Exception e) { return List.of(error(node, "会签比例必须为整数")); }
        }
        return List.of();
    }

    @Override
    public FlowElement translate(GraphElement node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        UserTask task = new UserTask();
        task.setId(node.getId());
        task.setName(config.get("name") == null ? "会签" : String.valueOf(config.get("name")));
        // 多实例元素变量按实例绑定原生 assignee，避免 create 监听器在父执行上下文
        // 中读取到同一参与人，导致所有并行子任务历史上显示为同一审批人。
        task.setAssignee("${participantId}");
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        String collectionExpression = "${consensusParticipantResolver}";
        // Flowable 7.1 的 XML 解析器只会把 collectionString 写入模型，
        // 不会将其转换为可重新解析的 flowable:collection 属性。
        // 通过 CollectionHandler 让 XML 同时保留 delegateExpression 与 collection string，
        // 保证部署后的多实例校验和运行时解析都能拿到集合。
        loop.setCollectionString(collectionExpression);
        CollectionHandler collectionHandler = new CollectionHandler();
        collectionHandler.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        collectionHandler.setImplementation(collectionExpression);
        loop.setHandler(collectionHandler);
        loop.setElementVariable("participantId");
        String mode = String.valueOf(config.getOrDefault("mode", "ALL"));
        String ratio = String.valueOf(config.getOrDefault("ratio", "100"));
        loop.setCompletionCondition("${consensusCompletionEvaluator.shouldComplete(execution, '"
                + mode + "', " + ratio + ")}");
        task.setLoopCharacteristics(loop);
        FlowableListener create = listener("create");
        FlowableListener complete = listener("complete");
        task.setTaskListeners(new java.util.ArrayList<>(List.of(create, complete)));
        try {
            ExtensionAttribute attr = new ExtensionAttribute("nodeConfig", objectMapper.writeValueAsString(config));
            attr.setNamespace(FLOWABLE_NS);
            attr.setNamespacePrefix("flowable");
            task.addAttribute(attr);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("会签配置序列化失败", e);
        }
        return task;
    }

    private FlowableListener listener(String event) {
        FlowableListener listener = new FlowableListener();
        listener.setEvent(event);
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation("${consensusTaskListener}");
        return listener;
    }

    private GraphValidationError error(GraphElement node, String message) {
        return GraphValidationError.builder().elementId(node.getId())
                .errorCode(BpmErrorCode.COUNTER_CONFIG_INVALID.getCode()).message(message).build();
    }
}
