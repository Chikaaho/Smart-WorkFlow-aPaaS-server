package com.sw.ck.bpm.process.validator;

import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.node.BpmNodeDefinition;
import com.sw.ck.bpm.api.node.BpmNodeMetadata;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.bpm.api.node.BpmNodeTopology;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GraphValidator 单元测试 —— 重点覆盖正向 BFS 可达但反向 BFS 到不了 END 的死胡同节点。
 */
class GraphValidatorTest {

    private GraphValidator validator;

    @BeforeEach
    void setUp() {
        Map<String, BpmNodeDefinition> definitions = new HashMap<>();
        definitions.put("START", definition("START", 0, 0, 1, 1, true, false));
        definitions.put("END", definition("END", 1, Integer.MAX_VALUE, 0, 0, false, true));
        definitions.put("APPROVAL", definition("APPROVAL", 1, 1, 1, 1, false, false));
        definitions.put("CONDITION", definition("CONDITION", 1, 1, 1, 1, false, false));
        definitions.put("EXCLUSIVE_GATEWAY", definition("EXCLUSIVE_GATEWAY", 1, 1, 2, Integer.MAX_VALUE, false, false));
        definitions.put("PARALLEL_GATEWAY", definition("PARALLEL_GATEWAY", 1, 1, 2, Integer.MAX_VALUE, false, false));
        definitions.put("JOIN_GATEWAY", definition("JOIN_GATEWAY", 2, Integer.MAX_VALUE, 1, 1, false, false));
        BpmNodeRegistry registry = mock(BpmNodeRegistry.class);
        when(registry.find(any())).thenAnswer(invocation ->
                Optional.ofNullable(definitions.get(invocation.getArgument(0))));
        when(registry.validateConfig(any())).thenReturn(List.of());
        // formDefinitionService 在 formKey=null 时不会被调用，safe-null mock
        validator = new GraphValidator(registry, mock(FormDefinitionService.class));
    }

    private static BpmNodeDefinition definition(String type, int minIn, int maxIn,
                                                int minOut, int maxOut,
                                                boolean startNode, boolean endNode) {
        return new BpmNodeDefinition() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public BpmNodeMetadata metadata() {
                return new BpmNodeMetadata(
                        type, type, "OTHER",
                        new BpmNodeTopology(minIn, maxIn, minOut, maxOut),
                        List.of(), "test-v1", EnumSet.of(
                                com.sw.ck.bpm.api.node.BpmNodeCapability.DESIGN,
                                com.sw.ck.bpm.api.node.BpmNodeCapability.TRANSLATE,
                                com.sw.ck.bpm.api.node.BpmNodeCapability.RUNTIME,
                                com.sw.ck.bpm.api.node.BpmNodeCapability.CONFIG_VALIDATE),
                        startNode, endNode, startNode || endNode, false);
            }
        };
    }

    /**
     * 图拓扑：
     * <pre>
     *   START → A → B → END
     *            ↘
     *              C（死胡同 —— 从 START 可到达，但无法到达 END）
     * </pre>
     * <p>
     * 正向 BFS（从 START 出发）：{START, A, B, C, END}
     * 反向 BFS（从 END 出发）：{END, B, A, START}
     * C 在正向集合但不在反向集合 → 孤儿（2005）。
     * </p>
     * <p>
     * 注：此图同时触发 A（out=2）和 C（out=0）的 APPROVAL 入/出度基数违规（2004），
     * 这是预期行为——校验器独立运行所有规则，不因基数违规跳过连通性校验。
     * </p>
     */
    @Test
    void shouldDetectNodeReachableFromStartButCannotReachEnd() {
        String startId = "start";
        String endId = "end";
        String aId = "A";
        String bId = "B";
        String cId = "C";

        GraphElement start = node(startId, "START");
        GraphElement end = node(endId, "END");
        GraphElement a = node(aId, "APPROVAL");
        GraphElement b = node(bId, "APPROVAL");
        GraphElement c = node(cId, "APPROVAL");

        List<GraphElement> elements = List.of(
                start, end, a, b, c,
                edge("e1", startId, aId),
                edge("e2", aId, bId),
                edge("e3", bId, endId),
                edge("e4", aId, cId)   // 死胡同分支
        );

        List<GraphValidationError> errors = validator.validate(elements, null);

        // C 应被标记为孤儿（2005）
        assertThat(errors)
                .filteredOn(e -> e.getErrorCode() == BpmErrorCode.GRAPH_ORPHAN_NODE.getCode())
                .extracting(GraphValidationError::getElementId)
                .contains(cId);

        // START / END / A / B 不应被标记为孤儿
        assertThat(errors)
                .filteredOn(e -> e.getErrorCode() == BpmErrorCode.GRAPH_ORPHAN_NODE.getCode())
                .extracting(GraphValidationError::getElementId)
                .doesNotContain(startId, endId, aId, bId);
    }

    /**
     * 完整注册骨架（M04-F08-01）：预留位类型（CONDITION / EXCLUSIVE_GATEWAY /
     * JOIN_GATEWAY）应被注册表自动识别——不再报未知类型（2003），
     * 且基数合规时整体通过校验（消费方零改动即生效）。
     * <pre>
     *   START → CONDITION → EXCLUSIVE_GATEWAY → A1 ─┐
     *                                         └→ A2 ─→ JOIN_GATEWAY → END
     * </pre>
     */
    @Test
    void shouldRecognizeReservedGatewayTypes() {
        List<GraphElement> elements = List.of(
                node("start", "START"),
                node("cond", "CONDITION"),
                node("exg", "EXCLUSIVE_GATEWAY"),
                node("a1", "APPROVAL"),
                node("a2", "APPROVAL"),
                node("join", "JOIN_GATEWAY"),
                node("end", "END"),
                edge("e1", "start", "cond"),
                edge("e2", "cond", "exg"),
                edge("e3", "exg", "a1"),
                edge("e4", "exg", "a2"),
                edge("e5", "a1", "join"),
                edge("e6", "a2", "join"),
                edge("e7", "join", "end")
        );

        List<GraphValidationError> errors = validator.validate(elements, null);

        // 无任何错误：类型全注册 + 基数合规 + 连通
        assertThat(errors).isEmpty();
    }

    /**
     * 预留位类型 PARALLEL_GATEWAY 同样被注册表自动识别（并行扇出 + 汇合）。
     * <pre>
     *   START → PARALLEL_GATEWAY → A1 ─┐
     *                           └→ A2 ─→ JOIN_GATEWAY → END
     * </pre>
     */
    @Test
    void shouldRecognizeParallelGateway() {
        List<GraphElement> elements = List.of(
                node("start", "START"),
                node("pg", "PARALLEL_GATEWAY"),
                node("a1", "APPROVAL"),
                node("a2", "APPROVAL"),
                node("join", "JOIN_GATEWAY"),
                node("end", "END"),
                edge("e1", "start", "pg"),
                edge("e2", "pg", "a1"),
                edge("e3", "pg", "a2"),
                edge("e4", "a1", "join"),
                edge("e5", "a2", "join"),
                edge("e6", "join", "end")
        );

        List<GraphValidationError> errors = validator.validate(elements, null);

        assertThat(errors).isEmpty();
    }

    /**
     * 预留位类型同样受基数约束：EXCLUSIVE_GATEWAY 最少 2 出边（2004），
     * JOIN_GATEWAY 最少 2 入边（2004）——注册表规格即校验依据。
     */
    @Test
    void shouldEnforceReservedGatewayCardinality() {
        // 排他网关仅 1 条出边 → 基数违规
        List<GraphElement> gatewayOneOut = List.of(
                node("start", "START"),
                node("exg", "EXCLUSIVE_GATEWAY"),
                node("a1", "APPROVAL"),
                node("end", "END"),
                edge("e1", "start", "exg"),
                edge("e2", "exg", "a1"),
                edge("e3", "a1", "end")
        );

        List<GraphValidationError> errors = validator.validate(gatewayOneOut, null);
        assertThat(errors)
                .filteredOn(e -> e.getErrorCode() == BpmErrorCode.GRAPH_NODE_EDGE_CARDINALITY.getCode())
                .extracting(GraphValidationError::getElementId)
                .contains("exg");

        // 汇合网关仅 1 条入边 → 基数违规
        List<GraphElement> joinOneIn = List.of(
                node("start", "START"),
                node("j", "JOIN_GATEWAY"),
                node("end", "END"),
                edge("e1", "start", "j"),
                edge("e2", "j", "end")
        );

        List<GraphValidationError> joinErrors = validator.validate(joinOneIn, null);
        assertThat(joinErrors)
                .filteredOn(e -> e.getErrorCode() == BpmErrorCode.GRAPH_NODE_EDGE_CARDINALITY.getCode())
                .extracting(GraphValidationError::getElementId)
                .contains("j");
    }

    // ==================== helper ====================

    private static GraphElement node(String id, String type) {
        return GraphElement.builder()
                .id(id)
                .kind("node")
                .type(type)
                .config(Collections.emptyMap())
                .style(Collections.emptyMap())
                .build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder()
                .id(id)
                .kind("edge")
                .source(source)
                .target(target)
                .config(Collections.emptyMap())
                .style(Collections.emptyMap())
                .build();
    }
}
