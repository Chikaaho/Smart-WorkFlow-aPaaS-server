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
import org.flowable.bpmn.model.CollectionHandler;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态并行编排节点（I4 §3.1）：并行多实例 UserTask，每分支 = 一个去重后的有效部门负责人。
 * 分支来源在进入节点时由 {@code dynamicBranchCollectionResolver} 冻结；
 * 汇聚复用统一共识规则（consensusCompletionEvaluator：ALL/ANY/RATIO/VETO）。
 */
@Component
public class DynamicParallelNodeTranslator implements NodeTypeTranslator {
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final List<String> MODES = List.of("ALL", "ANY", "RATIO", "VETO");
    private static final List<String> SOURCE_TYPES = List.of("FORM_FIELD", "VARIABLE", "FIXED");

    private final ObjectMapper objectMapper;

    public DynamicParallelNodeTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<String> type() {
        return Optional.of("DYNAMIC_PARALLEL");
    }

    @Override
    public Optional<BpmNodeMetadata> metadata() {
        return Optional.of(new BpmNodeMetadata("动态并行", "按受控集合动态生成并行审批分支", "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(new BpmNodeConfigField("source", "分支来源", "object", true, Map.of()),
                        new BpmNodeConfigField("mode", "汇聚方式", "string", true,
                                Map.of("values", MODES)),
                        new BpmNodeConfigField("ratio", "通过比例", "integer", false,
                                Map.of("min", 1, "max", 100)),
                        new BpmNodeConfigField("maxBranches", "分支上限", "integer", false,
                                Map.of("min", 1, "max", 200)),
                        new BpmNodeConfigField("emptyStrategy", "空集合策略", "string", false,
                                Map.of("values", List.of("BLOCK", "PROCEED"))),
                        new BpmNodeConfigField("invalidStrategy", "失效对象策略", "string", false,
                                Map.of("values", List.of("BLOCK", "SKIP")))),
                "1", EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false, false, false, true));
    }

    @Override
    public Optional<List<GraphValidationError>> validateConfig(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null || !(config.get("source") instanceof Map<?, ?> source)) {
            return Optional.of(List.of(error(node, "动态并行缺少 source 配置")));
        }
        String sourceType = source.get("type") == null ? null : String.valueOf(source.get("type"));
        if (sourceType == null || !SOURCE_TYPES.contains(sourceType.toUpperCase())) {
            return Optional.of(List.of(error(node, "动态并行来源类型不合法: " + sourceType)));
        }
        Object sourceValue = source.get("value");
        if (sourceValue == null || String.valueOf(sourceValue).isBlank()) {
            return Optional.of(List.of(error(node, "动态并行来源值不能为空")));
        }
        if ("FIXED".equalsIgnoreCase(sourceType)) {
            Collection<?> values = sourceValue instanceof Collection<?> collection
                    ? collection : List.of(sourceValue);
            if (values.isEmpty() || values.stream().anyMatch(item -> {
                try {
                    return Long.parseLong(String.valueOf(item)) <= 0;
                } catch (Exception e) {
                    return true;
                }
            })) {
                return Optional.of(List.of(error(node, "动态并行 FIXED 来源只能配置正整数部门 ID")));
            }
        }
        String mode = config.get("mode") == null ? null : String.valueOf(config.get("mode"));
        if (mode == null || !MODES.contains(mode.toUpperCase())) {
            return Optional.of(List.of(error(node, "动态并行汇聚方式不合法: " + mode)));
        }
        if ("RATIO".equalsIgnoreCase(mode)) {
            try {
                int ratio = Integer.parseInt(String.valueOf(config.get("ratio")));
                if (ratio < 1 || ratio > 100) {
                    return Optional.of(List.of(error(node, "动态并行比例必须为 1-100")));
                }
            } catch (Exception e) {
                return Optional.of(List.of(error(node, "动态并行比例必须为整数")));
            }
        }
        if (config.get("maxBranches") != null) {
            try {
                int max = Integer.parseInt(String.valueOf(config.get("maxBranches")));
                if (max < 1 || max > 200) {
                    return Optional.of(List.of(error(node, "动态并行分支上限必须为 1-200")));
                }
            } catch (Exception e) {
                return Optional.of(List.of(error(node, "动态并行分支上限必须为整数")));
            }
        }
        String emptyStrategy = config.get("emptyStrategy") == null
                ? "BLOCK" : String.valueOf(config.get("emptyStrategy"));
        if (!List.of("BLOCK", "PROCEED").contains(emptyStrategy.toUpperCase())) {
            return Optional.of(List.of(error(node, "动态并行空集合策略不合法: " + emptyStrategy)));
        }
        String invalidStrategy = config.get("invalidStrategy") == null
                ? "BLOCK" : String.valueOf(config.get("invalidStrategy"));
        if (!List.of("BLOCK", "SKIP").contains(invalidStrategy.toUpperCase())) {
            return Optional.of(List.of(error(node, "动态并行失效对象策略不合法: " + invalidStrategy)));
        }
        return Optional.of(List.of());
    }

    @Override
    public FlowElement translate(GraphElement node) {
        Map<String, Object> config = node.getConfig() == null ? Map.of() : node.getConfig();
        UserTask task = new UserTask();
        task.setId(node.getId());
        task.setName(config.get("name") == null ? "动态并行" : String.valueOf(config.get("name")));
        // 与会签同口径：多实例元素变量按实例绑定原生 assignee
        task.setAssignee("${participantId}");
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        String collectionExpression = "${dynamicBranchCollectionResolver}";
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
            throw new IllegalArgumentException("动态并行配置序列化失败", e);
        }
        return task;
    }

    private FlowableListener listener(String event) {
        FlowableListener listener = new FlowableListener();
        listener.setEvent(event);
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation("${dynamicBranchTaskListener}");
        return listener;
    }

    private GraphValidationError error(GraphElement node, String message) {
        return GraphValidationError.builder().elementId(node.getId())
                .errorCode(BpmErrorCode.BRANCH_CONFIG_INVALID.getCode()).message(message).build();
    }
}
