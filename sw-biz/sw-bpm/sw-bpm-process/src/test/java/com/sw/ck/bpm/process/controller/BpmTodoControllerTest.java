package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.dto.ProcessedTaskRespDTO;
import com.sw.ck.bpm.process.dto.TaskDetailRespDTO;
import com.sw.ck.bpm.process.dto.TodoTaskRespDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link BpmTodoController} 单元测试。
 * <p>
 * 覆盖全部 5 个端点的 happy path + 边界/异常路径。
 * 纯 Mockito，不装载 Spring 上下文。使用 {@link LoginUserHolder#set(LoginUser)}
 * 模拟认证上下文，与项目既有测试模式一致（参照 {@code AuthMeControllerTest}）。
 * </p>
 */
@DisplayName("BPM 待办中心控制器测试")
class BpmTodoControllerTest {

    private final BpmTaskFacade bpmTaskFacade = mock(BpmTaskFacade.class);
    private final BpmInstanceService bpmInstanceService = mock(BpmInstanceService.class);
    private final BpmProcessDefService bpmProcessDefService = mock(BpmProcessDefService.class);
    private final DomainEventPublisher domainEventPublisher = mock(DomainEventPublisher.class);
    private final com.sw.ck.system.api.user.UserQueryFacade userQueryFacade =
            mock(com.sw.ck.system.api.user.UserQueryFacade.class);
    private final com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService =
            mock(com.sw.ck.bpm.process.service.ApprovalActionService.class);
    private final com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder participantSnapshotRecorder =
            mock(com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder.class);
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final com.sw.ck.bpm.process.service.TaskActionService taskActionService =
            new com.sw.ck.bpm.process.service.TaskActionService(
                    bpmTaskFacade, bpmInstanceService, bpmProcessDefService, domainEventPublisher,
                    userQueryFacade, approvalActionService, objectMapper, participantSnapshotRecorder);

    private final BpmTodoController controller = new BpmTodoController(
            bpmTaskFacade, bpmInstanceService, bpmProcessDefService, taskActionService,
            approvalActionService, objectMapper);

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 测试数据工厂 ====================

    /** 装配标准登录用户：userId=2, tenantId=1, assignee="2" */
    private void setLoginUser() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(2L);
        loginUser.setTenantId(1L);
        loginUser.setUsername("approver1");
        loginUser.setRoles(List.of("user"));
        loginUser.setPermissions(Collections.emptyList());
        loginUser.setSuperAdmin(false);
        LoginUserHolder.set(loginUser);
    }

    /** 创建标准 BpmTaskDTO（待办任务，endTime=null，assignee="2"）。 */
    private BpmTaskDTO createTask(String taskId) {
        BpmTaskDTO task = new BpmTaskDTO();
        task.setTaskId(taskId);
        task.setName("审批");
        task.setProcessInstanceId("pi-" + taskId);
        task.setProcessDefinitionKey("skeleton_approval");
        task.setAssignee("2");
        task.setBusinessKey("rec-001");
        task.setCreateTime(new Date());
        return task;
    }

    /** 创建已办 BpmTaskDTO（含 endTime）。 */
    private BpmTaskDTO createProcessedTask(String taskId) {
        BpmTaskDTO task = createTask(taskId);
        task.setEndTime(new Date());
        return task;
    }

    /** 创建标准流程定义。 */
    private BpmProcessDef createProcessDef() {
        BpmProcessDef def = new BpmProcessDef();
        def.setName("单节点审批");
        def.setProcessKey("skeleton_approval");
        return def;
    }

    /** 创建标准流程实例（initiatorId=1）。 */
    private BpmInstance createInstance() {
        BpmInstance instance = new BpmInstance();
        instance.setInitiatorId(1L);
        return instance;
    }

    // ==================== GET /workflow/tasks/todo ====================

    @Nested
    @DisplayName("GET /workflow/tasks/todo")
    class TodoTests {

        @Test
        @DisplayName("正常分页查询 → PageResult 含 records/total/pageNum/pageSize")
        void todo_shouldReturnPageResult() {
            setLoginUser();
            BpmTaskDTO t1 = createTask("task-001");
            BpmTaskDTO t2 = createTask("task-002");

            when(bpmTaskFacade.queryTodoPage(eq("1"), eq("2"), anyInt(), anyInt()))
                    .thenReturn(java.util.Optional.of(List.of(t1, t2)));
            when(bpmTaskFacade.countTodo("1", "2")).thenReturn(java.util.Optional.of(2L));
            when(bpmTaskFacade.getVariable("pi-task-001", "formKey")).thenReturn(java.util.Optional.of("test_form"));
            when(bpmTaskFacade.getVariable("pi-task-002", "formKey")).thenReturn(java.util.Optional.of("leave_form"));
            when(bpmProcessDefService.findByProcessKey("skeleton_approval")).thenReturn(createProcessDef());

            R<PageResult<TodoTaskRespDTO>> result = controller.todo(new PageParam());

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getRecords()).hasSize(2);
            assertThat(result.getData().getTotal()).isEqualTo(2L);
            assertThat(result.getData().getPageNum()).isEqualTo(1L);
            assertThat(result.getData().getPageSize()).isEqualTo(10L);
            assertThat(result.getData().getRecords().get(0).getFormKey()).isEqualTo("test_form");
            assertThat(result.getData().getRecords().get(0).getProcessName()).isEqualTo("单节点审批");
            verify(bpmTaskFacade).queryTodoPage(eq("1"), eq("2"), eq(0), eq(10));
            verify(bpmTaskFacade).countTodo("1", "2");
        }

        @Test
        @DisplayName("空待办列表 → records=[], total=0")
        void todo_shouldReturnEmptyList() {
            setLoginUser();
            when(bpmTaskFacade.queryTodoPage(anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(java.util.Optional.of(Collections.emptyList()));
            when(bpmTaskFacade.countTodo(anyString(), anyString())).thenReturn(java.util.Optional.of(0L));

            R<PageResult<TodoTaskRespDTO>> result = controller.todo(new PageParam());

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getRecords()).isEmpty();
            assertThat(result.getData().getTotal()).isZero();
        }

        @Test
        @DisplayName("无对应流程定义 → processName 为 null（不抛 NPE）")
        void todo_processDefNotFound_shouldSetProcessNameNull() {
            setLoginUser();
            BpmTaskDTO t1 = createTask("task-001");
            when(bpmTaskFacade.queryTodoPage(anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(java.util.Optional.of(List.of(t1)));
            when(bpmTaskFacade.countTodo(anyString(), anyString())).thenReturn(java.util.Optional.of(1L));
            when(bpmProcessDefService.findByProcessKey("skeleton_approval")).thenReturn(null);

            R<PageResult<TodoTaskRespDTO>> result = controller.todo(new PageParam());

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getRecords().get(0).getProcessName()).isNull();
        }
    }

    // ==================== POST /workflow/tasks/{taskId}/complete ====================

    @Nested
    @DisplayName("POST /workflow/tasks/{taskId}/complete")
    class CompleteTests {

        @Test
        @DisplayName("正常审批通过 + 流程结束 → APPROVED + 通知事件")
        void complete_shouldApproveAndPublishEvent() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmTaskFacade.isProcessActive("pi-task-001")).thenReturn(java.util.Optional.of(false));
            when(bpmInstanceService.findByProcessInstanceId("pi-task-001"))
                    .thenReturn(Optional.of(createInstance()));

            R<Void> result = controller.complete("task-001");

            assertThat(result.getCode()).isZero();
            verify(bpmTaskFacade).complete(eq("task-001"), isNull());
            verify(bpmInstanceService).updateStatus("pi-task-001", "APPROVED");
            verify(domainEventPublisher).publish(any(BpmNotifyEvent.class));
        }

        @Test
        @DisplayName("审批通过但流程未结束 → 不更新状态、不发通知")
        void complete_flowStillActive_shouldNotUpdateStatusOrPublish() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmTaskFacade.isProcessActive("pi-task-001")).thenReturn(java.util.Optional.of(true));

            R<Void> result = controller.complete("task-001");

            assertThat(result.getCode()).isZero();
            verify(bpmTaskFacade).complete(eq("task-001"), isNull());
            verify(bpmInstanceService, never()).updateStatus(anyString(), anyString());
            verify(domainEventPublisher, never()).publish(any(BpmNotifyEvent.class));
        }

        @Test
        @DisplayName("实例已 FAILED → 拒绝继续审批且不调用 Flowable 完成")
        void complete_failedInstance_shouldRejectFurtherApproval() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-failed");
            BpmInstance failed = createInstance();
            failed.setProcessInstanceId("pi-task-failed");
            failed.setStatus(InstanceStatusEnum.FAILED.getCode());
            when(bpmTaskFacade.getTask("task-failed")).thenReturn(java.util.Optional.of(task));
            when(bpmInstanceService.findByProcessInstanceId("pi-task-failed"))
                    .thenReturn(Optional.of(failed));

            assertThatThrownBy(() -> controller.complete("task-failed"))
                    .isInstanceOf(BaseException.class)
                    .satisfies(error -> assertThat(((BaseException) error).getCode())
                            .isEqualTo(BpmErrorCode.INSTANCE_FAILED.getCode()));
            verify(bpmTaskFacade, never()).getVariables(anyString());
            verify(bpmTaskFacade, never()).complete(anyString(), any());
            verify(bpmTaskFacade, never()).completeAsUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("任务不存在 → 抛 BaseException 含「任务不存在」")
        void complete_taskNotFound_shouldThrow() {
            setLoginUser();
            when(bpmTaskFacade.getTask("task-999")).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> controller.complete("task-999"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("任务不存在");
        }

        @Test
        @DisplayName("越权 — 非当前审批人操作 → 抛 BaseException 含「无权处理」")
        void complete_unauthorized_shouldThrow() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            task.setAssignee("3"); // 非当前登录用户
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));

            assertThatThrownBy(() -> controller.complete("task-001"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("无权处理");
        }
    }

    // ==================== POST /workflow/tasks/{taskId}/reject ====================

    @Nested
    @DisplayName("POST /workflow/tasks/{taskId}/reject")
    class RejectTests {

        @Test
        @DisplayName("正常驳回 + 流程结束 → REJECTED + 不发通知")
        void reject_shouldRejectAndNotPublishEvent() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmTaskFacade.isProcessActive("pi-task-001")).thenReturn(java.util.Optional.of(false));
            org.mockito.Mockito.lenient()
                    .when(bpmTaskFacade.terminateProcess(anyString(), anyString()))
                    .thenReturn(java.util.Optional.of(com.sw.ck.bpm.api.result.MutationOutcome.APPLIED));

            R<Void> result = controller.reject("task-001");

            assertThat(result.getCode()).isZero();
            verify(bpmTaskFacade).complete(eq("task-001"), argThat(vars ->
                    vars != null && "REJECTED".equals(vars.get("outcome"))));
            verify(bpmInstanceService).updateStatus("pi-task-001", "REJECTED");
            verifyNoInteractions(domainEventPublisher);
        }

        @Test
        @DisplayName("I3 REJECT 为流程级终态：即使引擎仍在活跃也立即终结并更新状态")
        void reject_flowStillActive_shouldTerminateProcessLevel() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmTaskFacade.isProcessActive("pi-task-001")).thenReturn(java.util.Optional.of(true));
            org.mockito.Mockito.lenient()
                    .when(bpmTaskFacade.terminateProcess(anyString(), anyString()))
                    .thenReturn(java.util.Optional.of(com.sw.ck.bpm.api.result.MutationOutcome.APPLIED));

            R<Void> result = controller.reject("task-001");

            assertThat(result.getCode()).isZero();
            verify(bpmTaskFacade).terminateProcess("pi-task-001", "REJECTED");
            verify(bpmInstanceService).updateStatus("pi-task-001", "REJECTED");
        }

        @Test
        @DisplayName("任务不存在 → 抛 BaseException 含「任务不存在」")
        void reject_taskNotFound_shouldThrow() {
            setLoginUser();
            when(bpmTaskFacade.getTask("task-999")).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> controller.reject("task-999"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("任务不存在");
        }

        @Test
        @DisplayName("越权 → 抛 BaseException 含「无权处理」")
        void reject_unauthorized_shouldThrow() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            task.setAssignee("3");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));

            assertThatThrownBy(() -> controller.reject("task-001"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("无权处理");
        }
    }

    // ==================== GET /workflow/tasks/{taskId} ====================

    @Nested
    @DisplayName("GET /workflow/tasks/{taskId} (detail)")
    class DetailTests {

        @Test
        @DisplayName("完整详情 → 含 processName + initiatorId + variables + approvalHistory")
        void detail_shouldReturnFullInfo() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmProcessDefService.findByProcessKey("skeleton_approval")).thenReturn(createProcessDef());
            BpmInstance instance = createInstance();
            when(bpmInstanceService.findByProcessInstanceId("pi-task-001")).thenReturn(Optional.of(instance));
            when(bpmTaskFacade.getVariables("pi-task-001")).thenReturn(java.util.Optional.of(Map.of("formKey", "leave_form", "amount", 5000)));

            // 审批历史：2 条已完成记录
            BpmTaskDTO h1 = createProcessedTask("hist-001");
            h1.setName("审批");
            h1.setAssignee("2");
            h1.setCreateTime(new Date(System.currentTimeMillis() - 3600_000));
            h1.setEndTime(new Date());
            BpmTaskDTO h2 = createProcessedTask("hist-002");
            h2.setName("提交");
            h2.setAssignee("1");
            h2.setCreateTime(new Date(System.currentTimeMillis() - 7200_000));
            h2.setEndTime(new Date(System.currentTimeMillis() - 3600_000));
            when(bpmTaskFacade.queryHistoryByProcessInstance("pi-task-001")).thenReturn(java.util.Optional.of(List.of(h1, h2)));

            R<TaskDetailRespDTO> result = controller.detail("task-001");

            assertThat(result.getCode()).isZero();
            TaskDetailRespDTO dto = result.getData();
            assertThat(dto.getTaskName()).isEqualTo("审批");
            assertThat(dto.getProcessName()).isEqualTo("单节点审批");
            assertThat(dto.getInitiatorId()).isEqualTo(1L);
            assertThat(dto.getProcessVariables()).containsEntry("formKey", "leave_form");
            assertThat(dto.getApprovalHistory()).hasSize(2);
            assertThat(dto.getApprovalHistory().get(0).getTaskName()).isEqualTo("审批");
            assertThat(dto.getApprovalHistory().get(0).getAssignee()).isEqualTo("2");
            assertThat(dto.getApprovalHistory().get(0).getEndTime()).isNotNull();
            assertThat(dto.getApprovalHistory().get(1).getTaskName()).isEqualTo("提交");
        }

        @Test
        @DisplayName("I4 G4b：非办理人且无监控权限 → 任务详情拒绝")
        void detail_outsider_shouldDeny() {
            LoginUser outsider = new LoginUser();
            outsider.setUserId(999L);
            outsider.setTenantId(1L);
            outsider.setUsername("outsider");
            outsider.setRoles(Collections.emptyList());
            outsider.setPermissions(Collections.emptyList());
            outsider.setSuperAdmin(false);
            LoginUserHolder.set(outsider);
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));

            assertThatThrownBy(() -> controller.detail("task-001"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("无权查看该任务");
        }

        @Test
        @DisplayName("监控权限身份 → 任务详情允许")
        void detail_monitorViewer_shouldAllow() {
            LoginUser monitor = new LoginUser();
            monitor.setUserId(888L);
            monitor.setTenantId(1L);
            monitor.setUsername("monitor");
            monitor.setRoles(List.of("admin"));
            monitor.setPermissions(List.of("workflow:monitor:view"));
            monitor.setSuperAdmin(false);
            LoginUserHolder.set(monitor);
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmInstanceService.findByProcessInstanceId("pi-task-001")).thenReturn(Optional.of(createInstance()));
            when(bpmTaskFacade.getVariables("pi-task-001")).thenReturn(java.util.Optional.of(Collections.emptyMap()));
            when(bpmTaskFacade.queryHistoryByProcessInstance("pi-task-001")).thenReturn(java.util.Optional.of(Collections.emptyList()));

            assertThat(controller.detail("task-001").getCode()).isZero();
        }

        @Test
        @DisplayName("审批历史为空 → approvalHistory 为空列表（非 null）")
        void detail_emptyHistory_shouldReturnEmptyList() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmInstanceService.findByProcessInstanceId("pi-task-001")).thenReturn(Optional.of(createInstance()));
            when(bpmTaskFacade.getVariables("pi-task-001")).thenReturn(java.util.Optional.of(Collections.emptyMap()));
            when(bpmTaskFacade.queryHistoryByProcessInstance("pi-task-001")).thenReturn(java.util.Optional.of(Collections.emptyList()));

            R<TaskDetailRespDTO> result = controller.detail("task-001");

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getApprovalHistory()).isEmpty();
        }

        @Test
        @DisplayName("任务不存在 → 抛 BaseException 含「任务不存在」")
        void detail_taskNotFound_shouldThrow() {
            setLoginUser();
            when(bpmTaskFacade.getTask("task-999")).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> controller.detail("task-999"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("任务不存在");
        }

        @Test
        @DisplayName("流程定义被删除 → processName 为 null（不抛 NPE）")
        void detail_processDefDeleted_shouldSetProcessNameNull() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001");
            when(bpmTaskFacade.getTask("task-001")).thenReturn(java.util.Optional.of(task));
            when(bpmProcessDefService.findByProcessKey("skeleton_approval")).thenReturn(null);
            when(bpmInstanceService.findByProcessInstanceId("pi-task-001")).thenReturn(Optional.of(createInstance()));
            when(bpmTaskFacade.getVariables("pi-task-001")).thenReturn(java.util.Optional.of(Collections.emptyMap()));
            when(bpmTaskFacade.queryHistoryByProcessInstance("pi-task-001")).thenReturn(java.util.Optional.of(Collections.emptyList()));

            R<TaskDetailRespDTO> result = controller.detail("task-001");

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getProcessName()).isNull();
        }
    }

    // ==================== GET /workflow/tasks/processed ====================

    @Nested
    @DisplayName("GET /workflow/tasks/processed")
    class ProcessedTests {

        @Test
        @DisplayName("正常已办分页 → 返回 ProcessedTaskRespDTO 含 endTime")
        void processed_shouldReturnPageResultWithEndTime() {
            setLoginUser();
            BpmTaskDTO t1 = createProcessedTask("task-001");
            BpmTaskDTO t2 = createProcessedTask("task-002");

            when(bpmTaskFacade.queryProcessedPage(eq("1"), eq("2"), anyInt(), anyInt()))
                    .thenReturn(java.util.Optional.of(List.of(t1, t2)));
            when(bpmTaskFacade.countProcessed("1", "2")).thenReturn(java.util.Optional.of(2L));
            when(bpmTaskFacade.getVariable(eq("pi-task-001"), eq("formKey"))).thenReturn(java.util.Optional.of("leave_form"));
            when(bpmTaskFacade.getVariable(eq("pi-task-002"), eq("formKey"))).thenReturn(java.util.Optional.of("expense_form"));
            when(bpmProcessDefService.findByProcessKey("skeleton_approval")).thenReturn(createProcessDef());

            R<PageResult<ProcessedTaskRespDTO>> result = controller.processed(new PageParam());

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getRecords()).hasSize(2);
            assertThat(result.getData().getTotal()).isEqualTo(2L);
            assertThat(result.getData().getPageNum()).isEqualTo(1L);
            ProcessedTaskRespDTO dto = result.getData().getRecords().get(0);
            assertThat(dto.getTaskName()).isEqualTo("审批");
            assertThat(dto.getFormKey()).isEqualTo("leave_form");
            assertThat(dto.getProcessName()).isEqualTo("单节点审批");
            assertThat(dto.getCreateTime()).isNotNull();
            assertThat(dto.getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("空已办列表 → records=[], total=0")
        void processed_shouldReturnEmptyList() {
            setLoginUser();
            when(bpmTaskFacade.queryProcessedPage(anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(java.util.Optional.of(Collections.emptyList()));
            when(bpmTaskFacade.countProcessed(anyString(), anyString())).thenReturn(java.util.Optional.of(0L));

            R<PageResult<ProcessedTaskRespDTO>> result = controller.processed(new PageParam());

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getRecords()).isEmpty();
            assertThat(result.getData().getTotal()).isZero();
        }

        @Test
        @DisplayName("历史记录缺失 endTime → endTime 为 null（不抛 NPE）")
        void processed_endTimeNull_shouldNotThrow() {
            setLoginUser();
            BpmTaskDTO task = createTask("task-001"); // createTask 不设 endTime
            when(bpmTaskFacade.queryProcessedPage(anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(java.util.Optional.of(List.of(task)));
            when(bpmTaskFacade.countProcessed(anyString(), anyString())).thenReturn(java.util.Optional.of(1L));
            when(bpmTaskFacade.getVariable(anyString(), eq("formKey"))).thenReturn(java.util.Optional.of("leave_form"));
            when(bpmProcessDefService.findByProcessKey("skeleton_approval")).thenReturn(createProcessDef());

            R<PageResult<ProcessedTaskRespDTO>> result = controller.processed(new PageParam());

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getRecords().get(0).getEndTime()).isNull();
        }
    }
}
