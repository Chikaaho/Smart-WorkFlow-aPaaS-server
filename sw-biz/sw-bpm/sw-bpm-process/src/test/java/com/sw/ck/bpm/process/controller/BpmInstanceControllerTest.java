package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.dto.BpmActivityDTO;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.dto.InstanceFilterDTO;
import com.sw.ck.bpm.process.dto.InstanceDetailDTO;
import com.sw.ck.bpm.process.dto.InstanceListItemDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("BpmInstanceController 单元测试")
class BpmInstanceControllerTest {

    private final BpmInstanceService bpmInstanceService = mock(BpmInstanceService.class);
    private final BpmRuntimeFacade bpmRuntimeFacade = mock(BpmRuntimeFacade.class);
    private final BpmProcessDefService bpmProcessDefService = mock(BpmProcessDefService.class);
    private final com.sw.ck.system.api.user.UserQueryFacade userQueryFacade =
            mock(com.sw.ck.system.api.user.UserQueryFacade.class);

    private final com.sw.ck.bpm.process.service.ParticipantNameService participantNameService =
            new com.sw.ck.bpm.process.service.ParticipantNameService(
                    mock(com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper.class), userQueryFacade);

    private final com.sw.ck.security.support.PermissionService permissionService =
            mock(com.sw.ck.security.support.PermissionService.class);
    private final com.sw.ck.bpm.process.mapper.CopyRecordMapper copyRecordMapper =
            mock(com.sw.ck.bpm.process.mapper.CopyRecordMapper.class);

    private final BpmInstanceController controller = new BpmInstanceController(
            bpmInstanceService, bpmRuntimeFacade, bpmProcessDefService, userQueryFacade, participantNameService,
            permissionService, copyRecordMapper);

    @AfterEach
    void tearDown() {
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    private com.sw.ck.security.holder.LoginUser superAdmin() {
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(1L);
        user.setTenantId(0L);
        user.setSuperAdmin(true);
        return user;
    }

    private com.sw.ck.security.holder.LoginUser normalUser(Long userId) {
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(userId);
        user.setTenantId(0L);
        user.setSuperAdmin(false);
        return user;
    }

    // 测试夹具
    private BpmInstance sampleInstance;
    private BpmProcessDef sampleProcessDef;

    @BeforeEach
    void setUp() {
        // 默认超管身份：既有用例关注详情装配；对象权限负向单独覆盖
        com.sw.ck.security.holder.LoginUserHolder.set(superAdmin());
        sampleInstance = new BpmInstance();
        sampleInstance.setId(1L);
        sampleInstance.setProcessInstanceId("proc-001");
        sampleInstance.setProcessDefKey("leave");
        sampleInstance.setBusinessKey("rec-123");
        sampleInstance.setFormKey("leave_form");
        sampleInstance.setInitiatorId(100L);
        sampleInstance.setStatus("RUNNING");
        sampleInstance.setCreateTime(LocalDateTime.of(2026, 7, 1, 10, 0));

        sampleProcessDef = new BpmProcessDef();
        sampleProcessDef.setProcessKey("leave");
        sampleProcessDef.setName("请假流程");
    }

    // ==================== GET /workflow/instances ====================

    @Nested
    @DisplayName("GET /workflow/instances — 分页实例列表")
    class ListInstancesTests {

        @Test
        @DisplayName("无过滤 → 返回全量分页结果，含 processName 富化")
        void listInstances_noFilter_shouldReturnAll() {
            PageResult<BpmInstance> page = new PageResult<>();
            page.setRecords(List.of(sampleInstance));
            page.setTotal(1);
            page.setPageNum(1);
            page.setPageSize(10);

            when(bpmInstanceService.pageInstances(any(PageParam.class), isNull()))
                    .thenReturn(page);
            when(bpmProcessDefService.findByProcessKey("leave"))
                    .thenReturn(sampleProcessDef);

            R<PageResult<InstanceListItemDTO>> result = controller.listInstances(
                    new PageParam(), null);

            assertThat(result.getCode()).isZero();
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getTotal()).isEqualTo(1);
            assertThat(result.getData().getRecords()).hasSize(1);

            InstanceListItemDTO dto = result.getData().getRecords().get(0);
            assertThat(dto.getId()).isEqualTo(1L);
            assertThat(dto.getProcessInstanceId()).isEqualTo("proc-001");
            assertThat(dto.getProcessName()).isEqualTo("请假流程");
            assertThat(dto.getStatus()).isEqualTo("RUNNING");
            assertThat(dto.getCreateTime()).isNotNull();
        }

        @Test
        @DisplayName("按 status 过滤 → 传递给 Service，返回过滤后结果")
        void listInstances_filterByStatus_shouldPassFilter() {
            InstanceFilterDTO filter = new InstanceFilterDTO();
            filter.setStatus("RUNNING");

            PageResult<BpmInstance> page = new PageResult<>();
            page.setRecords(List.of(sampleInstance));
            page.setTotal(1);
            page.setPageNum(1);
            page.setPageSize(10);

            when(bpmInstanceService.pageInstances(any(PageParam.class), eq(filter)))
                    .thenReturn(page);
            when(bpmProcessDefService.findByProcessKey("leave"))
                    .thenReturn(sampleProcessDef);

            R<PageResult<InstanceListItemDTO>> result = controller.listInstances(
                    new PageParam(), filter);

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getTotal()).isEqualTo(1);
            verify(bpmInstanceService).pageInstances(any(PageParam.class), eq(filter));
        }

        @Test
        @DisplayName("流程定义已删除 → processName 为 null，不阻断列表")
        void listInstances_processDefDeleted_shouldReturnNullProcessName() {
            PageResult<BpmInstance> page = new PageResult<>();
            page.setRecords(List.of(sampleInstance));
            page.setTotal(1);
            page.setPageNum(1);
            page.setPageSize(10);

            when(bpmInstanceService.pageInstances(any(PageParam.class), isNull()))
                    .thenReturn(page);
            when(bpmProcessDefService.findByProcessKey("leave"))
                    .thenReturn(null);  // 流程定义已删除

            R<PageResult<InstanceListItemDTO>> result = controller.listInstances(
                    new PageParam(), null);

            assertThat(result.getCode()).isZero();
            InstanceListItemDTO dto = result.getData().getRecords().get(0);
            assertThat(dto.getProcessName()).isNull();  // 不阻断
        }
    }

    // ==================== 对象权限（I4 §3.3/§4 跨用户零串读） ====================

    @Nested
    @DisplayName("实例详情对象权限")
    class InstanceDetailAccessTests {

        @Test
        @DisplayName("无监控权限且非发起人/参与人/抄送人 → 拒绝")
        void instanceDetail_outsider_shouldDeny() {
            com.sw.ck.security.holder.LoginUserHolder.set(normalUser(999L));
            when(bpmInstanceService.findByProcessInstanceId("proc-001"))
                    .thenReturn(Optional.of(sampleInstance));
            assertThatThrownBy(() -> controller.instanceDetail("proc-001"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("无权查看");
            verify(permissionService).hasPermi("workflow:monitor:view");
        }

        @Test
        @DisplayName("发起人本人 → 允许")
        void instanceDetail_initiator_shouldAllow() {
            com.sw.ck.security.holder.LoginUserHolder.set(normalUser(100L));
            when(bpmInstanceService.findByProcessInstanceId("proc-001"))
                    .thenReturn(Optional.of(sampleInstance));
            when(bpmRuntimeFacade.getActiveActivityIds("proc-001")).thenReturn(List.of());
            when(bpmRuntimeFacade.queryHistoricActivities("proc-001")).thenReturn(List.of());
            when(bpmProcessDefService.findByProcessKey("leave")).thenReturn(sampleProcessDef);
            assertThat(controller.instanceDetail("proc-001").getCode()).isZero();
        }

        @Test
        @DisplayName("参与人（历史任务办理人）→ 允许")
        void instanceDetail_participant_shouldAllow() {
            com.sw.ck.security.holder.LoginUserHolder.set(normalUser(200L));
            when(bpmInstanceService.findByProcessInstanceId("proc-001"))
                    .thenReturn(Optional.of(sampleInstance));
            com.sw.ck.bpm.api.dto.BpmActivityDTO taskRow = new com.sw.ck.bpm.api.dto.BpmActivityDTO();
            taskRow.setActivityType("userTask");
            taskRow.setTaskId("task-1");
            taskRow.setAssignee("200");
            when(bpmRuntimeFacade.queryHistoricActivities("proc-001")).thenReturn(List.of(taskRow));
            when(bpmRuntimeFacade.getActiveActivityIds("proc-001")).thenReturn(List.of());
            when(bpmProcessDefService.findByProcessKey("leave")).thenReturn(sampleProcessDef);
            assertThat(controller.instanceDetail("proc-001").getCode()).isZero();
        }
    }

    // ==================== GET /workflow/instances/{processInstanceId} ====================

    @Nested
    @DisplayName("GET /workflow/instances/{processInstanceId} — 实例详情")
    class InstanceDetailTests {

        @Test
        @DisplayName("实例存在且运行中 → 返回活跃节点 + 流转记录")
        void instanceDetail_running_shouldReturnActiveNodesAndFlowTrace() {
            when(bpmInstanceService.findByProcessInstanceId("proc-001"))
                    .thenReturn(Optional.of(sampleInstance));
            when(bpmRuntimeFacade.getActiveActivityIds("proc-001"))
                    .thenReturn(List.of("Activity_001"));
            when(bpmRuntimeFacade.queryHistoricActivities("proc-001"))
                    .thenReturn(List.of());
            when(bpmProcessDefService.findByProcessKey("leave"))
                    .thenReturn(sampleProcessDef);

            R<InstanceDetailDTO> result = controller.instanceDetail("proc-001");

            assertThat(result.getCode()).isZero();
            InstanceDetailDTO dto = result.getData();
            assertThat(dto.getProcessInstanceId()).isEqualTo("proc-001");
            assertThat(dto.getProcessName()).isEqualTo("请假流程");
            assertThat(dto.getActiveNodeIds()).containsExactly("Activity_001");
            assertThat(dto.getFlowTrace()).isEmpty();
        }

        @Test
        @DisplayName("实例不存在 → 抛 BaseException code=404")
        void instanceDetail_notFound_shouldThrow404() {
            when(bpmInstanceService.findByProcessInstanceId("nonexistent"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.instanceDetail("nonexistent"))
                    .isInstanceOf(BaseException.class)
                    .satisfies(e -> {
                        BaseException be = (BaseException) e;
                        assertThat(be.getCode()).isEqualTo(404);
                        assertThat(be.getMessage()).contains("不存在");
                    });

            // 验证未调用 Facade（短路在 Service 层）
            verify(bpmRuntimeFacade, never()).getActiveActivityIds(any());
            verify(bpmRuntimeFacade, never()).queryHistoricActivities(any());
        }

        @Test
        @DisplayName("已结束实例 → activeNodeIds 空列表，flowTrace 含完整历史")
        void instanceDetail_completed_shouldReturnEmptyActiveNodes() {
            sampleInstance.setStatus("APPROVED");
            when(bpmInstanceService.findByProcessInstanceId("proc-002"))
                    .thenReturn(Optional.of(sampleInstance));
            when(bpmRuntimeFacade.getActiveActivityIds("proc-002"))
                    .thenReturn(List.of());  // 已结束，无活跃节点
            when(bpmRuntimeFacade.queryHistoricActivities("proc-002"))
                    .thenReturn(List.of(new BpmActivityDTO()));  // 简化表示
            when(bpmProcessDefService.findByProcessKey("leave"))
                    .thenReturn(sampleProcessDef);

            R<InstanceDetailDTO> result = controller.instanceDetail("proc-002");

            assertThat(result.getCode()).isZero();
            assertThat(result.getData().getActiveNodeIds()).isEmpty();
            assertThat(result.getData().getFlowTrace()).hasSize(1);
        }
    }
}
