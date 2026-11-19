package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.sw.ck.agent.entity.AgentGraphExecution;
import com.sw.ck.agent.entity.AgentGraphExecutionNode;
import com.sw.ck.agent.entity.AgentGraphExecutionRequest;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionEventMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionRequestMapper;
import com.sw.ck.agent.mapper.AgentGraphHumanTaskMapper;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.agent.vo.AgentGraphExecutionDetailVO;
import com.sw.ck.agent.vo.AgentGraphExecutionLifecycleVO;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.1.3 补丁回归：P72 子执行行回链受理行（详情/取消不 404）、P73 取消同步节点轨迹
 * 终态、重启收尾不再遗留「等待人工」子行。生产类已统一字段注入：全部依赖经
 * ReflectionTestUtils 注入（2026-11-19 治理批次同款测试侧适配）。
 */
@DisplayName("1.1.3 执行生命周期：子执行回链/取消轨迹同步/重启收尾")
class AgentGraphExecutionLifecycleRecoveryTest {

    private AgentGraphExecutionRequestMapper requestMapper;
    private AgentGraphExecutionEventMapper eventMapper;
    private AgentGraphHumanTaskMapper humanTaskMapper;
    private AgentGraphExecutionMapper executionMapper;
    private AgentGraphExecutionNodeMapper executionNodeMapper;
    private AgentGraphExecutionService executionService;
    private AgentGraphExecutionLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lifecycle-recovery-test"),
                AgentGraphExecutionRequest.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lifecycle-recovery-test"),
                AgentGraphExecution.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "lifecycle-recovery-test"),
                AgentGraphExecutionNode.class);
        requestMapper = mock(AgentGraphExecutionRequestMapper.class);
        eventMapper = mock(AgentGraphExecutionEventMapper.class);
        humanTaskMapper = mock(AgentGraphHumanTaskMapper.class);
        executionMapper = mock(AgentGraphExecutionMapper.class);
        executionNodeMapper = mock(AgentGraphExecutionNodeMapper.class);
        executionService = mock(AgentGraphExecutionService.class);
        LoginContextProvider loginContext = mock(LoginContextProvider.class);
        when(loginContext.getUserId()).thenReturn(1L);
        when(loginContext.isSuperAdmin()).thenReturn(false);

        service = new AgentGraphExecutionLifecycleServiceImpl();
        ReflectionTestUtils.setField(service, "requestMapper", requestMapper);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "humanTaskMapper", humanTaskMapper);
        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "executionNodeMapper", executionNodeMapper);
        ReflectionTestUtils.setField(service, "graphDefMapper", mock(AgentGraphDefMapper.class));
        ReflectionTestUtils.setField(service, "executionService", executionService);
        ReflectionTestUtils.setField(service, "loginContext", loginContext);
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "eventPublisher", mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "workers", 2);
        ReflectionTestUtils.setField(service, "queueCapacity", 8);

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setTenantId(100L);
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        // 未走 recover() 的用例线程池未建（executor 为 null），空判后关闭
        if (ReflectionTestUtils.getField(service, "executor") != null) {
            service.close();
        }
    }

    private AgentGraphExecutionRequest request(long id, long createBy, String status) {
        AgentGraphExecutionRequest request = new AgentGraphExecutionRequest();
        request.setId(id);
        request.setGraphDefId(21L);
        request.setGraphDefVersion(1);
        request.setStatus(status);
        request.setInput("input");
        request.setTenantId(100L);
        request.setCreateBy(createBy);
        return request;
    }

    private AgentGraphExecution executionRow(long id, Long parentExecutionId, String status) {
        AgentGraphExecution row = new AgentGraphExecution();
        row.setId(id);
        row.setGraphDefId(21L);
        row.setGraphDefVersion(1);
        row.setParentExecutionId(parentExecutionId);
        row.setStatus(status);
        row.setInput("{}");
        row.setTenantId(100L);
        row.setCreateBy(1L);
        return row;
    }

    @Test
    @DisplayName("P72：按子执行编号查详情回链受理行，返回子行自身视图不再 404")
    void get_byChildExecutionRow_resolvesRequestAndReturnsChildView() {
        AgentGraphExecutionRequest owner = request(5L, 1L, "SUCCESS");
        owner.setResultExecutionId(900L);
        AgentGraphExecution topRow = executionRow(900L, null, "SUCCESS");
        AgentGraphExecution childRow = executionRow(901L, 900L, "SUCCESS");
        childRow.setResultText("子图输出");

        when(requestMapper.selectById(901L)).thenReturn(null);
        // 第一问按 result_execution_id=901 未命中（子行不在受理簿）；第二问回链顶层 900 命中
        when(requestMapper.selectOne(any())).thenReturn(null).thenReturn(owner);
        when(executionMapper.selectById(901L)).thenReturn(childRow);
        when(executionMapper.selectById(900L)).thenReturn(topRow);

        AgentGraphExecutionLifecycleVO vo = service.get(901L);

        assertThat(vo.getExecutionId()).isEqualTo(5L);
        assertThat(vo.getResultExecutionId()).isEqualTo(901L);
        assertThat(vo.getStatus()).isEqualTo("SUCCESS");
        assertThat(vo.getOutput()).isEqualTo("子图输出");
    }

    @Test
    @DisplayName("P72：按子执行编号取消落在受理行，任务关闭、子行与结果行取消")
    void cancel_byChildExecutionRow_fallsOnRequestRow() {
        AgentGraphExecutionRequest waiting = request(5L, 1L, "WAITING_HUMAN");
        waiting.setResultExecutionId(900L);
        AgentGraphExecution topRow = executionRow(900L, null, "WAITING_HUMAN");
        AgentGraphExecution childRow = executionRow(901L, 900L, "WAITING_HUMAN");

        when(requestMapper.selectById(901L)).thenReturn(null);
        when(requestMapper.selectOne(any())).thenReturn(null).thenReturn(waiting);
        when(executionMapper.selectById(901L)).thenReturn(childRow);
        when(executionMapper.selectById(900L)).thenReturn(topRow);
        when(executionMapper.selectList(any())).thenReturn(List.of(childRow));
        when(requestMapper.cancelWaiting(5L)).thenReturn(1);
        when(requestMapper.selectById(5L)).thenReturn(waiting);
        when(executionService.getExecution(900L)).thenReturn(new AgentGraphExecutionDetailVO());

        AgentGraphExecutionLifecycleVO vo = service.cancel(901L);

        assertThat(vo.getExecutionId()).isEqualTo(5L);
        verify(humanTaskMapper).cancelPending(5L);
        // 级联取消子行 901 + 结果行 900 同步取消（G1 行为保持，各一次）
        verify(executionMapper, times(2)).updateById(any(AgentGraphExecution.class));
        verify(requestMapper).cancelWaiting(5L);
    }

    @Test
    @DisplayName("P73：取消人工等待时，结果行与子行的 WAITING 节点轨迹同步改判 CANCELLED")
    void cancel_waitingHuman_marksWaitingNodeTracesCancelled() {
        AgentGraphExecutionRequest waiting = request(5L, 1L, "WAITING_HUMAN");
        waiting.setResultExecutionId(900L);
        AgentGraphExecution topRow = executionRow(900L, null, "WAITING_HUMAN");
        AgentGraphExecution childRow = executionRow(901L, 900L, "WAITING_HUMAN");

        when(requestMapper.selectById(5L)).thenReturn(waiting);
        when(requestMapper.cancelWaiting(5L)).thenReturn(1);
        when(executionMapper.selectById(900L)).thenReturn(topRow);
        when(executionMapper.selectList(any())).thenReturn(List.of(childRow));
        when(executionService.getExecution(900L)).thenReturn(new AgentGraphExecutionDetailVO());

        service.cancel(5L);

        // 结果行 900 与级联取消的子行 901 各改判一次（共两次 WAITING→CANCELLED）
        verify(executionNodeMapper, times(2))
                .update(eq(null), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    @DisplayName("P72 同族：重启判 INTERRUPTED 的受理行，等待中的子行与死任务一并收尾")
    void recover_interruptsWaitingChildRowsAndClosesTasks() {
        AgentGraphExecutionRequest running = request(5L, 1L, "RUNNING");
        running.setResultExecutionId(900L);
        AgentGraphExecution topRow = executionRow(900L, null, "RUNNING");
        AgentGraphExecution childRow = executionRow(901L, 900L, "WAITING_HUMAN");

        when(requestMapper.selectCount(any())).thenReturn(0L);
        // 第一问取在途受理行；第二问（QUEUED 重放清单）为空
        when(requestMapper.selectList(any())).thenReturn(List.of(running)).thenReturn(List.of());
        when(executionMapper.selectById(900L)).thenReturn(topRow);
        when(executionMapper.selectList(any())).thenReturn(List.of(childRow));

        service.recover();

        verify(humanTaskMapper).cancelPending(5L);
        verify(humanTaskMapper).cancelPending(901L);
        assertThat(childRow.getStatus()).isEqualTo("INTERRUPTED");
        assertThat(topRow.getStatus()).isEqualTo("INTERRUPTED");
        verify(executionMapper).updateById(childRow);
        verify(executionMapper).updateById(topRow);
    }
}
