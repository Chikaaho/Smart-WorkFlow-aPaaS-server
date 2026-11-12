package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** G3：人工节点暂停/恢复与子图调用（纯 Java 单测，无 Spring）。 */
@DisplayName("G3 人工节点与子图节点")
class AgentGraphHumanSubgraphTest {

    private final AgentModelRuntimeService modelRuntimeService = mock(AgentModelRuntimeService.class);

    private AgentGraphInterpreter interpreter(int maxSteps) {
        return new AgentGraphInterpreter(modelRuntimeService, null, null, 100L, maxSteps);
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type).config(config).build();
    }

    private static GraphElement edge(String id, String source, String target,
                                     Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(config).build();
    }

    private static ProcessGraph graphOf(GraphElement... elements) {
        return ProcessGraph.builder().graphKey("g").name("g").version(1)
                .elements(List.of(elements)).canvas(Map.of()).build();
    }

    private static Map<String, Object> humanConfig() {
        return Map.of(
                "instructions", "请确认 {{input}}",
                "fields", List.of(Map.of("name", "comment", "label", "意见", "type", "text")),
                "buttons", List.of(Map.of("keyword", "approve", "label", "通过"),
                        Map.of("keyword", "reject", "label", "退回")),
                "assigneeUserIds", List.of(7),
                "timeoutSeconds", 60,
                "timeoutKeyword", "timeout");
    }

    @Test
    @DisplayName("人工节点：无决策时暂停并携带恢复状态与任务定义")
    void humanNode_pausesWithStateAndTaskSpec() {
        ProcessGraph graph = graphOf(
                node("start", "START", Map.of()),
                node("human", "HUMAN", humanConfig()),
                node("end", "END", Map.of()),
                edge("e1", "start", "human", Map.of()),
                edge("e2", "human", "end", Map.of("keyword", "approve")),
                edge("e3", "human", "end", Map.of("keyword", "reject")),
                edge("e4", "human", "end", Map.of("keyword", "timeout")));

        AtomicReference<AgentGraphInterpreter.HumanPauseException> paused = new AtomicReference<>();
        try {
            interpreter(20).run(graph, "待确认内容");
        } catch (AgentGraphInterpreter.HumanPauseException pause) {
            paused.set(pause);
        }

        assertThat(paused.get()).isNotNull();
        AgentGraphInterpreter.HumanTaskSpec spec = paused.get().getTaskSpec();
        assertThat(spec.humanNodeId).isEqualTo("human");
        assertThat(spec.instructions).isEqualTo("请确认 待确认内容");
        assertThat(spec.fields).hasSize(1);
        assertThat(spec.buttons).hasSize(2);
        assertThat(spec.assigneeUserIds).containsExactly(7L);
        assertThat(spec.timeoutSeconds).isEqualTo(60);
        AgentGraphInterpreter.PauseState state = paused.get().getPauseState();
        assertThat(state.activePoints).isNotEmpty();
        assertThat(state.activePoints.get(0).nodeId).isEqualTo("human");
        assertThat(state.variables).containsEntry("input", "待确认内容");
    }

    @Test
    @DisplayName("人工节点恢复：决策按钮路由 + 填写值写入变量供下游使用")
    void humanNode_resumeRoutesAndWritesValues() {
        ProcessGraph graph = graphOf(
                node("start", "START", Map.of()),
                node("human", "HUMAN", humanConfig()),
                node("end", "END", Map.of("inputVar", "comment")),
                edge("e1", "start", "human", Map.of()),
                edge("e2", "human", "end", Map.of("keyword", "approve")),
                edge("e3", "human", "end", Map.of("keyword", "reject")),
                edge("e4", "human", "end", Map.of("keyword", "timeout")));

        AgentGraphInterpreter first = interpreter(20);
        AgentGraphInterpreter.HumanPauseException pause = null;
        try {
            first.run(graph, "待确认内容");
        } catch (AgentGraphInterpreter.HumanPauseException caught) {
            pause = caught;
        }
        assertThat(pause).isNotNull();

        AgentGraphInterpreter.RunOptions options = new AgentGraphInterpreter.RunOptions();
        options.resumeState = pause.getPauseState();
        options.decisions = List.of(new AgentGraphInterpreter.HumanDecision(
                "human", "approve", Map.of("comment", "同意")));

        String output = interpreter(20).run(graph, Map.of(), options);
        assertThat(output).isEqualTo("同意");
    }

    @Test
    @DisplayName("人工节点超时：无超时边 → HUMAN_TIMEOUT 失败；有超时边 → 按 timeoutKeyword 路由")
    void humanNode_timeoutRouting() {
        ProcessGraph noTimeoutEdge = graphOf(
                node("start", "START", Map.of()),
                node("human", "HUMAN", Map.of(
                        "buttons", List.of(Map.of("keyword", "approve")),
                        "assigneeUserIds", List.of(7))),
                node("end", "END", Map.of()),
                edge("e1", "start", "human", Map.of()),
                edge("e2", "human", "end", Map.of("keyword", "approve")));

        AgentGraphInterpreter.HumanPauseException pause;
        try {
            interpreter(20).run(noTimeoutEdge, "x");
            throw new AssertionError("应暂停");
        } catch (AgentGraphInterpreter.HumanPauseException caught) {
            pause = caught;
        }
        AgentGraphInterpreter.RunOptions timeoutOptions = new AgentGraphInterpreter.RunOptions();
        timeoutOptions.resumeState = pause.getPauseState();
        timeoutOptions.decisions = List.of(new AgentGraphInterpreter.HumanDecision(
                "human", AgentGraphInterpreter.HUMAN_TIMEOUT_DECISION, Map.of()));
        assertThatThrownBy(() -> interpreter(20).run(noTimeoutEdge, Map.of(), timeoutOptions))
                .isInstanceOf(AgentGraphInterpreter.GraphExecutionException.class)
                .extracting(error -> ((AgentGraphInterpreter.GraphExecutionException) error).getCategory())
                .isEqualTo(AgentGraphInterpreter.ERROR_CATEGORY_HUMAN_TIMEOUT);

        ProcessGraph withTimeoutEdge = graphOf(
                node("start", "START", Map.of()),
                node("human", "HUMAN", Map.of(
                        "buttons", List.of(Map.of("keyword", "approve")),
                        "assigneeUserIds", List.of(7),
                        "timeoutKeyword", "expired")),
                node("end", "END", Map.of("inputVar", "timeoutFlag")),
                edge("e1", "start", "human", Map.of()),
                edge("e2", "human", "end", Map.of("keyword", "approve")),
                edge("e3", "human", "end", Map.of("keyword", "expired")));
        AgentGraphInterpreter.HumanPauseException second;
        try {
            interpreter(20).run(withTimeoutEdge, "x");
            throw new AssertionError("应暂停");
        } catch (AgentGraphInterpreter.HumanPauseException caught) {
            second = caught;
        }
        AgentGraphInterpreter.RunOptions routeOptions = new AgentGraphInterpreter.RunOptions();
        routeOptions.resumeState = second.getPauseState();
        routeOptions.decisions = List.of(new AgentGraphInterpreter.HumanDecision(
                "human", AgentGraphInterpreter.HUMAN_TIMEOUT_DECISION,
                Map.of("timeoutFlag", "TIMEOUT")));
        assertThat(interpreter(20).run(withTimeoutEdge, Map.of(), routeOptions)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("子图节点：入参映射 → 调用固定版本子图 → 出参映射回父变量")
    void subgraphNode_mapsInputsAndOutputs() {
        ProcessGraph parent = graphOf(
                node("start", "START", Map.of()),
                node("sub", "SUBGRAPH", Map.of(
                        "graphDefId", 88,
                        "graphDefVersion", 2,
                        "inputMappings", List.of(Map.of("name", "question", "source", "input")),
                        "outputMappings", List.of(Map.of("name", "summary", "source", "answer")))),
                node("end", "END", Map.of("inputVar", "summary")),
                edge("e1", "start", "sub", Map.of()),
                edge("e2", "sub", "end", Map.of()));

        AtomicReference<AgentGraphInterpreter.SubgraphCall> captured = new AtomicReference<>();
        AgentGraphInterpreter.RunOptions options = new AgentGraphInterpreter.RunOptions();
        options.subgraphRunner = call -> {
            captured.set(call);
            AgentGraphInterpreter.SubgraphResult result = new AgentGraphInterpreter.SubgraphResult();
            result.outputs = Map.of("answer", "子图结果");
            result.childExecutionId = 1234L;
            return result;
        };

        String output = interpreter(20).run(parent, Map.of("input", "父输入"), options);

        assertThat(output).isEqualTo("子图结果");
        assertThat(captured.get().graphDefId).isEqualTo(88L);
        assertThat(captured.get().graphDefVersion).isEqualTo(2);
        assertThat(captured.get().inputs).containsEntry("question", "父输入");
    }

    @Test
    @DisplayName("子图内人工暂停：父执行合并暂停状态并透传子任务定义")
    void subgraphHumanPause_bubblesToParent() {
        ProcessGraph parent = graphOf(
                node("start", "START", Map.of()),
                node("sub", "SUBGRAPH", Map.of("graphDefId", 88, "graphDefVersion", 1)),
                node("end", "END", Map.of()),
                edge("e1", "start", "sub", Map.of()),
                edge("e2", "sub", "end", Map.of()));

        AgentGraphInterpreter.PauseState childState = new AgentGraphInterpreter.PauseState();
        childState.activePoints.add(new AgentGraphInterpreter.ActivePointState("child-human", "0"));
        childState.variables.put("input", "子图输入");
        AgentGraphInterpreter.HumanTaskSpec childSpec = new AgentGraphInterpreter.HumanTaskSpec();
        childSpec.humanNodeId = "child-human";
        AgentGraphInterpreter.RunOptions options = new AgentGraphInterpreter.RunOptions();
        options.subgraphRunner = call -> {
            throw new AgentGraphInterpreter.HumanPauseException(childState, childSpec);
        };

        try {
            interpreter(20).run(parent, Map.of("input", "父输入"), options);
            throw new AssertionError("应暂停");
        } catch (AgentGraphInterpreter.HumanPauseException pause) {
            assertThat(pause.getTaskSpec().humanNodeId).isEqualTo("child-human");
            assertThat(pause.getPauseState().subgraphPauseStates).containsKey("sub");
            assertThat(pause.getPauseState().subgraphPauseStates.get("sub").variables)
                    .containsEntry("input", "子图输入");
        }
    }
}
