package com.sw.ck.iot.agentbridge;

import com.sw.ck.agent.service.AgentGraphDefService;
import com.sw.ck.agent.service.AgentGraphExecutionLifecycleService;
import com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO;
import com.sw.ck.agent.event.AgentGraphExecutionTerminalEvent;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.tenant.TenantWorkAdmission;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotEventRule;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRuleMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * IoT → Agent 桥接测试：AGENT 事件提交图执行与回写、非 AGENT 事件忽略、
 * 终态承接（成功回写/失败升级生成 FLOW 触发/取消与中断不升级/升级幂等）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IotAgentBridgeTest {

    @Mock
    private ObjectProvider<AgentGraphDefService> graphDefServiceProvider;
    @Mock
    private ObjectProvider<AgentGraphExecutionLifecycleService> lifecycleServiceProvider;
    @Mock
    private ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    @Mock
    private AgentGraphDefService graphDefService;
    @Mock
    private AgentGraphExecutionLifecycleService lifecycleService;
    @Mock
    private IotProcessTriggerMapper triggerMapper;
    @Mock
    private IotEventRuleMapper ruleMapper;
    @Mock
    private IotDeviceMapper deviceMapper;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private TenantWorkAdmission tenantWorkAdmission;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AgentGraphTriggerListener triggerListener;
    private AgentExecutionOutcomeListener outcomeListener;

    @BeforeEach
    void setUp() {
        when(graphDefServiceProvider.getIfAvailable()).thenReturn(graphDefService);
        when(lifecycleServiceProvider.getIfAvailable()).thenReturn(lifecycleService);
        when(transactionManagerProvider.getIfAvailable()).thenReturn(transactionManager);
        // 升级路径在显式事务模板内插入并发布；测试中直接同步执行回调
        when(transactionManager.getTransaction(any())).thenReturn(null);
        triggerListener = new AgentGraphTriggerListener(graphDefServiceProvider,
                lifecycleServiceProvider, triggerMapper, tenantWorkAdmission);
        outcomeListener = new AgentExecutionOutcomeListener(triggerMapper, ruleMapper,
                deviceMapper, eventPublisher, transactionManagerProvider);
        when(tenantWorkAdmission.claim(eq(1L), any())).thenReturn(1);
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private IotProcessTriggerEvent agentEvent() {
        IotProcessTriggerEvent event = new IotProcessTriggerEvent();
        event.setTenantId(1L);
        event.setTriggerId(55L);
        event.setRuleId(11L);
        event.setDeviceId(7L);
        event.setActionType("AGENT");
        event.setAgentGraphKey("agent_gate_judge");
        event.setIdempotentKey("r11-v3-7-agent-dedup-1");
        event.setConfiguredBy(9L);
        event.setAgentInput(Map.of("flow", "35.0"));
        return event;
    }

    @Test
    void testAgentEvent_SubmitsExecutionAndWritesBack() {
        when(graphDefService.resolvePublishedGraphId("agent_gate_judge"))
                .thenReturn(Optional.of(88L));
        when(lifecycleService.submit(eq(88L), any(), eq("r11-v3-7-agent-dedup-1")))
                .thenReturn(new AgentGraphExecutionAcceptedDTO(666L, "QUEUED"));
        when(triggerMapper.update(any(IotProcessTrigger.class), any())).thenReturn(1);

        triggerListener.onIotTrigger(agentEvent());

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).submit(eq(88L), inputCaptor.capture(),
                eq("r11-v3-7-agent-dedup-1"));
        assertTrue(inputCaptor.getValue().contains("\"flow\":\"35.0\""));
        verify(triggerMapper).update(argThat(patch -> patch instanceof IotProcessTrigger p
                        && Long.valueOf(666L).equals(p.getAgentExecutionId())
                        && "PROCESSING".equals(p.getStatus())),
                any());
    }

    @Test
    void testAgentEvent_GraphMissing_MarksTriggerFailed() {
        when(graphDefService.resolvePublishedGraphId("agent_gate_judge"))
                .thenReturn(Optional.empty());

        triggerListener.onIotTrigger(agentEvent());

        verify(lifecycleService, never()).submit(any(), any(), any());
        verify(triggerMapper).update(argThat(patch -> patch instanceof IotProcessTrigger p
                        && "FAILED".equals(p.getStatus())
                        && p.getError() != null && p.getError().contains("未发布")),
                any());
    }

    @Test
    void testNonAgentEvent_IgnoredByBridgeListener() {
        IotProcessTriggerEvent event = agentEvent();
        event.setActionType("FLOW");

        triggerListener.onIotTrigger(event);

        verify(graphDefService, never()).resolvePublishedGraphId(any());
        verify(lifecycleService, never()).submit(any(), any(), any());
    }

    private IotProcessTrigger bridgeTrigger() {
        IotProcessTrigger trigger = new IotProcessTrigger();
        trigger.setId(55L);
        trigger.setRuleId(11L);
        trigger.setDeviceId(7L);
        trigger.setActionType("AGENT");
        trigger.setAgentExecutionId(666L);
        trigger.setAgentGraphKey("agent_gate_judge");
        trigger.setFormSnapshot("{\"flow\":\"35.0\",\"temperature\":\"35.0\"}");
        trigger.setStatus("PROCESSING");
        return trigger;
    }

    private IotEventRule escalationRule() {
        IotEventRule rule = new IotEventRule();
        rule.setId(11L);
        rule.setName("大门流量异常");
        rule.setAgentEnabled(1);
        rule.setAgentGraphKey("agent_gate_judge");
        rule.setAgentFailureTemplateKey("p0_ticket");
        rule.setAgentInputMappingJson("[{\"field\":\"temperature\",\"source\":\"PROPERTY_VALUE\"},"
                + "{\"field\":\"at\",\"source\":\"REPORT_TIME\"}]");
        rule.setFormMappingJson("[{\"field\":\"level\",\"source\":\"fixed\",\"fixed\":\"P0\"},"
                + "{\"field\":\"temperature\",\"source\":\"PROPERTY_VALUE\"},"
                + "{\"field\":\"sourceExecutionId\",\"source\":\"AGENT_EXECUTION_ID\",\"required\":true},"
                + "{\"field\":\"deviceKey\",\"source\":\"DEVICE_KEY\"}]");
        return rule;
    }

    @Test
    void testTerminalFailed_EscalatesToFlowTrigger() {
        IotProcessTrigger trigger = bridgeTrigger();
        when(triggerMapper.selectOne(any())).thenReturn(trigger);
        when(ruleMapper.selectById(11L)).thenReturn(escalationRule());
        IotDevice device = new IotDevice();
        device.setId(7L);
        device.setDeviceKey("dev-001");
        device.setName("南门");
        when(deviceMapper.selectById(7L)).thenReturn(device);

        outcomeListener.onTerminal(new AgentGraphExecutionTerminalEvent(
                666L, 88L, 1L, "9", "FAILED", "GRAPH_EXECUTION_FAILED", "LLM 不可用"));

        ArgumentCaptor<IotProcessTrigger> insertCaptor = ArgumentCaptor.forClass(IotProcessTrigger.class);
        verify(triggerMapper).insert(insertCaptor.capture());
        IotProcessTrigger escalation = insertCaptor.getValue();
        assertEquals("FLOW", escalation.getActionType());
        assertEquals("p0_ticket", escalation.getProcessTemplateKey());
        assertEquals("iot-esc-666", escalation.getIdempotentKey());
        assertEquals("AGENT_FAIL", escalation.getTriggerSource());
        Map<String, Object> formData = com.alibaba.fastjson2.JSON.parseObject(
                escalation.getFormSnapshot());
        assertEquals("P0", formData.get("level"));
        assertEquals("35.0", formData.get("temperature"));
        assertEquals(666L, ((Number) formData.get("sourceExecutionId")).longValue());
        assertEquals("dev-001", formData.get("deviceKey"));
        verify(eventPublisher).publish(any(IotProcessTriggerEvent.class));
    }

    @Test
    void testTerminalFailed_NoEscalationConfig_TerminalOnly() {
        IotProcessTrigger trigger = bridgeTrigger();
        when(triggerMapper.selectOne(any())).thenReturn(trigger);
        IotEventRule rule = escalationRule();
        rule.setAgentFailureTemplateKey(null);
        when(ruleMapper.selectById(11L)).thenReturn(rule);

        outcomeListener.onTerminal(new AgentGraphExecutionTerminalEvent(
                666L, 88L, 1L, "9", "FAILED", "EXECUTION_ERROR", null));

        verify(triggerMapper, never()).insert(any(IotProcessTrigger.class));
        verify(eventPublisher, never()).publish(any());
        verify(triggerMapper).update(argThat(patch -> patch instanceof IotProcessTrigger p
                        && "FAILED".equals(p.getStatus())),
                any());
    }

    @Test
    void testTerminalSuccess_WritesBackNoEscalation() {
        IotProcessTrigger trigger = bridgeTrigger();
        when(triggerMapper.selectOne(any())).thenReturn(trigger);

        outcomeListener.onTerminal(new AgentGraphExecutionTerminalEvent(
                666L, 88L, 1L, "9", "SUCCESS", null, null));

        verify(triggerMapper, never()).insert(any(IotProcessTrigger.class));
        verify(eventPublisher, never()).publish(any());
        verify(triggerMapper).update(argThat(patch -> patch instanceof IotProcessTrigger p
                        && "SUCCESS".equals(p.getStatus())),
                any());
    }

    @Test
    void testTerminalCancelledOrInterrupted_NoEscalation() {
        IotProcessTrigger trigger = bridgeTrigger();
        when(triggerMapper.selectOne(any())).thenReturn(trigger);

        outcomeListener.onTerminal(new AgentGraphExecutionTerminalEvent(
                666L, 88L, 1L, "9", "CANCELLED", null, null));
        outcomeListener.onTerminal(new AgentGraphExecutionTerminalEvent(
                667L, 88L, 1L, "9", "INTERRUPTED", "SERVICE_RESTART", null));

        verify(triggerMapper, never()).insert(any(IotProcessTrigger.class));
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void testEscalationIdempotentDuplicate_SkipsPublish() {
        IotProcessTrigger trigger = bridgeTrigger();
        when(triggerMapper.selectOne(any())).thenReturn(trigger);
        when(ruleMapper.selectById(11L)).thenReturn(escalationRule());
        when(deviceMapper.selectById(7L)).thenReturn(new IotDevice());
        doThrow(new DuplicateKeyException("dup")).when(triggerMapper).insert(any(IotProcessTrigger.class));

        outcomeListener.onTerminal(new AgentGraphExecutionTerminalEvent(
                666L, 88L, 1L, "9", "FAILED", "GRAPH_EXECUTION_FAILED", "x"));

        verify(eventPublisher, never()).publish(any(IotProcessTriggerEvent.class));
    }

    @Test
    void testNonBridgeExecution_Ignored() {
        when(triggerMapper.selectOne(any())).thenReturn(null);

        outcomeListener.onTerminal(new AgentGraphExecutionTerminalEvent(
                666L, 88L, 1L, "9", "FAILED", "EXECUTION_ERROR", null));

        verify(triggerMapper, never()).update(any(), any());
        verify(triggerMapper, never()).insert(any(IotProcessTrigger.class));
    }
}
