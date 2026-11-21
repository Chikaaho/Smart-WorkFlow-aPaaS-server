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
            merged = applyOutputs(action, result, merged);
        }
        return merged;
    }

    /** 按钮动作：执行指定动作并返回回填字段值（field → value）；失败抛业务异常
     *  （由前端提示，不清空已填内容）。 */
    public Map<String, String> runButton(FormDefEntity formDef, String actionId,
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
        for (var mapping : action.outputMappings()) {
            String value = result.outputs() == null ? null : result.outputs().get(mapping.source());
            if (value != null) {
                outputs.put(mapping.field(), value);
            }
        }
        return outputs;
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
