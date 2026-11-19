package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphExecution;
import com.sw.ck.agent.entity.AgentGraphExecutionNode;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.1.3/P72 核心回归：子图人工暂停 → 超时恢复轮必须复用暂停前的子执行行
 * （对齐顶层 V3「恢复前后同一条执行」），不得新建行导致旧行永远停在 WAITING_HUMAN。
 * 生产类已统一字段注入：依赖经 ReflectionTestUtils 注入（2026-11-19 治理批次同款）。
 */
@DisplayName("1.1.3 子图执行行恢复复用")
class AgentGraphSubgraphExecutionRowReuseTest {

    private AgentGraphDefMapper graphDefMapper;
    private AgentGraphExecutionMapper executionMapper;
    private AgentGraphExecutionNodeMapper executionNodeMapper;
    private AgentGraphExecutionServiceImpl service;
    private Method runSubgraph;

    @BeforeEach
    void setUp() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "subgraph-reuse-test"),
                AgentGraphExecution.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "subgraph-reuse-test"),
                AgentGraphExecutionNode.class);

        graphDefMapper = mock(AgentGraphDefMapper.class);
        executionMapper = mock(AgentGraphExecutionMapper.class);
        executionNodeMapper = mock(AgentGraphExecutionNodeMapper.class);
        AgentGraphSnapshotServiceImpl snapshotService = mock(AgentGraphSnapshotServiceImpl.class);
        LoginContextProvider loginContext = mock(LoginContextProvider.class);
        when(loginContext.getTenantId()).thenReturn(100L);

        // 子图：START → HUMAN（超时分支）→ END
        ProcessGraph childGraph = graphOf(
                node("start", "START"),
                node("human", "HUMAN", humanConfig()),
                node("end", "END"),
                edge("e1", "start", "human", null),
                edge("e2", "human", "end", "approve"),
                edge("e3", "human", "end", "timeout"));

        AgentGraphDef childDef = new AgentGraphDef();
        childDef.setId(31L);
        childDef.setStatus("PUBLISHED");
        childDef.setDefVersion(1);
        when(graphDefMapper.selectById(31L)).thenReturn(childDef);
        when(snapshotService.loadGraphByVersion(childDef, 1)).thenReturn(childGraph);

        // insert 时回填行 id，模拟 IdWorker 主键生成
        when(executionMapper.insert(any(AgentGraphExecution.class))).thenAnswer(invocation -> {
            AgentGraphExecution row = invocation.getArgument(0);
            row.setId(901L);
            return 1;
        });

        service = new AgentGraphExecutionServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", graphDefMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "modelRuntimeService", mock(AgentModelRuntimeService.class));
        ReflectionTestUtils.setField(service, "snapshotService", snapshotService);
        ReflectionTestUtils.setField(service, "internalToolMapper", mock(AgentToolInternalConfigMapper.class));
        ReflectionTestUtils.setField(service, "externalToolMapper", mock(AgentToolExternalConfigMapper.class));
        ReflectionTestUtils.setField(service, "executionMapper", executionMapper);
        ReflectionTestUtils.setField(service, "executionNodeMapper", executionNodeMapper);
        ReflectionTestUtils.setField(service, "loginContextProvider", loginContext);
        ReflectionTestUtils.setField(service, "deptScopeProvider", mock(DeptScopeProvider.class));

        runSubgraph = AgentGraphExecutionServiceImpl.class
                .getDeclaredMethod("runSubgraph", AgentGraphInterpreter.SubgraphCall.class,
                        Long.class, int.class, Set.class);
        runSubgraph.setAccessible(true);
    }

    private static GraphElement node(String id, String type) {
        return GraphElement.builder().id(id).kind("node").type(type).config(new HashMap<>()).build();
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type).config(config).build();
    }

    private static GraphElement edge(String id, String source, String target, String keyword) {
        Map<String, Object> config = new HashMap<>();
        if (keyword != null) {
            config.put("keyword", keyword);
        }
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(config).build();
    }

    private static ProcessGraph graphOf(GraphElement... elements) {
        return ProcessGraph.builder().graphKey("child").name("child").version(1)
                .elements(List.of(elements)).canvas(Map.of()).build();
    }

    private static Map<String, Object> humanConfig() {
        return Map.of(
                "instructions", "请确认 {{input}}",
                "buttons", List.of(Map.of("keyword", "approve", "label", "通过")),
                "assigneeUserIds", List.of(7),
                "timeoutSeconds", 60,
                "timeoutKeyword", "timeout");
    }

    private AgentGraphInterpreter.SubgraphCall callWithResume(AgentGraphInterpreter.PauseState resumeState,
                                                               List<AgentGraphInterpreter.HumanDecision> decisions) {
        AgentGraphInterpreter.SubgraphCall call =
                new AgentGraphInterpreter.SubgraphCall(31L, 1, Map.of("input", "内容"));
        call.resumeState = resumeState;
        call.decisions = decisions;
        return call;
    }

    /** 首跑：子图运行到人工节点暂停，断言新建行 + 暂停状态携带行 id。 */
    private AgentGraphInterpreter.PauseState firstRunPauses() throws Exception {
        try {
            runSubgraph.invoke(service, callWithResume(null, List.of()), 900L, 0, new HashSet<Long>());
            throw new AssertionError("首跑应抛人工暂停异常");
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            assertThat(cause).isInstanceOf(AgentGraphInterpreter.HumanPauseException.class);
            AgentGraphInterpreter.PauseState saved =
                    ((AgentGraphInterpreter.HumanPauseException) cause).getPauseState();
            // P72：暂停状态必须携带子执行行 id，恢复轮凭它复用
            assertThat(saved.executionRowId).isEqualTo(901L);
            return saved;
        }
    }

    @Test
    @DisplayName("P72：超时恢复轮复用暂停前的子执行行——不新建行，旧行写终态 SUCCESS")
    void resume_reusesPausedChildRow_insteadOfInsertingNew() throws Exception {
        AgentGraphInterpreter.PauseState saved = firstRunPauses();
        verify(executionMapper, times(1)).insert(any(AgentGraphExecution.class));

        // 恢复：受理簿回查旧行（WAITING_HUMAN），子图以 TIMEOUT 决策续跑
        AgentGraphExecution pausedRow = new AgentGraphExecution();
        pausedRow.setId(901L);
        pausedRow.setGraphDefId(31L);
        pausedRow.setGraphDefVersion(1);
        pausedRow.setParentExecutionId(900L);
        pausedRow.setStatus("WAITING_HUMAN");
        when(executionMapper.selectById(901L)).thenReturn(pausedRow);

        AgentGraphInterpreter.HumanDecision timeoutDecision = new AgentGraphInterpreter.HumanDecision(
                "human", AgentGraphInterpreter.HUMAN_TIMEOUT_DECISION, Map.of());
        Object outcome = runSubgraph.invoke(service, callWithResume(saved, List.of(timeoutDecision)),
                900L, 0, new HashSet<Long>());

        // 全程只 insert 过一次（首跑）；恢复轮没有新建行
        verify(executionMapper, times(1)).insert(any(AgentGraphExecution.class));
        // 同一行更新为 SUCCESS
        ArgumentCaptor<AgentGraphExecution> updated = ArgumentCaptor.forClass(AgentGraphExecution.class);
        verify(executionMapper, times(2)).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(901L);
        assertThat(updated.getValue().getStatus()).isEqualTo("SUCCESS");
        // 恢复轮轨迹续写：清掉暂停期 WAITING 行后再插入新轨迹
        verify(executionNodeMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertThat(outcome).isInstanceOf(AgentGraphInterpreter.SubgraphResult.class);
        assertThat(((AgentGraphInterpreter.SubgraphResult) outcome).childExecutionId).isEqualTo(901L);
    }

    @Test
    @DisplayName("P72：行 id 缺失时防御性新建（不回退旧行、不中断恢复）")
    void resume_withMissingRow_fallsBackToInsert() throws Exception {
        AgentGraphInterpreter.PauseState saved = firstRunPauses();
        // 行已被清理的极端场景：selectById 返回 null → 防御性新建
        when(executionMapper.selectById(901L)).thenReturn(null);

        AgentGraphInterpreter.HumanDecision timeoutDecision = new AgentGraphInterpreter.HumanDecision(
                "human", AgentGraphInterpreter.HUMAN_TIMEOUT_DECISION, Map.of());
        runSubgraph.invoke(service, callWithResume(saved, List.of(timeoutDecision)),
                900L, 0, new HashSet<Long>());

        verify(executionMapper, times(2)).insert(any(AgentGraphExecution.class));
    }
}
