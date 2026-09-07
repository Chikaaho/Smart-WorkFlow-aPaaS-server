package com.sw.ck.bpm.engine.delegate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.common.exception.BaseException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;

import java.util.LinkedHashMap;
import java.util.Map;

abstract class NodeDelegateSupport {
    protected static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    protected final RepositoryService repositoryService;
    protected final ObjectMapper objectMapper;
    protected final ParticipantResolverRegistry participantResolverRegistry;

    NodeDelegateSupport(RepositoryService repositoryService, ObjectMapper objectMapper,
                        ParticipantResolverRegistry participantResolverRegistry) {
        this.repositoryService = repositoryService;
        this.objectMapper = objectMapper;
        this.participantResolverRegistry = participantResolverRegistry;
    }

    protected Map<String, Object> nodeConfig(DelegateExecution execution) {
        BpmnModel model = repositoryService.getBpmnModel(execution.getProcessDefinitionId());
        FlowElement element = model == null ? null : model.getFlowElement(execution.getCurrentActivityId());
        String json = element == null ? null : element.getAttributeValue(FLOWABLE_NS, "nodeConfig");
        if ((json == null || json.isBlank()) && element instanceof ServiceTask serviceTask) {
            json = serviceTask.getFieldExtensions().stream()
                    .filter(field -> "nodeConfig".equals(field.getFieldName()))
                    .map(FieldExtension::getStringValue)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
        }
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            throw new BaseException(BpmErrorCode.NODE_CONFIG_INVALID.getCode(), "节点配置解析失败");
        }
    }

    protected NodeParticipantContext participantContext(DelegateExecution execution, Map<String, Object> config) {
        Object raw = config.get("participant");
        if (!(raw instanceof Map<?, ?> participant)) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID);
        }
        String strategy = String.valueOf(participant.get("strategy"));
        Object value = participant.get("value");
        Map<String, Object> variables = new LinkedHashMap<>(execution.getVariables());
        Long initiator = parseLong(execution.getVariable("submitter"));
        variables.putIfAbsent("initiator", initiator);
        variables.putIfAbsent("initiatorId", initiator);
        return NodeParticipantContext.builder()
                .tenantId(parseLong(execution.getVariable("tenantId")))
                .processInstanceId(execution.getProcessInstanceId())
                .nodeKey(execution.getCurrentActivityId())
                .businessKey(asString(execution.getVariable("recordId")))
                .formKey(asString(execution.getVariable("formKey")))
                .initiatorUserId(initiator)
                .variables(variables)
                .strategy(strategy)
                .strategyValue(value)
                .adapterId(participant.get("adapterId") == null ? null
                        : String.valueOf(participant.get("adapterId")))
                .build();
    }

    protected String asString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    protected Long parseLong(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    protected boolean shouldBlock(Map<String, Object> config) {
        return !"CONTINUE".equalsIgnoreCase(String.valueOf(config.getOrDefault("failureStrategy", "BLOCK")));
    }
}
