package com.sw.ck.bpm.engine.delegate;

import com.sw.ck.agent.api.port.AgentGraphBusinessSources;
import com.sw.ck.agent.api.port.AgentGraphInvocation;
import com.sw.ck.agent.api.port.AgentGraphInvocationResult;
import com.sw.ck.agent.api.port.AgentGraphInvokePort;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import lombok.extern.log4j.Log4j2;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graph 执行器节点委托（1.2.0 P67 / E1-3、E1-4）。
 *
 * <p>同步执行（不设 async）：在流程推进线程内以<b>系统身份</b>（userId=0、
 * username=bpm-graph-exec）按<b>实例租户</b>调用已发布 Graph 的固定版本，等到结束
 * 再往下走；出参写回流程变量后，后续条件分支可直接判断。受理复用执行生命周期
 * （幂等键 {@code bpm-graph:{processInstanceId}:{activityId}}，重试/恢复不重复执行），
 * 并写入业务关联列（E1-7：来源 PROCESS + 实例 id + 节点 id）。</p>
 *
 * <p>失败语义（E1-4 三种情况：超时 / Graph 报错 / 出参缺必需字段）：原因统一写入
 * {@code graphExec.{activityId}.*} 流程变量；{@code failureStrategy=CONTINUE} 时记录并
 * 推进（「异常分支」＝下游条件节点按 status/errorCategory 分流），默认 BLOCK 抛业务
 * 异常使节点失败、实例可见原因。图运行进入人工等待按失败处理（发布期已拒绝该类图，
 * 此处为运行期兜底）。</p>
 */
@Component("graphExecNodeDelegate")
@Log4j2
public class GraphExecNodeDelegate extends NodeDelegateSupport implements JavaDelegate {

    private static final long SYSTEM_OPERATOR_ID = 0L;
    private static final String SYSTEM_USERNAME = "bpm-graph-exec";

    @Autowired(required = false)
    private ObjectProvider<AgentGraphInvokePort> graphInvokePortProvider;

    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Object> config = nodeConfig(execution);
        Long graphDefId = positiveLong(config.get("graphDefId"));
        Integer defVersion = positiveInt(config.get("graphDefVersion"));
        if (graphDefId == null || defVersion == null) {
            throw new BaseException(BpmErrorCode.NODE_CONFIG_INVALID.getCode(),
                    "Graph 执行器节点必须绑定图与固定版本（graphDefId/graphDefVersion）");
        }
        Long tenantId = parseLong(execution.getVariable("tenantId"));
        if (tenantId == null) {
            throw new BaseException(BpmErrorCode.NODE_DELIVERY_FAILED.getCode(),
                    "Graph 执行器节点缺少租户上下文（tenantId 流程变量缺失）");
        }

        // E1-3：系统身份 + 实例租户（不伪装发起人；Graph 内工具/知识库按租户隔离，
        // 与 IoT 桥同一口径）。同步调用在推进线程内完成，结束后恢复原身份。
        LoginUser previous = LoginUserHolder.get();
        LoginUser system = new LoginUser();
        system.setUserId(SYSTEM_OPERATOR_ID);
        system.setTenantId(tenantId);
        system.setUsername(SYSTEM_USERNAME);
        LoginUserHolder.set(system);
        try {
            invokeGraph(execution, config, graphDefId, defVersion);
        } finally {
            if (previous != null) {
                LoginUserHolder.set(previous);
            } else {
                LoginUserHolder.clear();
            }
        }
    }

    private void invokeGraph(DelegateExecution execution, Map<String, Object> config,
                             Long graphDefId, Integer defVersion) {
        AgentGraphInvokePort port = graphInvokePortProvider.getIfAvailable();
        if (port == null) {
            throw new BaseException(BpmErrorCode.NODE_DELIVERY_FAILED.getCode(),
                    "智能体模块未装配，Graph 执行器节点无法执行");
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Map<String, Object> mapping : mappings(config.get("inputMappings"))) {
            String name = asString(mapping.get("name"));
            String source = asString(mapping.get("source"));
            if (name == null || source == null) {
                continue;
            }
            // E1-1 语义：流程变量或表单字段都可映射——顶层变量优先，回退 formData（发起时
            // 表单数据以 formData map 写入实例变量，字段值不在顶层）
            Object value = execution.getVariable(source);
            if (value == null) {
                Object formData = execution.getVariable("formData");
                if (formData instanceof Map<?, ?> formMap) {
                    value = formMap.get(source);
                }
            }
            if (value == null) {
                throw new BaseException(BpmErrorCode.NODE_CONFIG_INVALID.getCode(),
                        "Graph 入参映射引用了缺失的流程变量: " + source);
            }
            inputs.put(name, String.valueOf(value));
        }
        String activityId = execution.getCurrentActivityId();
        String idempotencyKey = "bpm-graph:" + execution.getProcessInstanceId() + ":" + activityId;
        AgentGraphInvocationResult result = port.invoke(new AgentGraphInvocation(
                graphDefId, defVersion, inputs, positiveInt(config.get("timeoutSeconds")),
                AgentGraphBusinessSources.PROCESS, execution.getProcessInstanceId(), activityId,
                null, idempotencyKey));

        execution.setVariable(var(activityId, "executionId"), result.executionId());
        execution.setVariable(var(activityId, "status"), result.success() ? "SUCCESS" : "FAILED");
        execution.setVariable(var(activityId, "latencyMs"), result.latencyMs());
        if (result.success()) {
            for (Map<String, Object> mapping : mappings(config.get("outputMappings"))) {
                String target = asString(mapping.get("name"));
                String source = asString(mapping.get("source"));
                if (target == null || source == null) {
                    continue;
                }
                String value = result.outputs() == null ? null : result.outputs().get(source);
                if (value != null) {
                    execution.setVariable(target, value);
                }
            }
            log.info("Graph 执行器节点成功: processInstance={}, activity={}, graph=#{} v{}, executionId={}, latency={}ms",
                    execution.getProcessInstanceId(), activityId, graphDefId, defVersion,
                    result.executionId(), result.latencyMs());
            return;
        }

        // E1-4：超时 / Graph 报错 / 出参缺必需字段（含运行期人工等待兜底）统一失败路径
        String reason = "Graph 执行失败[" + result.errorCategory() + "]: " + result.errorMessage();
        execution.setVariable(var(activityId, "errorCategory"), result.errorCategory());
        execution.setVariable(var(activityId, "errorMessage"), reason);
        if (blockOnFailure(config)) {
            throw new BaseException(BpmErrorCode.NODE_DELIVERY_FAILED.getCode(), reason);
        }
        log.warn("Graph 执行器节点按 CONTINUE 策略记录失败并推进: processInstance={}, activity={}, reason={}",
                execution.getProcessInstanceId(), activityId, reason);
    }

    /** 失败语义（E1-4）：默认 BLOCK——节点失败、实例可见原因；显式 CONTINUE 走「异常分支」。 */
    private boolean blockOnFailure(Map<String, Object> config) {
        Object strategy = config.get("failureStrategy");
        return strategy == null || "BLOCK".equalsIgnoreCase(String.valueOf(strategy));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mappings(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private Long positiveLong(Object value) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer positiveInt(Object value) {
        if (value == null) return null;
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String var(String activityId, String field) {
        return "graphExec." + activityId + "." + field;
    }
}
