package com.sw.ck.openapi.biz.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sw.ck.openapi.biz.entity.OpenApiCallbackTask;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiCallbackTaskMapper;
import com.sw.ck.openapi.biz.service.OpenApiCallbackDeliveryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiCallbackRecoveryJobAdmissionTest {

    @BeforeAll
    static void tableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OpenApiCallbackTask.class);
    }

    @Test
    void pausedTenantCannotClaimOrDeliverCallback() {
        OpenApiCallbackTaskMapper tasks = mock(OpenApiCallbackTaskMapper.class);
        OpenApiAppMapper apps = mock(OpenApiAppMapper.class);
        OpenApiCallbackDeliveryService delivery = mock(OpenApiCallbackDeliveryService.class);
        OpenApiCallbackTask task = new OpenApiCallbackTask();
        task.setId(91L);
        task.setTenantId(94L);
        task.setStatus("PENDING");
        task.setAttempts(0);
        task.setEvent("PROCESS_APPROVED");
        task.setAppId("app-94");
        task.setBizRef("process-94");
        task.setNextRetryTime(LocalDateTime.now().minusSeconds(1));
        when(tasks.selectList(any())).thenReturn(List.of(task));
        OpenApiCallbackRecoveryJob job = new OpenApiCallbackRecoveryJob(
                tasks, apps, delivery, (tenantId, claim) -> 0);

        job.recoverDue();

        verify(tasks, never()).update(any(), any());
        verify(apps, never()).selectOne(any());
        verify(delivery, never()).dispatch(any(), any());
    }
}
