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
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.ServiceTask;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Optional;

/** P58 服务节点翻译公共基类：配置只作为 BPMN 扩展属性传给受控委托。 */
abstract class ServiceTaskNodeTranslator implements NodeTypeTranslator {

    protected final ObjectMapper objectMapper;

    ServiceTaskNodeTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected abstract String delegateBean();

    protected abstract String nodeName();

    @Override
    public Optional<BpmNodeMetadata> metadata() {
        return Optional.of(new BpmNodeMetadata(nodeName(), nodeName() + "节点", "TASK",
                new BpmNodeTopology(1, 1, 1, 1),
                List.of(new BpmNodeConfigField("name", "节点名称", "string", false, Map.of()),
                        new BpmNodeConfigField("participant", "参与人", "object", true, Map.of()),
                        new BpmNodeConfigField("failureStrategy", "失败策略", "string", false,
                                Map.of("values", List.of("BLOCK", "CONTINUE")))),
                "1", EnumSet.of(BpmNodeCapability.DESIGN, BpmNodeCapability.TRANSLATE,
                        BpmNodeCapability.RUNTIME, BpmNodeCapability.CONFIG_VALIDATE),
                false, false, false, true));
    }

    @Override
    public Optional<List<GraphValidationError>> validateConfig(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null || !(config.get("participant") instanceof Map<?, ?> participant)) {
            return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                    "节点缺少 participant 配置")));
        }
        Object strategy = participant.get("strategy");
        Object type = participant.get("type");
        String selected = strategy == null ? (type == null ? null : String.valueOf(type))
                : String.valueOf(strategy);
        if (selected == null || selected.isBlank()) {
            return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                    "参与人策略不能为空")));
        }
        if (!ParticipantStrategy.ALL.contains(selected.toUpperCase())) {
            return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_TYPE_NOT_IMPLEMENTED,
                    "未实现的参与人策略: " + selected)));
        }
        if (participant.get("value") == null && !"ADAPTER".equalsIgnoreCase(selected)) {
            return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                    "参与人值不能为空")));
        }
        if ("ADAPTER".equalsIgnoreCase(selected)
                && (participant.get("adapterId") == null
                || String.valueOf(participant.get("adapterId")).isBlank())) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "适配器标识不能为空")));
        }
        if (ParticipantStrategy.DEPT_LEADER.equalsIgnoreCase(selected)) {
            Collection<?> values = participant.get("value") instanceof Collection<?> collection
                    ? collection : List.of(participant.get("value"));
            if (values.isEmpty() || values.stream().anyMatch(item -> {
                try { return Long.parseLong(String.valueOf(item)) <= 0; }
                catch (Exception e) { return true; }
            })) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "DEPT_LEADER 只能配置正整数部门 ID")));
            }
        } else if (ParticipantStrategy.POST.equalsIgnoreCase(selected)) {
            Collection<?> values = participant.get("value") instanceof Collection<?> collection
                    ? collection : List.of(participant.get("value"));
            if (values.isEmpty() || values.stream().anyMatch(item -> item == null || String.valueOf(item).isBlank())) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "POST 必须配置非空岗位编码")));
            }
        } else if (ParticipantStrategy.DEPT_POST.equalsIgnoreCase(selected)) {
            if (!(participant.get("value") instanceof java.util.Map<?, ?> mapping)
                    || mapping.get("deptId") == null
                    || mapping.get("postCode") == null
                    || String.valueOf(mapping.get("postCode")).isBlank()) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "DEPT_POST 必须配置 {deptId, postCode}")));
            }
        } else if (ParticipantStrategy.FIXED_USER.equalsIgnoreCase(selected)) {
            Collection<?> values = participant.get("value") instanceof Collection<?> collection
                    ? collection : List.of(participant.get("value"));
            if (values.isEmpty() || values.stream().anyMatch(item -> {
                try { return Long.parseLong(String.valueOf(item)) <= 0; }
                catch (Exception e) { return true; }
            })) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "FIXED_USER 只能配置正整数用户 ID")));
            }
        } else if (ParticipantStrategy.ROLE.equalsIgnoreCase(selected)) {
            Collection<?> values = participant.get("value") instanceof Collection<?> collection
                    ? collection : List.of(participant.get("value"));
            if (values.isEmpty() || values.stream().anyMatch(item -> item == null || String.valueOf(item).isBlank())) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "ROLE 必须配置非空角色编码")));
            }
        } else if (ParticipantStrategy.EXPRESSION.equalsIgnoreCase(selected)) {
            Object expression = participant.get("value");
            if (!(expression instanceof String text) || text.isBlank()) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "EXPRESSION 必须配置受控表达式")));
            }
            try {
                // 仅做语法校验：结果按新契约返回 Optional，本处不消费求值结果
                RestrictedExpressionEvaluator.value(text, Map.of());
            } catch (RuntimeException e) {
                return Optional.of(List.of(error(node, BpmErrorCode.PARTICIPANT_CONFIG_INVALID,
                        "EXPRESSION 语法不合法")));
            }
        }
        return Optional.of(List.of());
    }

    @Override
    public FlowElement translate(GraphElement node) {
        ServiceTask task = new ServiceTask();
        task.setId(node.getId());
        task.setName(node.getConfig() != null && node.getConfig().get("name") != null
                ? String.valueOf(node.getConfig().get("name")) : nodeName());
        task.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        task.setImplementation("${" + delegateBean() + "}");
        if (node.getConfig() != null) {
            try {
                FieldExtension field = new FieldExtension();
                field.setFieldName("nodeConfig");
                field.setStringValue(objectMapper.writeValueAsString(node.getConfig()));
                task.getFieldExtensions().add(field);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("节点配置序列化失败", e);
            }
        }
        return task;
    }

    private GraphValidationError error(GraphElement node, BpmErrorCode code, String message) {
        return GraphValidationError.builder().elementId(node.getId())
                .errorCode(code.getCode()).message(message).build();
    }
}
