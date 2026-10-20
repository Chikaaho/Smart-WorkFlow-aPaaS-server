package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.ParticipantScriptResolvePort;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.common.exception.BaseException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 审批人表达式脚本解析（流程表达式＝计算值适配器，脚本以 {@code return} 返回参与人）。
 * <p>
 * 与触发器/动作映射脚本共用同一执行引擎（GraalJS + {@code #} 函数语法糖）与同一变量快照口径：
 * 实例已声明的流程变量（含主表单字段来源）按图变量定义解析后作为只读快照传入，组织函数由
 * {@link BpmScriptFunctionContextFactory} 提供真实部门目录。因此表达式可直接写
 * {@code const depts = #获取流程变量('var_x'); ... return [...]}。
 * </p>
 */
@Service
public class ParticipantScriptResolveService implements ParticipantScriptResolvePort {

    private final BpmScriptEvaluatePort scriptEvaluatePort;
    private final ObjectProvider<BpmScriptFunctionContextFactory> contextFactoryProvider;
    private final BpmInstanceService instanceService;
    private final NodeFormDataService nodeFormDataService;
    private final BpmVariableSnapshotService variableSnapshotService;

    @Value("${sw.bpm.script.timeout-ms:5000}")
    private long timeoutMs = BpmScriptEvaluatePort.DEFAULT_TIMEOUT_MS;

    public ParticipantScriptResolveService(
            BpmScriptEvaluatePort scriptEvaluatePort,
            ObjectProvider<BpmScriptFunctionContextFactory> contextFactoryProvider,
            BpmInstanceService instanceService,
            NodeFormDataService nodeFormDataService,
            BpmVariableSnapshotService variableSnapshotService) {
        this.scriptEvaluatePort = scriptEvaluatePort;
        this.contextFactoryProvider = contextFactoryProvider;
        this.instanceService = instanceService;
        this.nodeFormDataService = nodeFormDataService;
        this.variableSnapshotService = variableSnapshotService;
    }

    @Override
    public Optional<List<String>> resolve(Long tenantId, String processInstanceId,
                                          String script, Map<String, Object> variables) {
        if (script == null || script.isBlank()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID.getCode(),
                    "审批人表达式为空：请填写以 return 返回参与人的脚本");
        }
        Map<String, Object> snapshot = variableSnapshot(tenantId, processInstanceId, variables);
        BpmScriptFunctionContextFactory factory = contextFactoryProvider == null
                ? null : contextFactoryProvider.getIfAvailable();
        ScriptFunctionContext functionContext =
                factory == null || processInstanceId == null || tenantId == null
                        ? ScriptFunctionContext.EMPTY
                        : factory.build(script, processInstanceId, tenantId);
        BpmScriptEvaluatePort.ScriptOutcome outcome = scriptEvaluatePort
                .run(script, snapshot, timeoutMs, functionContext)
                .orElseThrow(() -> new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID.getCode(),
                        "审批人表达式脚本未返回结果"));
        if (!outcome.ok()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID.getCode(),
                    "审批人表达式脚本执行失败: " + outcome.errorMessage());
        }
        return Optional.of(normalize(outcome.value()));
    }

    /**
     * 变量快照：按图变量定义解析实例已声明变量（主表单字段来源等），再叠加调用方已知变量。
     * 实例/图不可用时退回调用方变量（如单元测试或预览场景）。
     */
    private Map<String, Object> variableSnapshot(Long tenantId, String processInstanceId,
                                                 Map<String, Object> variables) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (variables != null) {
            merged.putAll(variables);
        }
        if (tenantId == null || processInstanceId == null) {
            return merged;
        }
        BpmInstance instance = instanceService.findByProcessInstanceId(processInstanceId).orElse(null);
        if (instance == null) {
            return merged;
        }
        ProcessGraph graph = nodeFormDataService.loadGraph(
                instance.getProcessDefKey(), instance.getDefVersion());
        List<ProcessVariableDef> defs = graph == null ? null : graph.getVariables();
        if (defs == null || defs.isEmpty()) {
            return merged;
        }
        List<String> authorized = defs.stream().map(ProcessVariableDef::getVarId).toList();
        BpmVariableSnapshotService.SnapshotResult snapshot = variableSnapshotService.buildSnapshot(
                tenantId, instance, defs, authorized, null,
                nodeFormDataService.currentRound(processInstanceId));
        if (!snapshot.errors().isEmpty()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID.getCode(),
                    "审批人表达式变量解析失败: " + String.join("; ", snapshot.errors()));
        }
        Map<String, Object> resolved = new LinkedHashMap<>(snapshot.values());
        resolved.putAll(merged);
        return resolved;
    }

    /** 脚本返回值归一：标量→单元素列表；集合→去空去重字符串列表；null/空白→空列表。 */
    private List<String> normalize(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            Set<String> out = new LinkedHashSet<>();
            for (Object item : collection) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
            return new ArrayList<>(out);
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? List.of() : List.of(text);
    }
}
