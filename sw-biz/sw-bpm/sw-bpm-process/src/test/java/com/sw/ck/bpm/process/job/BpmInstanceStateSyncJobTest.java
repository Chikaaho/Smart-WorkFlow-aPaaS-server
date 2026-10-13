package com.sw.ck.bpm.process.job;

import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.ChildOrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅲ（数量差异关闭收尾）：实例状态对账收敛为 APPROVED 时触发子流程编排结算钩子。
 * 覆盖：引擎已终态 → 钩子先于状态落库触发；钩子异常 → 保留 RUNNING 下轮重试；
 * 引擎仍活跃 → 不触发钩子也不改状态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BpmInstanceStateSyncJobTest {

    @Mock
    private BpmInstanceService instanceService;
    @Mock
    private BpmTaskFacade taskFacade;
    @Mock
    private ChildOrchestrationService orchestration;
    @Mock
    private ObjectProvider<BpmTaskFacade> taskFacadeProvider;
    @Mock
    private ObjectProvider<ChildOrchestrationService> orchestrationProvider;

    private BpmInstanceStateSyncJob job;

    @BeforeEach
    void setUp() {
        when(taskFacadeProvider.getIfAvailable()).thenReturn(taskFacade);
        when(orchestrationProvider.getIfAvailable()).thenReturn(orchestration);
        job = new BpmInstanceStateSyncJob(instanceService, taskFacadeProvider);
        job.childOrchestrationProvider = orchestrationProvider;
    }

    private BpmInstance running(String pid) {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId(pid);
        instance.setStatus(InstanceStatusEnum.RUNNING.getCode());
        instance.setTenantId(0L);
        return instance;
    }

    @Test
    @DisplayName("引擎已终态：先触发编排结算钩子（APPROVED 语义），再收敛实例行")
    void settlesOrchestrationBeforeStatusFlip() {
        BpmInstance instance = running("p-ended");
        when(instanceService.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<BpmInstance>>any())).thenReturn(List.of(instance));
        when(taskFacade.isProcessActive("p-ended")).thenReturn(Optional.of(false));

        int synced = job.sweepOnce();

        assertThat(synced).isEqualTo(1);
        verify(orchestration).onChildProcessTerminal(instance, InstanceStatusEnum.APPROVED.getCode());
        verify(instanceService).updateById(instance);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatusEnum.APPROVED.getCode());
    }

    @Test
    @DisplayName("结算钩子异常：保留 RUNNING 行待下轮重试，不落状态、计入零收敛")
    void keepsRunningWhenSettleHookFails() {
        BpmInstance instance = running("p-hook-fails");
        when(instanceService.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<BpmInstance>>any())).thenReturn(List.of(instance));
        when(taskFacade.isProcessActive("p-hook-fails")).thenReturn(Optional.of(false));
        doThrow(new IllegalStateException("settle down"))
                .when(orchestration).onChildProcessTerminal(any(BpmInstance.class), anyString());

        int synced = job.sweepOnce();

        assertThat(synced).isZero();
        verify(instanceService, never()).updateById(any(BpmInstance.class));
        assertThat(instance.getStatus()).isEqualTo(InstanceStatusEnum.RUNNING.getCode());
    }

    @Test
    @DisplayName("引擎仍活跃：不触发钩子、不改状态")
    void skipsActiveInstances() {
        BpmInstance instance = running("p-active");
        when(instanceService.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<BpmInstance>>any())).thenReturn(List.of(instance));
        when(taskFacade.isProcessActive("p-active")).thenReturn(Optional.of(true));

        int synced = job.sweepOnce();

        assertThat(synced).isZero();
        verify(orchestration, never()).onChildProcessTerminal(any(BpmInstance.class), anyString());
        verify(instanceService, never()).updateById(any(BpmInstance.class));
    }
}
