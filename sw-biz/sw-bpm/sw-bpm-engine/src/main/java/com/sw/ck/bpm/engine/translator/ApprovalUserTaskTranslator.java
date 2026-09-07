package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.node.BpmNodeCapability;
import com.sw.ck.bpm.api.node.BpmNodeConfigField;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverType;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;

/**
 * APPROVAL 节点翻译器 —— 画布 APPROVAL 节点 → BPMN {@link UserTask}。
 * <p>
 * 自 B2（M04-F08-01）起从 {@link GraphToBpmnTranslator} 的 switch 中拆出，
 * 经 {@link NodeTypeTranslator} 注册表分发，翻译行为与拆分前逐字节一致。
 * </p>
 *
 * <h3>扩展属性</h3>
 * APPROVAL 节点翻译为 UserTask 时：
 * <ul>
 *   <li>通过 {@link ExtensionAttribute}（{@code flowable:approverConfig} 命名空间属性）
 *       写入 approver 配置（type + value JSON）</li>
 *   <li>通过 {@link FlowableListener} 挂载 {@code ApprovalTaskListener}（create 事件）</li>
 *   <li>DESIGNATED 静态指定审批人时直接写 BPMN 原生 {@code flowable:assignee}
 *      （引擎插入任务时持久化，历史表 assignee 可查）；其余动态类型由 create 监听器运行期解析</li>
 * </ul>
 */
@Component
public class ApprovalUserTaskTranslator implements NodeTypeTranslator {

    private static final Logger log = LoggerFactory.getLogger(ApprovalUserTaskTranslator.class);

    private static final String APPROVER_CONFIG_ELEMENT = "approverConfig";

    /** Flowable 扩展命名空间（BPMN 2.0 XSD 允许 {@code flowable:*} 属性） */
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final String FLOWABLE_PREFIX = "flowable";

    /** Spring bean name of ApprovalTaskListener（用于 delegation expression） */
    private static final String TASK_LISTENER_BEAN = "approvalTaskListener";

    private final ObjectMapper objectMapper;

    public ApprovalUserTaskTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "APPROVAL";
    }

    @Override
    public BpmNodeMetadata metadata() {
        return new BpmNodeMetadata(
                "审批",
                "人工审批节点",
                "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(
                        new BpmNodeConfigField("name", "节点名称", "string", false, Map.of()),
                        new BpmNodeConfigField("approver", "审批人（兼容）", "object", false,
                                Map.of("approverTypes", List.of(NodeApproverType.DESIGNATED))),
                        new BpmNodeConfigField("participant", "参与人", "object", true,
                                Map.of("strategies", List.of(ParticipantStrategy.FIXED_USER,
                                        ParticipantStrategy.ROLE, ParticipantStrategy.EXPRESSION,
                                        ParticipantStrategy.ADAPTER))),
                        new BpmNodeConfigField("opinionForm", "审批意见表单", "object", false, Map.of()),
                        new BpmNodeConfigField("returnTargets", "可退回节点", "array", false, Map.of())),
                "2",
                EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                        BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false,
                false,
                false,
                true);
    }

    @Override
    public List<GraphValidationError> validateConfig(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null || (!config.containsKey("participant") && !config.containsKey("approver"))) {
            return List.of(configError(node, BpmErrorCode.APPROVER_CONFIG_MISSING,
                    "审批节点缺少 approver 配置"));
        }
        Object approverObj = config.containsKey("participant")
                ? config.get("participant") : config.get("approver");
        if (!(approverObj instanceof Map<?, ?> approverMap)) {
            return List.of(configError(node, BpmErrorCode.NODE_CONFIG_INVALID,
                    "审批人配置必须是对象"));
        }
        Object typeValue = approverMap.containsKey("strategy")
                ? approverMap.get("strategy") : approverMap.get("type");
        String approverType = typeValue == null ? null : String.valueOf(typeValue).trim();
        if (approverType == null || approverType.isBlank()) {
            return List.of(configError(node, BpmErrorCode.APPROVER_CONFIG_MISSING,
                    "审批人类型不能为空"));
        }
        if (config.containsKey("participant")) {
            if (!List.of(ParticipantStrategy.FIXED_USER, ParticipantStrategy.ROLE,
                    ParticipantStrategy.EXPRESSION, ParticipantStrategy.ADAPTER)
                    .contains(approverType.toUpperCase())) {
                return List.of(configError(node, BpmErrorCode.PARTICIPANT_TYPE_NOT_IMPLEMENTED,
                        "未实现的参与人策略: " + approverType));
            }
        if (ParticipantStrategy.ADAPTER.equalsIgnoreCase(approverType)
                    && (approverMap.get("adapterId") == null
                    || String.valueOf(approverMap.get("adapterId")).isBlank())) {
                return List.of(configError(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "参与人适配器标识不能为空"));
        }
        GraphValidationError strategyError = validateParticipantValue(node, approverType, approverMap.get("value"));
        if (strategyError != null) return List.of(strategyError);
        } else if (!NodeApproverType.DESIGNATED.equalsIgnoreCase(approverType)) {
            return List.of(configError(node, BpmErrorCode.APPROVER_TYPE_NOT_IMPLEMENTED,
                    "未实现的审批人类型: " + approverType));
        }
        Object value = approverMap.get("value");
        if (value == null || (value instanceof String text && text.isBlank())
                || (value instanceof Collection<?> collection
                && (collection.isEmpty() || collection.stream().allMatch(item -> item == null
                || item.toString().isBlank())))) {
            return List.of(configError(node, BpmErrorCode.APPROVER_RESOLVE_EMPTY,
                    "参与人配置值不能为空"));
        }
        return List.of();
    }

    private GraphValidationError validateParticipantValue(GraphElement node, String strategy, Object value) {
        if (ParticipantStrategy.FIXED_USER.equalsIgnoreCase(strategy)) {
            Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
            if (values.isEmpty() || values.stream().anyMatch(item -> {
                try { return Long.parseLong(String.valueOf(item)) <= 0; }
                catch (Exception e) { return true; }
            })) {
                return configError(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "FIXED_USER 只能配置正整数用户 ID");
            }
        } else if (ParticipantStrategy.ROLE.equalsIgnoreCase(strategy)) {
            Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
            if (values.isEmpty() || values.stream().anyMatch(item -> item == null || String.valueOf(item).isBlank())) {
                return configError(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "ROLE 必须配置非空角色编码");
            }
        } else if (ParticipantStrategy.EXPRESSION.equalsIgnoreCase(strategy)) {
            if (!(value instanceof String expression) || expression.isBlank()) {
                return configError(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "EXPRESSION 必须配置受控表达式");
            }
            try {
                RestrictedExpressionEvaluator.value(expression, Map.of());
            } catch (RuntimeException e) {
                return configError(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "EXPRESSION 语法不合法");
            }
        }
        return null;
    }

    private GraphValidationError configError(GraphElement node, BpmErrorCode errorCode, String message) {
        return GraphValidationError.builder()
                .elementId(node.getId())
                .errorCode(errorCode.getCode())
                .message(message)
                .build();
    }

    /**
     * 创建审批节点 UserTask。
     * <p>
     * assignee 不写死：不设值、不设 ${approver} 表达式。
     * 挂载 create 事件 TaskListener（delegation expression → Spring bean）。
     * 通过 {@link ExtensionAttribute} 写入 approverConfig（type + value JSON）。
     * </p>
     */
    @SuppressWarnings("unchecked")
    @Override
    public FlowElement translate(GraphElement node) {
        UserTask userTask = new UserTask();
        userTask.setId(node.getId());
        // 从 config 中读取 name，若无则用默认
        String nodeName = extractNodeName(node);
        userTask.setName(nodeName);

        // assignee 不写死（不设值，不走 ${approver} 表达式）
        // assignee 留空，由 TaskListener 在 create 事件中设置

        // 1. 挂载 create 事件 TaskListener（delegation expression → Spring bean）
        //    使用 delegation expression 而非 class 类型，确保 Spring Boot 下
        //    Flowable 通过 Spring 表达式管理器将 ${approvalTaskListener} 解析为
        //    ApprovalTaskListener Spring bean（带完整依赖注入）。
        FlowableListener listener = new FlowableListener();
        listener.setEvent("create");
        listener.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        listener.setImplementation("${" + TASK_LISTENER_BEAN + "}");
        List<FlowableListener> taskListeners = userTask.getTaskListeners();
        if (taskListeners == null) {
            taskListeners = new ArrayList<>();
            userTask.setTaskListeners(taskListeners);
        }
        taskListeners.add(listener);

        // 2. 写入统一 participantConfig；旧 approverConfig 继续保留兼容语义
        Map<String, Object> config = node.getConfig();
        if (config != null && (config.containsKey("participant") || config.containsKey("approver"))) {
            boolean unified = config.containsKey("participant");
            Object approverObj = unified ? config.get("participant") : config.get("approver");
            // DESIGNATED 静态指定审批人直接翻译为 BPMN 原生 flowable:assignee：
            // create 监听器内 setAssignee 不落 HI_ACTINST/HI_TASKINST（集成探针证实，
            // 监控流转记录审批人显示 "-"），原生属性由引擎在任务插入时持久化，历史表可查。
            if (approverObj instanceof Map<?, ?> approverMap
                    && ("DESIGNATED".equalsIgnoreCase(String.valueOf(approverMap.get("type")))
                    || ParticipantStrategy.FIXED_USER.equalsIgnoreCase(String.valueOf(approverMap.get("strategy"))))) {
                String designated = firstDesignatedUser(approverMap.get("value"));
                if (designated != null && !designated.isBlank()
                        && isSingleValue(approverMap.get("value"))) {
                    userTask.setAssignee(designated);
                }
            }
            try {
                String approverJson = objectMapper.writeValueAsString(approverObj);
                ExtensionAttribute attr = new ExtensionAttribute(
                        unified ? "participantConfig" : APPROVER_CONFIG_ELEMENT, approverJson);
                attr.setNamespace(FLOWABLE_NS);
                attr.setNamespacePrefix(FLOWABLE_PREFIX);
                userTask.addAttribute(attr);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize approver config for node {}: {}",
                        node.getId(), e.getMessage());
            }
            try {
                ExtensionAttribute nodeConfig = new ExtensionAttribute(
                        "nodeConfig", objectMapper.writeValueAsString(config));
                nodeConfig.setNamespace(FLOWABLE_NS);
                nodeConfig.setNamespacePrefix(FLOWABLE_PREFIX);
                userTask.addAttribute(nodeConfig);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("节点配置序列化失败", e);
            }
        }

        return userTask;
    }

    /**
     * 从 DESIGNATED value（字符串标量或字符串集合）提取首个用户 ID。
     */
    private String firstDesignatedUser(Object value) {
        if (value instanceof Collection<?> col) {
            return col.isEmpty() ? null : String.valueOf(col.iterator().next());
        }
        if (value != null && !(value instanceof Map)) {
            return String.valueOf(value);
        }
        return null;
    }

    private boolean isSingleValue(Object value) {
        return !(value instanceof Collection<?> collection) || collection.size() == 1;
    }

    /**
     * 从节点配置中提取名称。
     */
    private String extractNodeName(GraphElement node) {
        if (node.getConfig() != null && node.getConfig().containsKey("name")) {
            Object nameObj = node.getConfig().get("name");
            if (nameObj instanceof String name && !name.isBlank()) {
                return name;
            }
        }
        return "审批";
    }
}
