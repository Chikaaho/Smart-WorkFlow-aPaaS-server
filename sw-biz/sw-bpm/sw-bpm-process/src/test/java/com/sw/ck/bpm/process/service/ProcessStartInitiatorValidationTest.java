package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.spi.ApproverResolver;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I1 流程发起人有效性前置校验测试（方向 §3.1：停用/无效用户不得发起流程）。
 */
@DisplayName("I1 流程发起人有效性校验")
@ExtendWith(MockitoExtension.class)
class ProcessStartInitiatorValidationTest {

    @Mock
    private com.sw.ck.bpm.process.service.BpmFormBindingService bindingService;
    @Mock
    private ApproverResolver approverResolver;
    @Mock
    private BpmRuntimeFacade bpmRuntimeFacade;
    @Mock
    private BpmTaskFacade bpmTaskFacade;
    @Mock
    private BpmInstanceService bpmInstanceService;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private UserQueryFacade userQueryFacade;
    @Mock
    private ObjectProvider<UserQueryFacade> userQueryFacadeProvider;

    private ProcessStartService serviceWithFacade() {
        when(userQueryFacadeProvider.getIfAvailable()).thenReturn(userQueryFacade);
        return new ProcessStartService(bindingService, approverResolver, bpmRuntimeFacade,
                bpmTaskFacade, bpmInstanceService, domainEventPublisher, userQueryFacadeProvider);
    }

    @Test
    @DisplayName("停用/跨租户发起人被拒绝并抛 2314，不触达引擎")
    void start_withInvalidInitiator_shouldReject() {
        ProcessStartService service = serviceWithFacade();
        when(bindingService.findActiveByFormKey("form-1")).thenReturn(List.of(binding()));
        when(userQueryFacade.findActiveUserIds(List.of(810L), 1L)).thenReturn(java.util.Optional.of(List.of()));

        StartCommand cmd = command();
        assertThatThrownBy(() -> service.start(cmd))
                .isInstanceOfSatisfying(BaseException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BpmErrorCode.INSTANCE_INITIATOR_INVALID.getCode()));
        verify(bpmRuntimeFacade, never()).startProcess(any(), any(), any(), any());
    }

    @Test
    @DisplayName("有效发起人正常进入发起链")
    void start_withActiveInitiator_shouldProceed() {
        ProcessStartService service = serviceWithFacade();
        when(bindingService.findActiveByFormKey("form-1")).thenReturn(List.of(binding()));
        when(userQueryFacade.findActiveUserIds(List.of(810L), 1L)).thenReturn(java.util.Optional.of(List.of(810L)));
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("process-1"), eq("record-1"), any(), eq("1")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(false));

        service.start(command());

        verify(bpmRuntimeFacade).startProcess(eq("process-1"), eq("record-1"), any(), eq("1"));
    }

    @Test
    @DisplayName("无启用绑定时跳过（不校验发起人，维持既有 no-op 语义）")
    void start_withoutBinding_shouldNoOp() {
        ProcessStartService service = serviceWithFacade();
        when(bindingService.findActiveByFormKey("form-1")).thenReturn(List.of());

        service.start(command());

        verify(userQueryFacade, never()).findActiveUserIds(anyList(), eq(1L));
        verify(bpmRuntimeFacade, never()).startProcess(any(), any(), any(), any());
    }

    private BpmFormBinding binding() {
        BpmFormBinding binding = new BpmFormBinding();
        binding.setProcessDefKey("process-1");
        binding.setFormKey("form-1");
        return binding;
    }

    private StartCommand command() {
        StartCommand cmd = new StartCommand();
        cmd.setFormKey("form-1");
        cmd.setProcessDefKey("process-1");
        cmd.setRecordId("record-1");
        cmd.setSubmitter(810L);
        cmd.setTenantId(1L);
        return cmd;
    }
}
