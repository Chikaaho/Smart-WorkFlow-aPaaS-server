package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.bpm.api.variable.NodeFormPersonAggregatePort;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅱ（A07 聚合会签）节点表单人员聚合参与人解析单测：
 * 上一轮有效任务人员字段并集去重生成下一轮会签名单；来源/轮次边界；
 * 失效人员整体拒绝（准确性优先、可诊断）；形状校验单一权威。
 */
@DisplayName("P64 节点表单人员聚合参与人解析测试")
class AggregateNodeFormParticipantResolverTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<NodeFormPersonAggregatePort> port =
            (ObjectProvider<NodeFormPersonAggregatePort>) mock(ObjectProvider.class);
    private final UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
    private AggregateNodeFormParticipantResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AggregateNodeFormParticipantResolver(port, userQueryFacade);
    }

    @Test
    @DisplayName("上一轮全部有效并行任务的人员字段并集去重：A甲乙/B丙/C乙丁 → 甲乙丙丁")
    void aggregatesUnionDedupAcrossTasks() {
        when(port.getIfAvailable()).thenReturn(new NodeFormPersonAggregatePort() {
            @Override
            public Optional<List<String>> readPersonFieldValues(Long tenantId, String processInstanceId,
                                                                String nodeKey, String formField,
                                                                int roundOffset) {
                assertThat(nodeKey).isEqualTo("node_select");
                assertThat(formField).isEqualTo("next_approvers");
                assertThat(roundOffset).isZero();
                return Optional.of(List.of("101", "102", "103", "102", "104"));
            }
        });
        when(userQueryFacade.findActiveUserIds(anyCollection(), eq(9L)))
                .thenReturn(Optional.of(List.of(101L, 102L, 103L, 104L)));

        Optional<List<String>> result = resolver.resolve(context(
                Map.of("nodeKey", "node_select", "formField", "next_approvers", "round", "CURRENT")));

        assertThat(result).isPresent();
        // 并集按用户身份去重，保持来源顺序
        assertThat(result.orElseThrow()).containsExactly("101", "102", "103", "104");
    }

    @Test
    @DisplayName("聚合来源包含失效/越权人员整体拒绝（与 FORM_FIELD 口径一致，可诊断不静默跳过）")
    void inactiveUserRejectsWholeResolution() {
        when(port.getIfAvailable()).thenReturn(new NodeFormPersonAggregatePort() {
            @Override
            public Optional<List<String>> readPersonFieldValues(Long tenantId, String processInstanceId,
                                                                String nodeKey, String formField,
                                                                int roundOffset) {
                return Optional.of(List.of("101", "999"));
            }
        });
        when(userQueryFacade.findActiveUserIds(anyCollection(), eq(9L)))
                .thenReturn(Optional.of(List.of(101L)));

        assertThatThrownBy(() -> resolver.resolve(context(
                Map.of("nodeKey", "node_select", "formField", "next_approvers"))))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY.getCode()));
    }

    @Test
    @DisplayName("形状校验单一权威：缺 formField / 非法 round 拒绝并给出可纠正说明")
    void invalidShapeRejectedWithActionableMessage() {
        assertThat(ParticipantStrategy.nodeFormAggregateConfigError(
                        Map.of("nodeKey", "n1")).isEmpty()).isFalse();
        assertThat(ParticipantStrategy.nodeFormAggregateConfigError(
                Map.of("nodeKey", "n1", "formField", "f", "round", "NEXT"))).isPresent();
        assertThat(ParticipantStrategy.nodeFormAggregateConfigError(
                Map.of("nodeKey", "n1", "formField", "f", "round", "PREVIOUS"))).isEmpty();

        assertThatThrownBy(() -> resolver.resolve(context(Map.of("nodeKey", "node_select"))))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(BpmErrorCode.PARTICIPANT_AGGREGATE_INVALID.getCode()));
    }

    @Test
    @DisplayName("端口未装配（engine 独立测试）：解析为空交由注册失败策略处置")
    void missingPortYieldsEmpty() {
        when(port.getIfAvailable()).thenReturn(null);
        assertThat(resolver.resolve(context(
                Map.of("nodeKey", "n", "formField", "f")))).isEqualTo(Optional.of(List.of()));
    }

    private NodeParticipantContext context(Object strategyValue) {
        return NodeParticipantContext.builder()
                .tenantId(9L)
                .processInstanceId("pi-1")
                .taskId("task-1")
                .nodeKey("node_next")
                .businessKey("rec-1")
                .formKey("form-1")
                .strategy(ParticipantStrategy.NODE_FORM_AGGREGATE)
                .strategyValue(strategyValue)
                .build();
    }
}
