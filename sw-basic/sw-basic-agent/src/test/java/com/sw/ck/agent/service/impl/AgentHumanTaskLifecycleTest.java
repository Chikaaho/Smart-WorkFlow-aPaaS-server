package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sw.ck.agent.dto.AgentGraphExecuteRespDTO;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphExecutionRequest;
import com.sw.ck.agent.entity.AgentGraphHumanTask;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionEventMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionRequestMapper;
import com.sw.ck.agent.mapper.AgentGraphHumanTaskMapper;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** G3：人工任务等待/提交/超时/身份校验（mock 持久层与执行服务）。 */
@DisplayName("G3 人工任务生命周期")
class AgentHumanTaskLifecycleTest {

    private AgentGraphExecutionRequestMapper requestMapper;
    private AgentGraphExecutionEventMapper eventMapper;
    private AgentGraphHumanTaskMapper humanTaskMapper;
    private com.sw.ck.agent.mapper.AgentGraphExecutionMapper executionMapper;
    private AgentGraphDefMapper graphDefMapper;
    private AgentGraphExecutionService executionService;
    private LoginContextProvider loginContext;
    private AgentGraphExecutionLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "human-task-test"),
                AgentGraphExecutionRequest.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "human-task-test"),
                AgentGraphHumanTask.class);
        requestMapper = mock(AgentGraphExecutionRequestMapper.class);
        eventMapper = mock(AgentGraphExecutionEventMapper.class);
        humanTaskMapper = mock(AgentGraphHumanTaskMapper.class);
        executionMapper = mock(com.sw.ck.agent.mapper.AgentGraphExecutionMapper.class);
        graphDefMapper = mock(AgentGraphDefMapper.class);
        executionService = mock(AgentGraphExecutionService.class);
        loginContext = mock(LoginContextProvider.class);
        when(loginContext.getUserId()).thenReturn(1L);
        service = new AgentGraphExecutionLifecycleServiceImpl(
                requestMapper, eventMapper, humanTaskMapper, executionMapper, graphDefMapper, executionService,
                loginContext, mock(PlatformTransactionManager.class),
                mock(ApplicationEventPublisher.class), 2, 8);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private AgentGraphExecutionRequest request(long id, String status) {
        AgentGraphExecutionRequest request = new AgentGraphExecutionRequest();
        request.setId(id);
        request.setGraphDefId(21L);
        request.setGraphDefVersion(1);
        request.setStatus(status);
        request.setInput("input");
        request.setTenantId(100L);
        request.setCreateBy(1L);
        return request;
    }

    private AgentGraphExecutionRequest waitingRequest(long id) {
        AgentGraphExecutionRequest waiting = request(id, "WAITING_HUMAN");
        waiting.setPauseStateJson(
                "{\"activePoints\":[{\"nodeId\":\"human-1\",\"branchPath\":\"0\"}],"
                        + "\"variables\":{\"input\":\"input\"}}");
        return waiting;
    }

    private AgentGraphExecutionService.ExecutionOutcome pausedOutcome() {
        AgentGraphInterpreter.HumanTaskSpec spec = new AgentGraphInterpreter.HumanTaskSpec();
        spec.humanNodeId = "human-1";
        spec.instructions = "请确认";
        spec.assigneeUserIds = List.of(1L);
        AgentGraphInterpreter.PauseState state = new AgentGraphInterpreter.PauseState();
        state.activePoints.add(new AgentGraphInterpreter.ActivePointState("human-1", "0"));
        state.variables.put("input", "input");
        AgentGraphExecutionService.ExecutionOutcome outcome =
                new AgentGraphExecutionService.ExecutionOutcome();
        outcome.response = new AgentGraphExecuteRespDTO();
        outcome.pauseState = state;
        outcome.humanTask = spec;
        outcome.executionId = 900L;
        return outcome;
    }

    private AgentGraphExecutionService.ExecutionOutcome successOutcome() {
        AgentGraphExecuteRespDTO dto = new AgentGraphExecuteRespDTO();
        dto.setSuccess(true);
        dto.setOutput("完成");
        dto.setExecutionId(901L);
        AgentGraphExecutionService.ExecutionOutcome outcome =
                new AgentGraphExecutionService.ExecutionOutcome();
        outcome.response = dto;
        outcome.executionId = 901L;
        return outcome;
    }

    private void invokeRun(long id) {
        try {
            java.lang.reflect.Method method = AgentGraphExecutionLifecycleServiceImpl.class
                    .getDeclaredMethod("run", Long.class, LoginUser.class);
            method.setAccessible(true);
            LoginUser user = new LoginUser();
            user.setUserId(1L);
            user.setTenantId(100L);
            method.invoke(service, id, user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("运行到人工节点：执行转为 WAITING_HUMAN，落暂停状态与待处理任务")
    void runPausesAndPersistsHumanTask() throws Exception {
        AgentGraphDef graph = new AgentGraphDef();
        graph.setId(21L);
        graph.setDefVersion(1);
        graph.setStatus("PUBLISHED");
        when(graphDefMapper.selectById(21L)).thenReturn(graph);
        AgentGraphExecutionRequest running = request(5L, "RUNNING");
        when(requestMapper.transition(5L, "QUEUED", "RUNNING")).thenReturn(1);
        when(requestMapper.selectById(5L)).thenReturn(running);
        when(requestMapper.updateById((AgentGraphExecutionRequest) any())).thenReturn(1);
        when(executionService.runLifecycle(eq(21L), eq(1), eq("input"), any(), any(), any(), any()))
                .thenReturn(pausedOutcome());

        invokeRun(5L);

        verify(humanTaskMapper, times(1)).insert(any(AgentGraphHumanTask.class));
        org.mockito.ArgumentCaptor<AgentGraphExecutionRequest> updateCaptor =
                org.mockito.ArgumentCaptor.forClass(AgentGraphExecutionRequest.class);
        verify(requestMapper, org.mockito.Mockito.atLeastOnce()).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getAllValues().get(updateCaptor.getAllValues().size() - 1)
                .getStatus()).isEqualTo("WAITING_HUMAN");
        assertThat(updateCaptor.getAllValues().get(updateCaptor.getAllValues().size() - 1)
                .getPauseStateJson()).isNotNull();
        verify(eventMapper, times(1)).insertNext(anyLong(), eq(100L), eq("1"), eq(5L),
                eq("STATE"), eq("human-1"), eq("WAITING_HUMAN"));
    }

    @Test
    @DisplayName("提交人工任务：处理人校验 + 认领 + 重新派发并完成执行")
    void submitHumanTaskClaimsAndDispatches() throws Exception {
        java.util.concurrent.atomic.AtomicReference<AgentGraphExecutionRequest> current =
                new java.util.concurrent.atomic.AtomicReference<>(waitingRequest(5L));
        when(requestMapper.selectById(5L)).thenAnswer(inv -> current.get());
        AgentGraphHumanTask task = new AgentGraphHumanTask();
        task.setId(77L);
        task.setExecutionId(5L);
        task.setNodeId("human-1");
        task.setStatus("PENDING");
        task.setAssigneeUserId(1L);
        when(humanTaskMapper.selectById(77L)).thenReturn(task);
        when(humanTaskMapper.claimSubmitted(eq(77L), any(), eq(1L))).thenReturn(1);
        when(humanTaskMapper.completeSiblings(5L, "human-1")).thenReturn(1);
        when(requestMapper.transition(5L, "WAITING_HUMAN", "RUNNING")).thenAnswer(inv -> {
            AgentGraphExecutionRequest running = waitingRequest(5L);
            running.setStatus("RUNNING");
            current.set(running);
            return 1;
        });
        AgentGraphDef graph = new AgentGraphDef();
        graph.setId(21L);
        graph.setDefVersion(1);
        graph.setStatus("PUBLISHED");
        when(graphDefMapper.selectById(21L)).thenReturn(graph);
        CountDownLatch resumed = new CountDownLatch(1);
        when(executionService.runLifecycle(eq(21L), eq(1), eq("input"), any(), any(), any(), any()))
                .thenAnswer(inv -> successOutcome());
        // CI 稳定性：闩锁挂在 finish 上——恢复线程在 runLifecycle 返回后还要走 finish 收尾，
        // 若在 runLifecycle 处放行，verify(finish) 与异步收尾存在竞态（本地快、runner 慢时必现）。
        when(requestMapper.finish(eq(5L), eq("SUCCESS"), any(), any(), eq(901L), anyLong(), any()))
                .thenAnswer(inv -> {
                    resumed.countDown();
                    return 1;
                });

        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(100L);
        LoginUserHolder.set(user);
        service.submitHumanTask(5L, 77L, Map.of("comment", "同意"), "approve");

        assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
        verify(humanTaskMapper).claimSubmitted(eq(77L), any(), eq(1L));
        verify(humanTaskMapper).completeSiblings(5L, "human-1");
        verify(requestMapper).transition(5L, "WAITING_HUMAN", "RUNNING");
        verify(requestMapper).finish(eq(5L), eq("SUCCESS"), any(), any(), eq(901L), anyLong(), any());
    }

    @Test
    @DisplayName("非处理人提交：拒绝；重复提交：拒绝")
    void submitRejectsWrongAssigneeAndDuplicate() {
        AgentGraphExecutionRequest waiting = request(5L, "WAITING_HUMAN");
        when(requestMapper.selectById(5L)).thenReturn(waiting);
        AgentGraphHumanTask task = new AgentGraphHumanTask();
        task.setId(77L);
        task.setExecutionId(5L);
        task.setNodeId("human-1");
        task.setStatus("PENDING");
        task.setAssigneeUserId(9L);
        when(humanTaskMapper.selectById(77L)).thenReturn(task);

        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(100L);
        LoginUserHolder.set(user);
        assertThatThrownBy(() -> service.submitHumanTask(5L, 77L, Map.of(), "approve"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不是该等待的处理人");

        task.setAssigneeUserId(1L);
        when(humanTaskMapper.claimSubmitted(eq(77L), any(), eq(1L))).thenReturn(0);
        assertThatThrownBy(() -> service.submitHumanTask(5L, 77L, Map.of(), "approve"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不能重复提交");
        verify(humanTaskMapper, never()).completeSiblings(anyLong(), any());
    }

    @Test
    @DisplayName("超时扫描：过期 PENDING 任务认领为 TIMEOUT 并重新派发")
    void timeoutScanClaimsAndRedispatches() throws Exception {
        AgentGraphHumanTask overdue = new AgentGraphHumanTask();
        overdue.setId(88L);
        overdue.setExecutionId(6L);
        overdue.setNodeId("human-1");
        overdue.setStatus("PENDING");
        overdue.setAssigneeUserId(1L);
        overdue.setPayloadJson("{\"timeoutSeconds\":1}");
        overdue.setCreateTime(LocalDateTime.now().minusSeconds(5));
        when(humanTaskMapper.selectList(any())).thenReturn(List.of(overdue));
        java.util.concurrent.atomic.AtomicReference<AgentGraphExecutionRequest> current =
                new java.util.concurrent.atomic.AtomicReference<>(waitingRequest(6L));
        when(requestMapper.selectById(6L)).thenAnswer(inv -> current.get());
        when(humanTaskMapper.claimTimeout(eq(88L), any())).thenReturn(1);
        when(humanTaskMapper.timeoutSiblings(6L, "human-1")).thenReturn(1);
        when(requestMapper.transition(6L, "WAITING_HUMAN", "RUNNING")).thenAnswer(inv -> {
            AgentGraphExecutionRequest running = waitingRequest(6L);
            running.setStatus("RUNNING");
            current.set(running);
            return 1;
        });
        AgentGraphHumanTask decided = new AgentGraphHumanTask();
        decided.setNodeId("human-1");
        decided.setStatus("TIMEOUT");
        decided.setResultJson("{\"decision\":\"__TIMEOUT__\",\"values\":{}}");
        when(humanTaskMapper.selectOne(any())).thenReturn(decided);
        AgentGraphDef graph = new AgentGraphDef();
        graph.setId(21L);
        graph.setDefVersion(1);
        graph.setStatus("PUBLISHED");
        when(graphDefMapper.selectById(21L)).thenReturn(graph);
        AtomicReference<List<AgentGraphInterpreter.HumanDecision>> decisions =
                new AtomicReference<>();
        CountDownLatch resumed = new CountDownLatch(1);
        when(executionService.runLifecycle(eq(21L), eq(1), eq("input"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    decisions.set(inv.getArgument(5));
                    resumed.countDown();
                    return successOutcome();
                });

        service.scanHumanTaskTimeouts();

        assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(decisions.get()).isNotNull();
        assertThat(decisions.get()).hasSize(1);
        assertThat(decisions.get().get(0).decision)
                .isEqualTo(AgentGraphInterpreter.HUMAN_TIMEOUT_DECISION);
        verify(humanTaskMapper).claimTimeout(eq(88L), any());
        verify(humanTaskMapper).timeoutSiblings(6L, "human-1");
    }

    @Test
    @DisplayName("V3（planning-review-110-02）：提交恢复复用暂停前执行记录，同一 executionId")
    void submitResumesSameExecutionRecord() throws Exception {
        AgentGraphExecutionRequest waiting = waitingRequest(5L);
        waiting.setResultExecutionId(900L);
        java.util.concurrent.atomic.AtomicReference<AgentGraphExecutionRequest> current =
                new java.util.concurrent.atomic.AtomicReference<>(waiting);
        when(requestMapper.selectById(5L)).thenAnswer(inv -> current.get());
        AgentGraphHumanTask task = new AgentGraphHumanTask();
        task.setId(77L);
        task.setExecutionId(5L);
        task.setNodeId("human-1");
        task.setStatus("PENDING");
        task.setAssigneeUserId(1L);
        when(humanTaskMapper.selectById(77L)).thenReturn(task);
        when(humanTaskMapper.claimSubmitted(eq(77L), any(), eq(1L))).thenReturn(1);
        when(humanTaskMapper.completeSiblings(5L, "human-1")).thenReturn(1);
        when(requestMapper.transition(5L, "WAITING_HUMAN", "RUNNING")).thenAnswer(inv -> {
            AgentGraphExecutionRequest running = waitingRequest(5L);
            running.setStatus("RUNNING");
            running.setResultExecutionId(900L);
            current.set(running);
            return 1;
        });
        AgentGraphDef graph = new AgentGraphDef();
        graph.setId(21L);
        graph.setDefVersion(1);
        graph.setStatus("PUBLISHED");
        when(graphDefMapper.selectById(21L)).thenReturn(graph);
        CountDownLatch resumed = new CountDownLatch(1);
        // 恢复执行返回与暂停前相同的执行记录 id（runInternal 复用该行）
        when(executionService.runLifecycle(eq(21L), eq(1), eq("input"), any(), any(), any(),
                eq(900L)))
                .thenAnswer(inv -> {
                    AgentGraphExecutionService.ExecutionOutcome outcome = successOutcome();
                    outcome.executionId = 900L;
                    outcome.response.setExecutionId(900L);
                    return outcome;
                });
        // 闩锁挂在 finish 上，保证 verify(finish) 前异步收尾已完成（同 CI flake 修复）
        when(requestMapper.finish(eq(5L), eq("SUCCESS"), any(), any(), eq(900L), anyLong(), any()))
                .thenAnswer(inv -> {
                    resumed.countDown();
                    return 1;
                });
        when(executionService.getExecution(900L))
                .thenReturn(new com.sw.ck.agent.dto.AgentGraphExecutionDetailDTO());

        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(100L);
        LoginUserHolder.set(user);
        service.submitHumanTask(5L, 77L, Map.of("comment", "同意"), "approve");

        assertThat(resumed.await(5, TimeUnit.SECONDS)).isTrue();
        // 恢复调用必须携带暂停前执行记录 id；终态回执仍是同一 id
        verify(executionService).runLifecycle(eq(21L), eq(1), eq("input"), any(), any(), any(),
                eq(900L));
        verify(requestMapper).finish(eq(5L), eq("SUCCESS"), any(), any(), eq(900L), anyLong(),
                any());
    }

    @Test
    @DisplayName("G3-3：等待人工中取消——任务关闭、执行转 CANCELLED")
    void cancelWhileWaitingHumanCancelsTasks() {
        java.util.concurrent.atomic.AtomicReference<AgentGraphExecutionRequest> current =
                new java.util.concurrent.atomic.AtomicReference<>(waitingRequest(5L));
        when(requestMapper.selectById(5L)).thenAnswer(inv -> current.get());
        when(requestMapper.cancelQueued(5L)).thenReturn(0);
        when(requestMapper.cancelWaiting(5L)).thenAnswer(inv -> {
            AgentGraphExecutionRequest cancelled = waitingRequest(5L);
            cancelled.setStatus("CANCELLED");
            current.set(cancelled);
            return 1;
        });
        when(humanTaskMapper.cancelPending(5L)).thenReturn(2);

        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(100L);
        LoginUserHolder.set(user);
        var dto = service.cancel(5L);

        assertThat(dto.getStatus()).isEqualTo("CANCELLED");
        verify(humanTaskMapper).cancelPending(5L);
        verify(eventMapper).insertNext(anyLong(), eq(100L), eq("1"), eq(5L), eq("STATE"), any(),
                eq("CANCELLED"));
    }
}
