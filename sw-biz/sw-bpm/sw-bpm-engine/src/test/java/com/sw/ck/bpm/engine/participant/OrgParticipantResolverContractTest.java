package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I1 组织参与人策略解析器契约测试。
 * <p>
 * DEPT_LEADER / POST / DEPT_POST 三类策略：strategy 标识稳定、值正确转换、
 * 去重透传、非法值短路不触发组织查询。租户/启用状态过滤由 UserQueryFacade
 * 的组织权威实现负责（见 system 模块 OrgAuthorityFacadeIntegrationTest）。
 * </p>
 */
@DisplayName("I1 部门负责人/岗位/组合参与人解析器契约")
@ExtendWith(MockitoExtension.class)
class OrgParticipantResolverContractTest {

    @Mock
    private UserQueryFacade userQueryFacade;

    private NodeParticipantContext context(Object strategyValue) {
        return NodeParticipantContext.builder()
                .tenantId(1L)
                .processInstanceId("pi-1")
                .nodeKey("node-1")
                .strategy("X")
                .strategyValue(strategyValue)
                .build();
    }

    @Test
    @DisplayName("DEPT_LEADER：值转换为部门 ID 集合并去重，结果按字符串返回")
    void deptLeaderResolver_shouldDelegateToFacade() {
        when(userQueryFacade.findActiveUserIdsByDeptLeaders(List.of(1L, 2L), 1L))
                .thenReturn(List.of(10L, 11L));

        DeptLeaderParticipantResolver resolver = new DeptLeaderParticipantResolver(userQueryFacade);
        List<String> result = resolver.resolve(context(List.of("1", 2, "1")));

        assertThat(result).containsExactly("10", "11");
        verify(userQueryFacade).findActiveUserIdsByDeptLeaders(List.of(1L, 2L), 1L);
    }

    @Test
    @DisplayName("DEPT_LEADER：全非法值不触发组织查询，返回空")
    void deptLeaderResolver_shouldShortCircuitOnIllegalValues() {
        DeptLeaderParticipantResolver resolver = new DeptLeaderParticipantResolver(userQueryFacade);
        assertThat(resolver.resolve(context(List.of("abc", " ")))).isEmpty();
        verify(userQueryFacade, never()).findActiveUserIdsByDeptLeaders(anyCollection(), eq(1L));
    }

    @Test
    @DisplayName("POST：岗位编码集合去重透传，空值过滤")
    void postResolver_shouldDelegateToFacade() {
        when(userQueryFacade.findActiveUserIdsByPostCodes(List.of("PM", "DEV"), 1L))
                .thenReturn(List.of(20L));

        PostParticipantResolver resolver = new PostParticipantResolver(userQueryFacade);
        List<String> result = resolver.resolve(context(List.of("PM", "DEV", " ")));

        assertThat(result).containsExactly("20");
        verify(userQueryFacade).findActiveUserIdsByPostCodes(List.of("PM", "DEV"), 1L);
    }

    @Test
    @DisplayName("DEPT_POST：{deptId, postCode} 组合转换；非法结构短路返回空")
    void deptPostResolver_shouldDelegateToFacade() {
        when(userQueryFacade.findActiveUserIdsByDeptAndPost(3L, "PM", 1L))
                .thenReturn(List.of(30L));

        DeptPostParticipantResolver resolver = new DeptPostParticipantResolver(userQueryFacade);

        assertThat(resolver.resolve(context(Map.of("deptId", 3, "postCode", "PM"))))
                .containsExactly("30");
        // 非法结构：非 Map / 缺 key / 非数字 deptId
        assertThat(resolver.resolve(context("not-a-map"))).isEmpty();
        assertThat(resolver.resolve(context(Map.of("deptId", 3)))).isEmpty();
        assertThat(resolver.resolve(context(Map.of("deptId", "x", "postCode", "PM")))).isEmpty();
        verify(userQueryFacade).findActiveUserIdsByDeptAndPost(3L, "PM", 1L);
    }

    @Test
    @DisplayName("策略标识与 ParticipantStrategy 白名单一致")
    void strategyIds_shouldMatchContract() {
        assertThat(new DeptLeaderParticipantResolver(userQueryFacade).strategy()).isEqualTo("DEPT_LEADER");
        assertThat(new PostParticipantResolver(userQueryFacade).strategy()).isEqualTo("POST");
        assertThat(new DeptPostParticipantResolver(userQueryFacade).strategy()).isEqualTo("DEPT_POST");
        assertThat(com.sw.ck.bpm.api.participant.ParticipantStrategy.ALL)
                .contains("DEPT_LEADER", "POST", "DEPT_POST");
    }
}
