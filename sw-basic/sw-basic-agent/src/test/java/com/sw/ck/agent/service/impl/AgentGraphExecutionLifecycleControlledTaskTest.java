package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.sw.ck.agent.dto.AgentGraphExecuteRespDTO;
import com.sw.ck.agent.entity.AgentGraphExecutionRequest;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionEventMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionRequestMapper;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentGraphExecutionLifecycleControlledTaskTest {
    private AgentGraphExecutionLifecycleServiceImpl service;

    @AfterEach
    void close() {
        if (service != null) service.close();
    }

    @Test
    void queuedCancellationNeverExecutesExternalSideEffect() {
        Fixture f = fixture();
        AgentGraphExecutionRequest queued = request(11L, "QUEUED");
        when(f.requestMapper.selectById(11L)).thenReturn(queued, request(11L, "CANCELLED"));
        when(f.requestMapper.cancelQueued(11L)).thenReturn(1);

        assertThat(service.cancel(11L).getStatus()).isEqualTo("CANCELLED");
        verify(f.executionService, never()).execute(anyLong(), any());
        verify(f.eventMapper, times(1)).insertNext(anyLong(), eq(100L), any(), eq(11L), eq("STATE"), any(), eq("CANCELLED"));
    }

    @Test
    void runningCancellationWinsRaceAndSideEffectRunsExactlyOnce() throws Exception {
        Fixture f = fixture();
        AgentGraphExecutionRequest queued = request(12L, "QUEUED");
        AgentGraphExecutionRequest running = request(12L, "RUNNING");
        AgentGraphExecutionRequest requested = request(12L, "CANCEL_REQUESTED");
        AtomicReference<AgentGraphExecutionRequest> current = new AtomicReference<>(queued);
        when(f.requestMapper.transition(12L, "QUEUED", "RUNNING")).thenAnswer(inv -> { current.set(running); return 1; });
        when(f.requestMapper.selectById(12L)).thenAnswer(inv -> current.get());
        when(f.requestMapper.updateById(any(AgentGraphExecutionRequest.class))).thenReturn(1);
        when(f.requestMapper.requestRunningCancellation(12L)).thenAnswer(inv -> { current.set(requested); return 1; });
        when(f.requestMapper.finish(eq(12L), eq("CANCELLED"), any(), any(), any(), anyLong())).thenAnswer(inv -> { current.set(request(12L, "CANCELLED")); return 1; });
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sideEffects = new AtomicInteger();
        when(f.executionService.execute(21L, "input")).thenAnswer(inv -> {
            sideEffects.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            AgentGraphExecuteRespDTO dto = new AgentGraphExecuteRespDTO();
            dto.setSuccess(true);
            return dto;
        });

        Thread worker = Thread.ofVirtual().start(() -> invokeRun(12L));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.cancel(12L).getStatus()).isEqualTo("CANCEL_REQUESTED");
        release.countDown();
        worker.join(5000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(sideEffects).hasValue(1);
        verify(f.requestMapper, times(1)).finish(eq(12L), eq("CANCELLED"), any(), any(), any(), anyLong());
    }

    @Test
    void restartMarksUncertainRunningAndReplaysQueuedExactlyOnce() throws Exception {
        Fixture f = fixture();
        AgentGraphExecutionRequest queued = request(13L, "QUEUED");
        when(f.requestMapper.selectCount(any())).thenReturn(0L);
        when(f.requestMapper.update(any(), any())).thenReturn(1);
        when(f.requestMapper.selectList(any())).thenReturn(List.of(queued));
        when(f.requestMapper.transition(13L, "QUEUED", "RUNNING")).thenReturn(1);
        when(f.requestMapper.selectById(13L)).thenReturn(queued, request(13L, "RUNNING"), request(13L, "SUCCESS"));
        when(f.requestMapper.updateById(any(AgentGraphExecutionRequest.class))).thenReturn(1);
        when(f.requestMapper.finish(eq(13L), eq("SUCCESS"), any(), any(), any(), anyLong())).thenReturn(1);
        CountDownLatch executed = new CountDownLatch(1);
        AtomicInteger sideEffects = new AtomicInteger();
        when(f.executionService.execute(21L, "input")).thenAnswer(inv -> {
            sideEffects.incrementAndGet(); executed.countDown();
            AgentGraphExecuteRespDTO dto = new AgentGraphExecuteRespDTO(); dto.setSuccess(true); return dto;
        });

        service.recover();
        assertThat(executed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sideEffects).hasValue(1);
        verify(f.requestMapper, times(1)).update(isNull(), any());
        verify(f.executionService, times(1)).execute(21L, "input");
    }

    private Fixture fixture() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "controlled-task-test"),
                AgentGraphExecutionRequest.class);
        AgentGraphExecutionRequestMapper requests = mock(AgentGraphExecutionRequestMapper.class);
        AgentGraphExecutionEventMapper events = mock(AgentGraphExecutionEventMapper.class);
        AgentGraphDefMapper graphs = mock(AgentGraphDefMapper.class);
        AgentGraphExecutionService executions = mock(AgentGraphExecutionService.class);
        LoginContextProvider login = mock(LoginContextProvider.class);
        when(login.getUserId()).thenReturn(1L);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        service = new AgentGraphExecutionLifecycleServiceImpl(requests, events, graphs, executions, login, tx, 1, 2);
        return new Fixture(requests, events, executions);
    }

    private AgentGraphExecutionRequest request(long id, String status) {
        AgentGraphExecutionRequest request = new AgentGraphExecutionRequest();
        request.setId(id); request.setGraphDefId(21L); request.setGraphDefVersion(1);
        request.setStatus(status); request.setInput("input"); request.setTenantId(100L); request.setCreateBy(1L);
        return request;
    }

    private void invokeRun(long id) {
        try {
            Method method = AgentGraphExecutionLifecycleServiceImpl.class.getDeclaredMethod("run", Long.class, LoginUser.class);
            method.setAccessible(true);
            LoginUser user = new LoginUser(); user.setUserId(1L); user.setTenantId(100L);
            method.invoke(service, id, user);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private record Fixture(AgentGraphExecutionRequestMapper requestMapper,
                           AgentGraphExecutionEventMapper eventMapper,
                           AgentGraphExecutionService executionService) {}
}
