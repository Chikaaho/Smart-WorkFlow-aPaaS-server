package com.sw.ck.bpm.engine.facade;

import com.sw.ck.bpm.api.dto.BpmActivityDTO;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link BpmRuntimeFacadeImpl} 单元测试。
 * <p>
 * 纯 Mockito + {@link MockitoExtension}，不装载 Spring 上下文。
 * 覆盖 {@link BpmRuntimeFacadeImpl#getActiveActivityIds(String)}
 * 和 {@link BpmRuntimeFacadeImpl#queryHistoricActivities(String)}。
 * </p>
 */
@DisplayName("BpmRuntimeFacadeImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class BpmRuntimeFacadeImplTest {

    @Mock
    private RuntimeService runtimeService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private BpmRuntimeFacadeImpl facade;

    // ==================== getActiveActivityIds ====================

    @Nested
    @DisplayName("getActiveActivityIds 方法")
    class GetActiveActivityIdsTests {

        @Test
        @DisplayName("正常：运行中实例返回活跃节点列表")
        void getActiveActivityIds_shouldReturnActiveIds() {
            when(runtimeService.getActiveActivityIds("pi-1"))
                    .thenReturn(List.of("Activity_1", "Activity_2"));

            List<String> ids = facade.getActiveActivityIds("pi-1").orElseThrow();

            assertThat(ids).containsExactly("Activity_1", "Activity_2");
            verify(runtimeService).getActiveActivityIds("pi-1");
        }

        @Test
        @DisplayName("边界：查询成功但无活跃节点 → present 空列表（合法零匹配）")
        void getActiveActivityIds_noActiveNodes_shouldReturnPresentEmptyList() {
            when(runtimeService.getActiveActivityIds("pi-finished"))
                    .thenReturn(List.of());

            assertThat(facade.getActiveActivityIds("pi-finished")).contains(List.of());
        }

        @Test
        @DisplayName("上下文缺失：null 或空字符串实例标识 → empty")
        void getActiveActivityIds_nullOrBlank_shouldReturnEmpty() {
            assertThat(facade.getActiveActivityIds(null)).isEmpty();
            assertThat(facade.getActiveActivityIds("")).isEmpty();
            assertThat(facade.getActiveActivityIds("  ")).isEmpty();

            verifyNoInteractions(runtimeService);
        }

        @Test
        @DisplayName("异常：RuntimeService 抛异常 → empty + 不向外抛（保留原容错行为）")
        void getActiveActivityIds_exception_shouldReturnEmpty() {
            when(runtimeService.getActiveActivityIds("pi-error"))
                    .thenThrow(new RuntimeException("Flowable error"));

            assertThat(facade.getActiveActivityIds("pi-error")).isEmpty();
            // 验证日志已调用（异常场景，验证不抛出即达标）
        }
    }

    // ==================== queryHistoricActivities ====================

    @Nested
    @DisplayName("queryHistoricActivities 方法")
    class QueryHistoricActivitiesTests {

        private HistoricActivityInstance createActivity(String activityId, String activityName,
                                                         String activityType, Date startTime,
                                                         Date endTime, String assignee, String taskId) {
            HistoricActivityInstance ha = mock(HistoricActivityInstance.class);
            when(ha.getActivityId()).thenReturn(activityId);
            when(ha.getActivityName()).thenReturn(activityName);
            when(ha.getActivityType()).thenReturn(activityType);
            when(ha.getStartTime()).thenReturn(startTime);
            when(ha.getEndTime()).thenReturn(endTime);
            when(ha.getAssignee()).thenReturn(assignee);
            when(ha.getTaskId()).thenReturn(taskId);
            return ha;
        }

        @Test
        @DisplayName("正常：返回所有历史活动节点（已完成 + 进行中）")
        void queryHistoricActivities_shouldReturnAllActivities() {
            // 模拟 HistoryService 查询链
            HistoricActivityInstanceQuery query = mock(HistoricActivityInstanceQuery.class);
            when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
            when(query.processInstanceId("pi-1")).thenReturn(query);

            // orderByHistoricActivityInstanceEndTime() → .asc() → 回到 query 继续链
            HistoricActivityInstanceQuery orderQuery = mock(HistoricActivityInstanceQuery.class);
            when(query.orderByHistoricActivityInstanceEndTime()).thenReturn(orderQuery);
            when(orderQuery.asc()).thenReturn(query);

            Date now = new Date();
            HistoricActivityInstance ha1 = createActivity(
                    "Activity_start", "开始", "startEvent",
                    now, now, null, null);
            HistoricActivityInstance ha2 = createActivity(
                    "Activity_approve", "经理审批", "userTask",
                    now, now, "approver1", "task-1");
            HistoricActivityInstance ha3 = createActivity(
                    "Activity_end", "结束", "endEvent",
                    now, now, null, null);

            when(query.list()).thenReturn(List.of(ha1, ha2, ha3));

            // assignee 兜底链：历史任务表 + 历史变量 approver（本用例 assignee 已有值，不触发）
            org.flowable.task.api.history.HistoricTaskInstanceQuery taskQuery =
                    mock(org.flowable.task.api.history.HistoricTaskInstanceQuery.class);
            when(historyService.createHistoricTaskInstanceQuery()).thenReturn(taskQuery);
            when(taskQuery.processInstanceId("pi-1")).thenReturn(taskQuery);
            when(taskQuery.list()).thenReturn(List.of());

            org.flowable.variable.api.history.HistoricVariableInstanceQuery varQuery =
                    mock(org.flowable.variable.api.history.HistoricVariableInstanceQuery.class);
            when(historyService.createHistoricVariableInstanceQuery()).thenReturn(varQuery);
            when(varQuery.processInstanceId("pi-1")).thenReturn(varQuery);
            when(varQuery.variableName("approver")).thenReturn(varQuery);
            when(varQuery.singleResult()).thenReturn(null);

            List<BpmActivityDTO> results = facade.queryHistoricActivities("pi-1").orElseThrow();

            assertThat(results).hasSize(3);
            assertThat(results.get(0).getActivityId()).isEqualTo("Activity_start");
            assertThat(results.get(0).getActivityType()).isEqualTo("startEvent");
            assertThat(results.get(0).getAssignee()).isNull();
            assertThat(results.get(1).getActivityId()).isEqualTo("Activity_approve");
            assertThat(results.get(1).getActivityType()).isEqualTo("userTask");
            assertThat(results.get(1).getAssignee()).isEqualTo("approver1");
            assertThat(results.get(1).getTaskId()).isEqualTo("task-1");
            assertThat(results.get(2).getActivityId()).isEqualTo("Activity_end");

            verify(historyService).createHistoricActivityInstanceQuery();
            verify(query).processInstanceId("pi-1");
        }

        @Test
        @DisplayName("兜底：userTask 历史行 assignee 为空 → 用历史变量 approver 补齐")
        void queryHistoricActivities_nullAssignee_shouldFallbackToApproverVariable() {
            HistoricActivityInstanceQuery query = mock(HistoricActivityInstanceQuery.class);
            when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
            when(query.processInstanceId("pi-2")).thenReturn(query);

            HistoricActivityInstanceQuery orderQuery = mock(HistoricActivityInstanceQuery.class);
            when(query.orderByHistoricActivityInstanceEndTime()).thenReturn(orderQuery);
            when(orderQuery.asc()).thenReturn(query);

            Date now = new Date();
            HistoricActivityInstance ha1 = createActivity(
                    "Activity_approve", "审批", "userTask", now, now, null, "task-9");
            when(query.list()).thenReturn(List.of(ha1));

            org.flowable.task.api.history.HistoricTaskInstanceQuery taskQuery =
                    mock(org.flowable.task.api.history.HistoricTaskInstanceQuery.class);
            when(historyService.createHistoricTaskInstanceQuery()).thenReturn(taskQuery);
            when(taskQuery.processInstanceId("pi-2")).thenReturn(taskQuery);
            when(taskQuery.list()).thenReturn(List.of());

            org.flowable.variable.api.history.HistoricVariableInstanceQuery varQuery =
                    mock(org.flowable.variable.api.history.HistoricVariableInstanceQuery.class);
            when(historyService.createHistoricVariableInstanceQuery()).thenReturn(varQuery);
            when(varQuery.processInstanceId("pi-2")).thenReturn(varQuery);
            when(varQuery.variableName("approver")).thenReturn(varQuery);
            org.flowable.variable.api.history.HistoricVariableInstance var =
                    mock(org.flowable.variable.api.history.HistoricVariableInstance.class);
            when(var.getValue()).thenReturn("1");
            when(varQuery.singleResult()).thenReturn(var);

            List<BpmActivityDTO> results = facade.queryHistoricActivities("pi-2").orElseThrow();

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getAssignee()).isEqualTo("1");
        }

        @Test
        @DisplayName("边界：查询成功但无历史记录 → present 空列表（合法零匹配）")
        void queryHistoricActivities_emptyHistory_shouldReturnPresentEmptyList() {
            HistoricActivityInstanceQuery query = mock(HistoricActivityInstanceQuery.class);
            when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
            when(query.processInstanceId("pi-empty")).thenReturn(query);

            HistoricActivityInstanceQuery orderQuery = mock(HistoricActivityInstanceQuery.class);
            when(query.orderByHistoricActivityInstanceEndTime()).thenReturn(orderQuery);
            when(orderQuery.asc()).thenReturn(query);

            when(query.list()).thenReturn(List.of());

            assertThat(facade.queryHistoricActivities("pi-empty")).contains(List.of());
        }

        @Test
        @DisplayName("上下文缺失：null 或空字符串实例标识 → empty")
        void queryHistoricActivities_nullOrBlank_shouldReturnEmpty() {
            assertThat(facade.queryHistoricActivities(null)).isEmpty();
            assertThat(facade.queryHistoricActivities("")).isEmpty();
            assertThat(facade.queryHistoricActivities("  ")).isEmpty();

            verifyNoInteractions(historyService);
        }

        @Test
        @DisplayName("异常：HistoryService 抛异常 → empty + 不向外抛（保留原容错行为）")
        void queryHistoricActivities_exception_shouldReturnEmpty() {
            HistoricActivityInstanceQuery query = mock(HistoricActivityInstanceQuery.class);
            when(historyService.createHistoricActivityInstanceQuery()).thenReturn(query);
            when(query.processInstanceId("pi-error")).thenReturn(query);

            HistoricActivityInstanceQuery orderQuery = mock(HistoricActivityInstanceQuery.class);
            when(query.orderByHistoricActivityInstanceEndTime()).thenReturn(orderQuery);
            when(orderQuery.asc()).thenReturn(query);

            when(query.list()).thenThrow(new RuntimeException("Flowable error"));

            assertThat(facade.queryHistoricActivities("pi-error")).isEmpty();
        }
    }
}
