package com.sw.ck.bpm.engine.delegate;

import com.sw.ck.bpm.api.expression.RestrictedExpressionEvaluator;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeActionAuditPort;
import com.sw.ck.common.exception.BaseException;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Flowable 条件表达式的受控函数出口；表达式正文以 Base64 传输，避免拼入可执行语法。 */
@Component("bpmBranchConditionEvaluator")
public class BpmBranchConditionEvaluator implements ExecutionListener {
    private final NodeActionAuditPort auditPort;

    public BpmBranchConditionEvaluator(ObjectProvider<NodeActionAuditPort> auditPort) {
        this.auditPort = auditPort.getIfAvailable();
    }

    public boolean matches(DelegateExecution execution, String encodedExpression) {
        return matches(execution, encodedExpression, null);
    }

    public boolean matches(DelegateExecution execution, String encodedExpression, String encodedBranchId) {
        return matches(execution, encodedExpression, encodedBranchId, null);
    }

    /**
     * 条件边的真实执行出口。priority 只作为审计输入，不参与表达式计算；表达式优先级由
     * GraphToBpmnTranslator 在生成 BPMN 时稳定排序。
     */
    public boolean matches(DelegateExecution execution, String encodedExpression,
                           String encodedBranchId, String priority) {
        String expression;
        try {
            expression = new String(Base64.getDecoder().decode(encodedExpression), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("分支条件编码非法", e);
        }
        try {
            return RestrictedExpressionEvaluator.matches(expression, execution.getVariables());
        } catch (IllegalArgumentException e) {
            throw new BaseException(BpmErrorCode.BRANCH_EVALUATION_FAILED.getCode(),
                    BpmErrorCode.BRANCH_EVALUATION_FAILED.getMessage() + ": " + e.getMessage());
        }
    }

    /**
     * Flowable 只会对最终被选中的 SequenceFlow 触发 take 监听器，因此默认路径也只落一条
     * 真实命中轨迹，不会在检查其他条件时产生误记录。
     */
    @Override
    public void notify(DelegateExecution execution) {
        FlowElement current = execution.getCurrentFlowElement();
        if (!(current instanceof SequenceFlow flow) || auditPort == null) return;
        String branchId = flow.getName() == null ? flow.getId() : flow.getName();
        String priority = flow.getAttributeValue("http://flowable.org/bpmn", "branchPriority");
        String expression = flow.getAttributeValue("http://flowable.org/bpmn", "branchExpression");
        String documentation = flow.getDocumentation();
        if (documentation != null && documentation.startsWith("P58_BRANCH_META|")) {
            String[] parts = documentation.split("\\|", 3);
            if (parts.length == 3) {
                priority = parts[1];
                try {
                    expression = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("分支审计元数据编码非法", e);
                }
            }
        }
        if (expression == null || expression.isBlank()) expression = "DEFAULT";
        auditPort.recordBranch(execution.getProcessInstanceId(), flow.getSourceRef(),
                branchId, "1", inputSummary(execution, expression, priority),
                parseLong(execution.getVariable("tenantId")));
    }

    /** 保留旧模型调用兼容性；新模型通过 SequenceFlow take listener 记录默认边。 */
    public boolean recordDefault(DelegateExecution execution, String encodedBranchId, String priority) {
        String branchId = decode(encodedBranchId, "分支标识");
        if (auditPort != null) {
            auditPort.recordBranch(execution.getProcessInstanceId(), execution.getCurrentActivityId(),
                    branchId, "1", inputSummary(execution, "DEFAULT", priority),
                    parseLong(execution.getVariable("tenantId")));
        }
        return true;
    }

    private Map<String, Object> inputSummary(DelegateExecution execution, String expression,
                                               String priority) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("expression", expression);
        if (priority != null && !priority.isBlank()) summary.put("priority", priority);
        Object formData = execution.getVariable("formData");
        if (formData instanceof Map<?, ?> values) {
            Map<String, Object> safe = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key != null && value != null && isSafeScalar(value)) {
                    safe.put(String.valueOf(key), value);
                }
            });
            summary.put("formData", safe);
        }
        return summary;
    }

    private boolean isSafeScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character;
    }

    private String decode(String encoded, String label) {
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(label + "编码非法", e);
        }
    }

    private Long parseLong(Object value) {
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }
}
