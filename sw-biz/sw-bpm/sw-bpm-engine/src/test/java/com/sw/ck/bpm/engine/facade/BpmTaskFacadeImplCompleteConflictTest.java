package com.sw.ck.bpm.engine.facade;

import com.sw.ck.common.exception.BaseException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BpmTaskFacadeImpl} 完成路径乐观锁语义回归（复审05 P1-04a，X7 双激活修复）。
 * <p>
 * 缺陷实证：乐观锁冲突发生时若在本事务内二次执行完成，已执行的副作用会被重复提交
 * （多实例延续两次、下游节点双重激活）。现语义：任务已不存在 = 幂等竞争 → 2305；
 * 任务仍存在 = 并发写冲突 → 抛原始冲突让整个事务回滚、由外层在新事务受控重试，
 * 绝不在本事务内重放。本测试钉死"仅执行一次完成调用"。
 * </p>
 */
@DisplayName("任务完成乐观锁冲突语义测试")
class BpmTaskFacadeImplCompleteConflictTest {

    private final TaskService taskService = mock(TaskService.class);
    private final TaskQuery taskQuery = mock(TaskQuery.class);
    private final BpmTaskFacadeImpl facade = new BpmTaskFacadeImpl(taskService,
            mock(RuntimeService.class), mock(RepositoryService.class), mock(HistoryService.class));

    private Task task(String taskId) {
        Task task = mock(Task.class);
        when(task.getProcessInstanceId()).thenReturn("pi-1");
        return task;
    }

    @Test
    @DisplayName("冲突且任务仍存在：抛原始乐观锁异常，完成调用恰一次（禁止同事务重放）")
    void shouldPropagateConflictWithoutReplayWhenTaskStillActive() {
        Task active = task("t1");
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId("t1")).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(active);
        Map<String, Object> variables = Map.of("outcome", "APPROVE");
        org.mockito.Mockito.doThrow(new FlowableOptimisticLockingException("rev conflict"))
                .when(taskService).complete(eq("t1"), anyMap());

        assertThatThrownBy(() -> facade.complete("t1", variables))
                .isInstanceOf(FlowableOptimisticLockingException.class);

        verify(taskService, times(1)).complete(eq("t1"), anyMap());
    }

    @Test
    @DisplayName("冲突且任务已不存在：转换为可预期 2305（幂等竞争，不暴露 500）")
    void shouldConvertToAlreadyHandledWhenTaskGone() {
        Task active = task("t1");
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId("t1")).thenReturn(taskQuery);
        // 入口快照 + 前置检查返回任务；冲突后复核返回 null（竞争者已提交完成）
        when(taskQuery.singleResult()).thenReturn(active, active, null);
        org.mockito.Mockito.doThrow(new FlowableOptimisticLockingException("rev conflict"))
                .when(taskService).complete(eq("t1"), anyMap());

        assertThatThrownBy(() -> facade.complete("t1", Map.of("outcome", "APPROVE")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("任务不存在或已被处理");

        verify(taskService, times(1)).complete(eq("t1"), anyMap());
    }
}
