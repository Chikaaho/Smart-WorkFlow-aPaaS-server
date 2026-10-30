package com.sw.ck.iot.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

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
}
