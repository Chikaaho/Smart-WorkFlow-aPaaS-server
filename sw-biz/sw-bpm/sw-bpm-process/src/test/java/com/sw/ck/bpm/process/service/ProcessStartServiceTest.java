package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.spi.ApproverResolver;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.common.event.DomainEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProcessStartService 实例终态同步")
@ExtendWith(MockitoExtension.class)
class ProcessStartServiceTest {

    @Mock
    private BpmFormBindingService bindingService;
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

    @InjectMocks
    private ProcessStartService service;

    @Test
    @DisplayName("启动后引擎已结束时写入 APPROVED 且不创建待办通知")
    void start_whenRuntimeAlreadyEnded_shouldPersistApproved() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn("1");
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn("instance-1");
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(false);

        service.start(command());

        ArgumentCaptor<BpmInstance> captor = ArgumentCaptor.forClass(BpmInstance.class);
        verify(bpmInstanceService).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
        verify(bpmTaskFacade, never()).queryByProcessInstance("instance-1");
        verify(domainEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("启动后引擎仍活跃时写入 RUNNING 并保留待办通知路径")
    void start_whenRuntimeActive_shouldPersistRunning() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn("1");
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn("instance-1");
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(true);
        when(bpmTaskFacade.queryByProcessInstance("instance-1")).thenReturn(List.of());

        service.start(command());

        ArgumentCaptor<BpmInstance> captor = ArgumentCaptor.forClass(BpmInstance.class);
        verify(bpmInstanceService).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RUNNING");
        verify(bpmTaskFacade).queryByProcessInstance("instance-1");
    }

    private BpmFormBinding binding() {
        BpmFormBinding binding = new BpmFormBinding();
        binding.setFormKey("p57-r1-form");
        binding.setProcessDefKey("p57-r1-process");
        binding.setActive(true);
        return binding;
    }

    private StartCommand command() {
        StartCommand command = new StartCommand();
        command.setFormKey("p57-r1-form");
        command.setRecordId("record-1");
        command.setSubmitter(1L);
        command.setTenantId(57001L);
        return command;
    }
}
