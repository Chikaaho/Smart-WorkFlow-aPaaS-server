package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.system.api.delegate.PositionDelegateFacade;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅱ（A07）岗位策略委托感知解析单测：
 * 委托门面可用时按“源岗位→有效委托→受托岗位→实际办理人”解析（含精确部门上下文）；
 * 未装配/未配置关系时保持 P63 原义。
 */
@DisplayName("P64 岗位策略委托感知解析测试")
class PostDelegateParticipantResolverTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PositionDelegateFacade> delegateFacade =
            (ObjectProvider<PositionDelegateFacade>) mock(ObjectProvider.class);
    private final UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
    private final PositionDelegateFacade facade = mock(PositionDelegateFacade.class);
    private PostParticipantResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PostParticipantResolver(userQueryFacade, delegateFacade);
        when(delegateFacade.getIfAvailable()).thenReturn(facade);
    }

    @Test
    @DisplayName("委托门面命中：解析受托岗位实际人员（租户+部门上下文贯穿）")
    void resolvesThroughDelegateFacade() {
        when(facade.resolvePostActors(eq(9L), eq(List.of("P_LEAD")), eq(10L)))
                .thenReturn(Optional.of(new PositionDelegateFacade.ResolvedPostActors(
                        List.of(21L, 22L),
                        List.of(new PositionDelegateFacade.DelegateHop(
                                "P_LEAD", "P_REVIEW", "DEPT", 10L)))));

        Optional<List<String>> result = resolver.resolve(context(
                Map.of("postCodes", List.of("P_LEAD"), "deptId", 10)));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).containsExactly("21", "22");
    }

    @Test
    @DisplayName("旧字符串形态（P63 兼容）经委托门面解析（未配置关系=源岗位任职）；门面未装配时直查任职")
    void fallsBackToPlainPostHoldersWhenFacadeMissing() {
        // 旧字符串形态 + 门面可用（未配置关系：解析结果即源岗位有效人员）
        when(facade.resolvePostActors(eq(9L), eq(List.of("P_LEAD")), eq((Long) null)))
                .thenReturn(Optional.of(new PositionDelegateFacade.ResolvedPostActors(
                        List.of(11L), List.of())));
        assertThat(resolver.resolve(context("P_LEAD")).orElseThrow()).containsExactly("11");

        // 门面未装配（engine 独立测试）：保持 P63 原义直查任职
        PostParticipantResolver withoutFacade = new PostParticipantResolver(userQueryFacade, null);
        when(userQueryFacade.findActiveUserIdsByPostCodes(eq(List.of("P_LEAD")), any()))
                .thenReturn(Optional.of(List.of(11L)));
        assertThat(withoutFacade.resolve(context("P_LEAD")).orElseThrow()).containsExactly("11");

        // 门面可用但租户缺失：保持原义直查
        PostParticipantResolver withFacade = new PostParticipantResolver(userQueryFacade, delegateFacade);
        NodeParticipantContext missingTenant = context("P_LEAD");
        missingTenant.setTenantId(null);
        assertThat(withFacade.resolve(missingTenant).orElseThrow()).containsExactly("11");
    }

    @Test
    @DisplayName("委托链越界传播：超4跳/循环/空缺异常原样上抛 → 参与人解析失败，节点不产生任务不推进")
    void facadeFailurePropagatesSoNodeDoesNotAdvance() {
        when(facade.resolvePostActors(eq(9L), eq(List.of("P_LEAD")), eq((Long) null)))
                .thenThrow(new IllegalStateException(
                        "岗位委托链超过最大 4 跳，解析终止，请修正委托配置"));

        assertThatThrownBy(() -> resolver.resolve(context("P_LEAD")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过最大 4 跳");
    }

    private NodeParticipantContext context(Object strategyValue) {
        return NodeParticipantContext.builder()
                .tenantId(9L)
                .processInstanceId("pi-1")
                .nodeKey("node_approval")
                .strategy("POST")
                .strategyValue(strategyValue)
                .build();
    }
}
