package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.participant.NodeTriggerEventPort;
import com.sw.ck.bpm.api.spi.ApproverResolver;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.times;
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
    @Mock
    private NodeTriggerEventPort nodeTriggerEventPort;
    @Mock
    private UserQueryFacade userQueryFacade;

    @InjectMocks
    private ProcessStartService service;

    /** 可选端口是字段注入；显式回填，确保单测覆盖首节点补评估路径。 */
    @BeforeEach
    void injectOptionalTriggerPort() throws Exception {
        java.lang.reflect.Field field = ProcessStartService.class.getDeclaredField("nodeTriggerEventPort");
        field.setAccessible(true);
        field.set(service, nodeTriggerEventPort);
        // userQueryFacade 经 ObjectProvider 装配；@InjectMocks 不匹配该类型，显式回填
        java.lang.reflect.Field userField =
                ProcessStartService.class.getDeclaredField("userQueryFacade");
        userField.setAccessible(true);
        userField.set(service, userQueryFacade);
        // 默认：系统操作人 1 在测试租户内有效（各用例可按需覆盖）
        org.mockito.Mockito.lenient()
                .when(userQueryFacade.findActiveUserIds(any(), any()))
                .thenReturn(java.util.Optional.of(List.of(1L)));
    }

    @Test
    @DisplayName("启动后引擎已结束时写入 APPROVED 且不创建待办通知")
    void start_whenRuntimeAlreadyEnded_shouldPersistApproved() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(false));

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
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(true));
        when(bpmTaskFacade.queryByProcessInstance("instance-1")).thenReturn(java.util.Optional.of(List.of()));

        service.start(command());

        ArgumentCaptor<BpmInstance> captor = ArgumentCaptor.forClass(BpmInstance.class);
        verify(bpmInstanceService).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RUNNING");
        // 一次用于首节点 NODE_ENTERING 补评估，一次用于发布 TODO_CREATED。
        verify(bpmTaskFacade, times(2)).queryByProcessInstance("instance-1");
    }

    @Test
    @DisplayName("首节点在实例落库后补评估 NODE_ENTERING，避免监听器因实例未落库跳过")
    void start_whenFirstTaskCreatedBeforeInstancePersist_shouldEvaluateNodeEntering() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(true));
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskDefinitionKey("node_1");
        when(bpmTaskFacade.queryByProcessInstance("instance-1"))
                .thenReturn(java.util.Optional.of(List.of(task)));

        service.start(command());

        verify(nodeTriggerEventPort).onNodeEntering("instance-1", "node_1");
    }

    @Test
    @DisplayName("P64阶段Ⅲ：表单携带 txn_quantity 时提升为流程变量供 TXN_ACTION 节点取数")
    void start_whenSubmittedDataCarriesTxnQuantity_shouldPromoteProcessVariable() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(true));
        when(bpmTaskFacade.queryByProcessInstance("instance-1")).thenReturn(java.util.Optional.of(List.of()));

        StartCommand command = command();
        command.setSubmittedData(new java.util.HashMap<>(java.util.Map.of(
                "target_record_id", "inv-record-9",
                "txn_quantity", "12.5")));
        service.start(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> varsCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(bpmRuntimeFacade).startProcess(eq("p57-r1-process"), eq("record-1"),
                varsCaptor.capture(), eq("57001"));
        assertThat(varsCaptor.getValue())
                .containsEntry("targetRecordId", "inv-record-9")
                .containsEntry("txnQuantity", "12.5");
    }

    @Test
    @DisplayName("P64阶段Ⅲ：无 txn_quantity 表单数据时跳过透传，不影响普通审批流")
    void start_whenNoTxnQuantity_shouldNotPromoteVariable() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(true));
        when(bpmTaskFacade.queryByProcessInstance("instance-1")).thenReturn(java.util.Optional.of(List.of()));

        service.start(command());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> varsCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(bpmRuntimeFacade).startProcess(eq("p57-r1-process"), eq("record-1"),
                varsCaptor.capture(), eq("57001"));
        assertThat(varsCaptor.getValue()).doesNotContainKey("txnQuantity");
    }

    @Test
    @DisplayName("BF022⑧：携带主流程来源标记的自动发起——操作人强制为系统管理员且来源进入子实例变量")
    void start_whenAutoStartCarriesSource_shouldForceAdminAndEmbedSourceVariables() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(true));
        when(bpmTaskFacade.queryByProcessInstance("instance-1")).thenReturn(java.util.Optional.of(List.of()));

        StartCommand command = command();
        command.setSubmitter(7L); // 派发链携带的业务提交人：自动发起时应被改写为管理员
        command.setSubmittedData(new java.util.HashMap<>(java.util.Map.of(
                "sourceInstanceId", "main-instance-1",
                "sourceBusinessKey", "record-9")));
        service.start(command);

        assertThat(command.getSubmitter()).isEqualTo(1L);
        ArgumentCaptor<BpmInstance> instanceCaptor = ArgumentCaptor.forClass(BpmInstance.class);
        verify(bpmInstanceService).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getInitiatorId()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> varsCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(bpmRuntimeFacade).startProcess(eq("p57-r1-process"), eq("record-1"),
                varsCaptor.capture(), eq("57001"));
        assertThat(varsCaptor.getValue())
                .containsEntry("sourceInstanceId", "main-instance-1")
                .containsEntry("sourceBusinessKey", "record-9")
                .containsEntry("submitter", "1");
    }

    @Test
    @DisplayName("自动发起系统操作人在目标租户不可用：回退来源发起人，子实例归属真实用户")
    void start_whenAutoStartOperatorNotActiveInTenant_shouldKeepSourceSubmitter() {
        BpmFormBinding binding = binding();
        when(bindingService.findActiveByFormKey("p57-r1-form")).thenReturn(List.of(binding));
        when(approverResolver.resolve(any())).thenReturn(java.util.Optional.of("1"));
        when(bpmRuntimeFacade.startProcess(eq("p57-r1-process"), eq("record-1"), any(), eq("57001")))
                .thenReturn(java.util.Optional.of("instance-1"));
        when(bpmTaskFacade.isProcessActive("instance-1")).thenReturn(java.util.Optional.of(true));
        when(bpmTaskFacade.queryByProcessInstance("instance-1")).thenReturn(java.util.Optional.of(List.of()));
        // 系统用户 1 不在业务租户 57001；来源发起人 7 在租户内有效
        when(userQueryFacade.findActiveUserIds(eq(List.of(1L)), eq(57001L)))
                .thenReturn(java.util.Optional.of(List.of()));
        when(userQueryFacade.findActiveUserIds(eq(List.of(7L)), eq(57001L)))
                .thenReturn(java.util.Optional.of(List.of(7L)));

        StartCommand command = command();
        command.setSubmitter(7L);
        command.setSubmittedData(new java.util.HashMap<>(java.util.Map.of(
                "sourceInstanceId", "main-instance-1",
                "sourceBusinessKey", "record-9")));
        service.start(command);

        assertThat(command.getSubmitter()).isEqualTo(7L);
        ArgumentCaptor<BpmInstance> instanceCaptor = ArgumentCaptor.forClass(BpmInstance.class);
        verify(bpmInstanceService).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getInitiatorId()).isEqualTo(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> varsCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(bpmRuntimeFacade).startProcess(eq("p57-r1-process"), eq("record-1"),
                varsCaptor.capture(), eq("57001"));
        assertThat(varsCaptor.getValue())
                .containsEntry("sourceInstanceId", "main-instance-1")
                .containsEntry("submitter", "7");
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
