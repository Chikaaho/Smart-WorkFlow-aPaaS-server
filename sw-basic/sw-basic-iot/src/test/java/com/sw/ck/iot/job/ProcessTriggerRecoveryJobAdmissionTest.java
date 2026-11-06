package com.sw.ck.iot.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessTriggerRecoveryJobAdmissionTest {

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotProcessTrigger.class);
    }

    @Test
    void pausedTenantCannotClaimOrRepublishTrigger() {
        IotProcessTriggerMapper mapper = mock(IotProcessTriggerMapper.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        IotProcessTrigger trigger = new IotProcessTrigger();
        trigger.setId(81L);
        trigger.setTenantId(88L);
        trigger.setStatus("PENDING");
        trigger.setRetryCount(0);
        when(mapper.selectList(any())).thenReturn(List.of(trigger));
        ProcessTriggerRecoveryJob job = new ProcessTriggerRecoveryJob(
                mapper, publisher, transactions, (tenantId, claim) -> 0);

        job.recoverDue();

        verify(mapper, never()).update(any(), any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void agentTriggerRepublishKeepsAgentSemantics() {
        IotProcessTriggerMapper mapper = mock(IotProcessTriggerMapper.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(null);
        IotProcessTrigger trigger = new IotProcessTrigger();
        trigger.setId(91L);
        trigger.setTenantId(1L);
        trigger.setRuleId(5L);
        trigger.setDeviceId(7L);
        trigger.setStatus("PENDING");
        trigger.setRetryCount(0);
        trigger.setActionType("AGENT");
        trigger.setAgentGraphKey("agent_gate_judge");
        trigger.setProcessTemplateKey("agent_gate_judge");
        trigger.setIdempotentKey("r5-v1-7-agent-dedup");
        trigger.setFormSnapshot("{\"flow\":\"35.0\"}");
        when(mapper.selectList(any())).thenReturn(List.of(trigger));
        when(mapper.update(any(), any())).thenReturn(1);
        ProcessTriggerRecoveryJob job = new ProcessTriggerRecoveryJob(
                mapper, publisher, transactions, (tenantId, claim) -> 1);

        job.recoverDue();

        ArgumentCaptor<IotProcessTriggerEvent> captor =
                ArgumentCaptor.forClass(IotProcessTriggerEvent.class);
        verify(publisher).publish(captor.capture());
        IotProcessTriggerEvent event = captor.getValue();
        assertEquals("AGENT", event.getActionType(), "恢复重投必须保留动作类型");
        assertEquals("agent_gate_judge", event.getAgentGraphKey());
        assertEquals(5L, event.getRuleId());
        assertEquals("35.0", String.valueOf(event.getAgentInput().get("flow")),
                "恢复重投必须还原图执行输入");
    }
}
