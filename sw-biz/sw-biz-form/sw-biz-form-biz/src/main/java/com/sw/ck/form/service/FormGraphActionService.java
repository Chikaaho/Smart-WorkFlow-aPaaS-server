package com.sw.ck.form.service;

import com.sw.ck.agent.api.port.AgentGraphBusinessSources;
import com.sw.ck.agent.api.port.AgentGraphInvocation;
import com.sw.ck.agent.api.port.AgentGraphInvocationResult;
import com.sw.ck.agent.api.port.AgentGraphInvokePort;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.service.FormGraphActionRules.GraphAction;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.log4j.Log4j2;

/**
 * 表单「调用 Graph」服务端动作执行（1.2.0 P67 / E1-6）。
 *
 * <p>触发时机两种：提交前（BEFORE_SUBMIT，失败阻止提交并提示原因，成功出参
 * 回填进提交载荷随记录落库）与按钮（BUTTON，运行页按钮触发，成功出参返回
 * 前端回填字段，失败只提示、不清空已填内容）。入参来自表单字段，出参回填到
 * 字段；全程服务端经 {@link AgentGraphInvokePort} 执行，不在前端脚本解释器内
 * 调用。身份：系统身份（userId=0、username=form-graph-action）按当前租户，
 * 与流程侧 Graph 执行器同一口径；执行请求写业务关联（来源 FORM + 表单记录 id
 * + 动作 id——E1-7）。</p>
 */
@Service
@Log4j2
public class FormGraphActionService {

    private static final long SYSTEM_OPERATOR_ID = 0L;
    private static final String SYSTEM_USERNAME = "form-graph-action";

    @Autowired(required = false)
    private ObjectProvider<AgentGraphInvokePort> graphInvokePortProvider;
    @Autowired
    private FormGraphActionRules graphActionRules;
    @Autowired
    private FormFieldValidator formFieldValidator;

    /** 提交前动作：依次执行全部 BEFORE_SUBMIT 动作，失败抛业务异常阻止提交；
     *  成功把出参按映射回填进提交载荷（字段 ← 图输出，未映射输出忽略）。 */
    public Map<String, Object> runBeforeSubmit(FormDefEntity formDef, Map<String, Object> effectiveData) {
        List<GraphAction> actions = graphActionRules.parse(formDef).stream()
                .filter(action -> FormGraphActionRules.TRIGGER_BEFORE_SUBMIT.equals(action.trigger()))
                .toList();
        Map<String, Object> merged = effectiveData;
        for (GraphAction action : actions) {
            AgentGraphInvocationResult result = execute(formDef.getFormKey(), action, merged, null);
            if (!result.success()) {
                throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                        "提交前 Graph 动作失败[" + result.errorCategory() + "]: " + result.errorMessage());
            }
            // planning-review-120-01 F1-type：输出值类型与目标字段不符时阻止提交并点名字段，
            // 不把不合法的值写进记录
            List<String> bad = typeConflicts(formDef, action, result);
            if (!bad.isEmpty()) {
                throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                        "Graph 动作输出类型不符，已阻止提交（动作 " + action.name() + "）: "
                                + String.join("; ", bad));
            }
            merged = applyOutputs(action, result, merged);
        }
        return merged;
    }

    /** 按钮动作结果：出参回填映射 + 类型不符被拦下的字段原因（不回填）。 */
    public record ButtonResult(Map<String, String> outputs, List<String> warnings) {
    }

    /** 按钮动作：执行指定动作并返回回填字段值（field → value）；失败抛业务异常
     *  （由前端提示，不清空已填内容）。 */
    public ButtonResult runButton(FormDefEntity formDef, String actionId,
                                          Long recordId, Map<String, Object> fieldValues) {
        GraphAction action = graphActionRules.parse(formDef).stream()
                .filter(candidate -> candidate.id().equals(actionId)
                        && FormGraphActionRules.TRIGGER_BUTTON.equals(candidate.trigger()))
                .findFirst()
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND,
                        "Graph 动作不存在: " + actionId));
        AgentGraphInvocationResult result = execute(formDef.getFormKey(), action, fieldValues, recordId);
        if (!result.success()) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                    "Graph 动作失败[" + result.errorCategory() + "]: " + result.errorMessage());
        }
        Map<String, String> outputs = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (var mapping : action.outputMappings()) {
            String value = result.outputs() == null ? null : result.outputs().get(mapping.source());
            if (value == null) {
                continue;
            }
            // F1-type：类型不符的值不回填，提示原因（已填内容不受影响）
            String conflict = typeConflictReason(formDef, mapping.field(), value);
            if (conflict != null) {
                warnings.add(conflict);
                continue;
            }
            outputs.put(mapping.field(), value);
        }
        return new ButtonResult(outputs, warnings);
    }

    /**
     * F1-type：全部出参映射的类型校验（提交前用）——返回「字段: 原因」列表，空=全部合法。
     */
    private List<String> typeConflicts(FormDefEntity formDef, GraphAction action,
                                        AgentGraphInvocationResult result) {
        List<String> bad = new ArrayList<>();
        for (var mapping : action.outputMappings()) {
            String value = result.outputs() == null ? null : result.outputs().get(mapping.source());
            if (value == null) {
                continue;
            }
            String reason = typeConflictReason(formDef, mapping.field(), value);
            if (reason != null) {
                bad.add(reason);
            }
        }
        return bad;
    }

    /**
     * 单字段类型校验：图输出是字符串，按目标字段声明类型做最基本类型检查
     * （NUMBER 须可解析为数值、BOOL 须为 true/false/1/0；其余类型不校验，走既有字段校验）。
     * 合法返回 null，不合法返回「字段label(name): 原因」。
     */
    private String typeConflictReason(FormDefEntity formDef, String fieldName, String value) {
        Map<String, FormFieldValidator.FieldDef> fieldDefs =
                formFieldValidator.loadAndParseFieldDefs(formDef.getId(), Map.of());
        FormFieldValidator.FieldDef def = fieldDefs.get(fieldName);
        if (def == null) {
            return fieldName + ": 字段不存在";
        }
        String type = def.type() == null ? "" : def.type().toUpperCase();
        String trimmed = value.trim();
        switch (type) {
            case "NUMBER":
                try {
                    new java.math.BigDecimal(trimmed);
                } catch (NumberFormatException e) {
                    return def.label() + "(" + fieldName + "): 数字字段收到非数值 '" + trimmed + "'";
                }
                break;
            case "BOOL":
                if (!trimmed.equalsIgnoreCase("true") && !trimmed.equalsIgnoreCase("false")
                        && !trimmed.equals("1") && !trimmed.equals("0")) {
                    return def.label() + "(" + fieldName + "): 布尔字段收到 '" + trimmed + "'";
                }
                break;
            default:
                break;
        }
        return null;
    }

    private Map<String, Object> applyOutputs(GraphAction action, AgentGraphInvocationResult result,
                                             Map<String, Object> data) {
        Map<String, Object> merged = new LinkedHashMap<>(data);
        for (var mapping : action.outputMappings()) {
            String value = result.outputs() == null ? null : result.outputs().get(mapping.source());
            if (value != null) {
                merged.put(mapping.field(), value);
            }
        }
        return merged;
    }

    private AgentGraphInvocationResult execute(String formKey, GraphAction action,
                                                Map<String, Object> fieldValues, Long recordId) {
        AgentGraphInvokePort port = graphInvokePortProvider == null
                ? null : graphInvokePortProvider.getIfAvailable();
        if (port == null) {
            throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                    "智能体模块未装配，Graph 动作不可用: " + action.name());
        }
        LoginUser previous = LoginUserHolder.get();
        if (previous == null || previous.getTenantId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "Graph 动作缺少租户上下文");
        }
        LoginUser system = new LoginUser();
        system.setUserId(SYSTEM_OPERATOR_ID);
        system.setTenantId(previous.getTenantId());
        system.setUsername(SYSTEM_USERNAME);
        LoginUserHolder.set(system);
        try {
            Map<String, String> inputs = new LinkedHashMap<>();
            for (var mapping : action.inputMappings()) {
                Object value = fieldValues == null ? null : fieldValues.get(mapping.field());
                if (value == null || String.valueOf(value).isBlank()) {
                    throw new BaseException(FormErrorCode.SUBMIT_FAILED,
                            "Graph 动作入参字段缺失或为空: " + mapping.field()
                                    + "（动作 " + action.name() + "）");
                }
                inputs.put(mapping.name(), String.valueOf(value));
            }
            String idempotencyKey = recordId == null ? null
                    : "form-graph:" + formKey + ":" + action.id() + ":" + recordId;
            return port.invoke(new AgentGraphInvocation(action.graphDefId(), action.graphDefVersion(),
                    inputs, action.timeoutSeconds(), AgentGraphBusinessSources.FORM,
                    null, action.id(), recordId, idempotencyKey));
        } finally {
            LoginUserHolder.set(previous);
        }
    }
}
