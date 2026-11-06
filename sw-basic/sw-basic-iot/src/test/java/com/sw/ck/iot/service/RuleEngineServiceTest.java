package com.sw.ck.iot.service;

import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotEventRule;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.entity.IotPropertyRecord;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotEventRuleMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 事件规则引擎测试：阈值触发、冷却、幂等去重、未联动不触发流程、Agent 联动。
 */
@ExtendWith(MockitoExtension.class)
class RuleEngineServiceTest {

    @Mock
    private IotEventRuleMapper ruleMapper;
    @Mock
    private IotProcessTriggerMapper triggerMapper;
    @Mock
    private IotPropertyRecordMapper propertyRecordMapper;
    @Mock
    private DomainEventPublisher eventPublisher;

    private RuleEngineService service;

    private IotDevice device;
    private IotEventRule rule;

    @BeforeEach
    void setUp() {
        service = new RuleEngineService(ruleMapper, triggerMapper, propertyRecordMapper, eventPublisher);
        device = new IotDevice();
        device.setId(7L);
        device.setDeviceKey("dev-001");
        device.setTenantId(1L);
        rule = new IotEventRule();
        rule.setId(11L);
        rule.setDeviceId(7L);
        rule.setRuleType("THRESHOLD");
        rule.setRuleVersion(3);
        rule.setConditionJson("{\"propertyId\":\"temperature\",\"op\":\"GT\",\"threshold\":30}");
        rule.setDebounceMs(0L);
        rule.setCooldownMs(0L);
        rule.setContinuousCount(1);
        rule.setStatus("PUBLISHED");
        rule.setProcessEnabled(1);
        rule.setProcessTemplateKey("iot_alert");
        rule.setFormMappingJson("[{\"field\":\"temperature\",\"source\":\"PROPERTY_VALUE\"},{\"field\":\"deviceKey\",\"source\":\"DEVICE_KEY\",\"required\":true}]");
    }

    @Test
    void testThresholdExceeded_TriggersProcessWithMapping() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-1");

        ArgumentCaptor<IotProcessTrigger> triggerCaptor = ArgumentCaptor.forClass(IotProcessTrigger.class);
        verify(triggerMapper).insert(triggerCaptor.capture());
        assertTrue(triggerCaptor.getValue().getIdempotentKey().contains("r11-v3-7-dedup-1"));
        ArgumentCaptor<IotProcessTriggerEvent> eventCaptor =
                ArgumentCaptor.forClass(IotProcessTriggerEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertEquals("35.0", eventCaptor.getValue().getFormData().get("temperature"));
        assertEquals("dev-001", eventCaptor.getValue().getFormData().get("deviceKey"));
    }

    @Test
    void testThresholdNotMet_NoTrigger() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        service.onProperty(device, "temperature", "25.0", "28.0", LocalDateTime.now(), "dedup-2");

        verify(triggerMapper, never()).insert(any(IotProcessTrigger.class));
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void testDuplicateIdempotentKey_SkipsSecondTrigger() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(triggerMapper.insert(any(IotProcessTrigger.class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("dup"));

        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-1");
        // 同一 dedupKey（Broker 重投）第二次上报
        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-1");

        verify(eventPublisher, times(1)).publish(any(IotProcessTriggerEvent.class));
    }

    @Test
    void testCooldownSuppressesRapidRefire() {
        rule.setCooldownMs(60_000L);
        rule.setLastFiredTime(LocalDateTime.now());
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-3");

        verify(triggerMapper, never()).insert(any(IotProcessTrigger.class));
    }

    @Test
    void testProcessDisabled_OnlyTouchesFiredTime() {
        rule.setProcessEnabled(0);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-4");

        verify(triggerMapper, never()).insert(any(IotProcessTrigger.class));
        verify(ruleMapper).updateById(any(IotEventRule.class));
    }

    // ==================== Agent 联动（0.2.4） ====================

    @Test
    void testAgentAction_PublishesAgentEventWithMappingAndSeries() {
        rule.setProcessEnabled(0);
        rule.setProcessTemplateKey(null);
        rule.setAgentEnabled(1);
        rule.setAgentGraphKey("agent_gate_judge");
        rule.setAgentInputMappingJson("[{\"field\":\"flow\",\"source\":\"PROPERTY_VALUE\"},"
                + "{\"field\":\"deviceKey\",\"source\":\"DEVICE_KEY\"},"
                + "{\"field\":\"recentSeries\",\"source\":\"RECENT_SERIES\",\"windowSeconds\":600,\"limit\":10}]");
        IotPropertyRecord record = new IotPropertyRecord();
        record.setDeviceId(7L);
        record.setPropertyId("temperature");
        record.setValueJson("31.5");
        record.setReportTime(LocalDateTime.now().minusMinutes(5));
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(propertyRecordMapper.selectList(any())).thenReturn(List.of(record));

        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-a1");

        ArgumentCaptor<IotProcessTrigger> triggerCaptor = ArgumentCaptor.forClass(IotProcessTrigger.class);
        verify(triggerMapper).insert(triggerCaptor.capture());
        assertEquals("AGENT", triggerCaptor.getValue().getActionType());
        assertEquals("agent_gate_judge", triggerCaptor.getValue().getAgentGraphKey());
        // AGENT 行以图 key 承担恢复身份
        assertEquals("agent_gate_judge", triggerCaptor.getValue().getProcessTemplateKey());
        assertTrue(triggerCaptor.getValue().getFormSnapshot().contains("\"flow\":\"35.0\""));
        assertTrue(triggerCaptor.getValue().getFormSnapshot().contains("recentSeries"));

        ArgumentCaptor<IotProcessTriggerEvent> eventCaptor =
                ArgumentCaptor.forClass(IotProcessTriggerEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture());
        assertEquals("AGENT", eventCaptor.getValue().getActionType());
        assertEquals("agent_gate_judge", eventCaptor.getValue().getAgentGraphKey());
        assertEquals("35.0", eventCaptor.getValue().getAgentInput().get("flow"));
        assertEquals("dev-001", eventCaptor.getValue().getAgentInput().get("deviceKey"));
        Object series = eventCaptor.getValue().getAgentInput().get("recentSeries");
        assertTrue(series instanceof List && !((List<?>) series).isEmpty(),
                "RECENT_SERIES 应映射为非空序列");
    }

    @Test
    void testBothActions_PublishesFlowAndAgentEvents() {
        rule.setProcessEnabled(1);
        rule.setProcessTemplateKey("iot_alert");
        rule.setAgentEnabled(1);
        rule.setAgentGraphKey("agent_gate_judge");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));

        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-a2");

        verify(triggerMapper, times(2)).insert(any(IotProcessTrigger.class));
        ArgumentCaptor<IotProcessTriggerEvent> eventCaptor =
                ArgumentCaptor.forClass(IotProcessTriggerEvent.class);
        verify(eventPublisher, times(2)).publish(eventCaptor.capture());
        List<String> actionTypes = eventCaptor.getAllValues().stream()
                .map(IotProcessTriggerEvent::getActionType).toList();
        assertTrue(actionTypes.contains("FLOW") && actionTypes.contains("AGENT"),
                "双动作应分别发布 FLOW 与 AGENT 事件: " + actionTypes);
    }

    @Test
    void testAgentActionOnly_DuplicateDedupKeySkipsSecond() {
        rule.setProcessEnabled(0);
        rule.setProcessTemplateKey(null);
        rule.setAgentEnabled(1);
        rule.setAgentGraphKey("agent_gate_judge");
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(triggerMapper.insert(any(IotProcessTrigger.class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("dup"));

        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-a3");
        service.onProperty(device, "temperature", "35.0", "28.0", LocalDateTime.now(), "dedup-a3");

        verify(eventPublisher, times(1)).publish(any(IotProcessTriggerEvent.class));
    }
}
