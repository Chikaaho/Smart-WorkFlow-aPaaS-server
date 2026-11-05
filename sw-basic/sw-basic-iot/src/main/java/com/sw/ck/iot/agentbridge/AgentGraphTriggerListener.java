package com.sw.ck.iot.agentbridge;

import com.alibaba.fastjson2.JSON;
import com.sw.ck.agent.service.AgentGraphDefService;
import com.sw.ck.agent.service.AgentGraphExecutionLifecycleService;
import com.sw.ck.common.tenant.TenantWorkAdmission;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * IoT 规则 → Agent 图执行桥（0.2.4）。
 * <p>
 * 消费 {@link IotProcessTriggerEvent} 的 AGENT 分支：按规则配置的已发布图 key
 * 解析图定义，以触发记录幂等键提交图执行（生命周期服务幂等受理），
 * 并把执行请求 id 回写触发记录 {@code agent_execution_id}（事件↔研判实例互查）。
 * 图缺失/未发布或受理失败按既有触发失败路径回写，交由恢复调度重试。
 * 执行结果由 {@link AgentExecutionOutcomeListener} 承接（失败升级）。
 * </p>
 */
@Component
public class AgentGraphTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphTriggerListener.class);

    private final ObjectProvider<AgentGraphDefService> graphDefServiceProvider;
    private final ObjectProvider<AgentGraphExecutionLifecycleService> lifecycleServiceProvider;
    private final IotProcessTriggerMapper triggerMapper;
    private final TenantWorkAdmission tenantWorkAdmission;

    public AgentGraphTriggerListener(ObjectProvider<AgentGraphDefService> graphDefServiceProvider,
                                     ObjectProvider<AgentGraphExecutionLifecycleService> lifecycleServiceProvider,
                                     IotProcessTriggerMapper triggerMapper,
                                     TenantWorkAdmission tenantWorkAdmission) {
        this.graphDefServiceProvider = graphDefServiceProvider;
        this.lifecycleServiceProvider = lifecycleServiceProvider;
        this.triggerMapper = triggerMapper;
        this.tenantWorkAdmission = tenantWorkAdmission;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIotTrigger(IotProcessTriggerEvent event) {
        if (event == null || !"AGENT".equals(event.getActionType())) {
            return;
        }
        if (event.getTenantId() == null
                || tenantWorkAdmission.claim(event.getTenantId(), () -> 1) == 0) {
            log.info("Agent 触发暂停：租户未启用、已过期或缺失: tenantId={}, idempotentKey={}",
                    event.getTenantId(), event.getIdempotentKey());
            return;
        }
        // 异步线程无登录态：按事件还原租户上下文（与流程发起监听器同一口径），
        // 图解析的租户过滤与生命周期受理的身份要求都依赖它
        LoginUser loginUser = new LoginUser();
        loginUser.setTenantId(event.getTenantId());
        loginUser.setUserId(event.getConfiguredBy());
        LoginUserHolder.set(loginUser);
        try {
            submitAgentExecution(event);
        } catch (Exception e) {
            log.error("Agent 触发提交失败: graphKey={}, key={}, error={}",
                    event.getAgentGraphKey(), event.getIdempotentKey(), e.getMessage(), e);
            markFailed(event, e.getMessage() == null ? "Agent 执行提交失败" : e.getMessage());
        } finally {
            LoginUserHolder.clear();
        }
    }

    private void submitAgentExecution(IotProcessTriggerEvent event) {
        AgentGraphDefService graphDefService = graphDefServiceProvider.getIfAvailable();
        AgentGraphExecutionLifecycleService lifecycleService = lifecycleServiceProvider.getIfAvailable();
        if (graphDefService == null || lifecycleService == null) {
            markFailed(event, "Agent 图运行时未装配");
            return;
        }
        Long graphDefId = graphDefService.resolvePublishedGraphId(event.getAgentGraphKey())
                .orElse(null);
        if (graphDefId == null) {
            markFailed(event, "Agent 图不存在或未发布: " + event.getAgentGraphKey());
            return;
        }
        String input = event.getAgentInput() == null
                ? "{}" : JSON.toJSONString(event.getAgentInput());
        // 幂等键与触发记录一一对应：恢复调度重投同一触发时，生命周期按
        // (tenant, createBy, idempotencyKey) 返回既有请求，不重复执行
        com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO accepted =
                lifecycleService.submit(graphDefId, input, event.getIdempotentKey());
        IotProcessTrigger patch = new IotProcessTrigger();
        patch.setId(event.getTriggerId());
        patch.setAgentExecutionId(accepted.getExecutionId());
        patch.setStatus("PROCESSING");
        int updated = triggerMapper.update(patch,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotProcessTrigger>lambdaUpdate()
                        .eq(IotProcessTrigger::getId, event.getTriggerId())
                        .eq(IotProcessTrigger::getStatus, "PENDING"));
        log.info("IoT 触发 Agent 执行已受理: graphKey={}, executionId={}, triggerId={}, 首次写回={}",
                event.getAgentGraphKey(), accepted.getExecutionId(), event.getTriggerId(), updated == 1);
    }

    private void markFailed(IotProcessTriggerEvent event, String error) {
        String safe = error.length() <= 480 ? error : error.substring(0, 480);
        IotProcessTrigger patch = new IotProcessTrigger();
        patch.setId(event.getTriggerId());
        patch.setStatus("FAILED");
        patch.setError(safe);
        triggerMapper.update(patch,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotProcessTrigger>lambdaUpdate()
                        .eq(IotProcessTrigger::getId, event.getTriggerId())
                        .eq(IotProcessTrigger::getStatus, "PENDING"));
    }
}
