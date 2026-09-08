package com.sw.ck.iot.service;

import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotEventRule;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotEventRuleMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
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
 * 事件规则引擎测试：阈值触发、冷却、幂等去重、未联动不触发流程。
 */
@ExtendWith(MockitoExtension.class)
class RuleEngineServiceTest {

    @Mock
    private IotEventRuleMapper ruleMapper;
    @Mock
    private IotProcessTriggerMapper triggerMapper;
    @Mock
    private DomainEventPublisher eventPublisher;

    private RuleEngineService service;

    private IotDevice device;
    private IotEventRule rule;

    @BeforeEach
    void setUp() {
        service = new RuleEngineService(ruleMapper, triggerMapper, eventPublisher);
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
}
