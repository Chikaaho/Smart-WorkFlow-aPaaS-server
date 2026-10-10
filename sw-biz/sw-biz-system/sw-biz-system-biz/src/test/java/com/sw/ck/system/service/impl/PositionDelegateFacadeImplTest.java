package com.sw.ck.system.service.impl;

import com.sw.ck.system.api.delegate.PositionDelegateFacade;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.entity.SysPostDelegate;
import com.sw.ck.system.mapper.SysPostDelegateMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P64 阶段Ⅱ（A07）岗位委托解析单测：
 * 未命中关系沿 P63 原义（源岗位有效人员）；精确部门优先于组织默认；
 * 逐跳审计链可追溯；超 4 跳/受托空缺异常处置不回退；多源岗位并集去重。
 */
@DisplayName("P64 岗位委托解析测试")
class PositionDelegateFacadeImplTest {

    private SysPostDelegateMapper delegateMapper;
    private SysPostMapper postMapper;
    private SysUserMapper userMapper;
    private PositionDelegateFacadeImpl facade;

    @BeforeEach
    void setUp() {
        delegateMapper = mock(SysPostDelegateMapper.class);
        postMapper = mock(SysPostMapper.class);
        userMapper = mock(SysUserMapper.class);
        facade = new PositionDelegateFacadeImpl(delegateMapper, postMapper, userMapper);
        when(postMapper.selectOne(any())).thenAnswer(invocation -> null);
        when(postMapper.selectById(anyLong())).thenAnswer(invocation ->
                switch (invocation.getArgument(0, Long.class).intValue()) {
                    case 1 -> post(1L, "P_LEAD");
                    case 2 -> post(2L, "P_REVIEW");
                    case 3 -> post(3L, "P_ARCHIVE");
                    default -> null;
                });
    }

    @Test
    @DisplayName("未命中委托关系：使用源岗位有效人员（P63 原义），跳链为空")
    void noRelationFallsBackToSourcePostHolders() {
        when(postMapper.selectOne(any())).thenReturn(post(1L, "P_LEAD"));
        when(delegateMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectActiveUserIdsByPostCodes(anyList(), eq(9L)))
                .thenReturn(List.of(11L, 12L));

        Optional<PositionDelegateFacade.ResolvedPostActors> result =
                facade.resolvePostActors(9L, List.of("P_LEAD"), null);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().userIds()).containsExactly(11L, 12L);
        assertThat(result.orElseThrow().hops()).isEmpty();
    }

    @Test
    @DisplayName("命中委托：源岗位→受托岗位→实际办理人；精确部门范围优先于组织默认；跳链可审计")
    void delegateChainResolvesTargetHoldersWithAudit() {
        // P_LEAD 在部门 10 有精确委托 → P_REVIEW；组织默认（→P_ARCHIVE）不生效
        SysPostDelegate deptRule = rule(1L, 2L, "DEPT", 10L);
        // 连续打桩：第一次查询（DEPT 精确）返回规则，第二次（ORG 默认兜底）返回空
        when(delegateMapper.selectOne(any())).thenReturn(deptRule, (SysPostDelegate) null);
        when(postMapper.selectOne(any())).thenReturn(post(1L, "P_LEAD"));
        when(userMapper.selectActiveUserIdsByDeptAndPost(eq(10L), eq("P_REVIEW"), eq(9L)))
                .thenReturn(List.of(21L));

        Optional<PositionDelegateFacade.ResolvedPostActors> result =
                facade.resolvePostActors(9L, List.of("P_LEAD"), 10L);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().userIds()).containsExactly(21L);
        List<PositionDelegateFacade.DelegateHop> hops = result.orElseThrow().hops();
        assertThat(hops).hasSize(1);
        assertThat(hops.get(0).sourcePostCode()).isEqualTo("P_LEAD");
        assertThat(hops.get(0).targetPostCode()).isEqualTo("P_REVIEW");
        assertThat(hops.get(0).scopeType()).isEqualTo("DEPT");
        assertThat(hops.get(0).deptId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("受托岗位缺有效人员：命中关系后空缺进入异常处置，不自动回退到源岗位")
    void delegatedEmptyTargetFailsWithoutFallback() {
        // 首次 ORG 查询命中 1→2，此后无规则（deptId 为空时无 DEPT 前置查询）
        when(delegateMapper.selectOne(any())).thenReturn(
                rule(1L, 2L, "ORG", null), (SysPostDelegate) null);
        when(postMapper.selectOne(any())).thenReturn(post(1L, "P_LEAD"));
        when(userMapper.selectActiveUserIdsByPostCodes(anyList(), eq(9L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> facade.resolvePostActors(9L, List.of("P_LEAD"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不自动回退");
    }

    @Test
    @DisplayName("多源岗位结果并集去重；未命中关系的源岗位空缺按 P63 原义贡献空集合")
    void multiPostUnionDedup() {
        when(postMapper.selectOne(any())).thenReturn(post(1L, "P_LEAD"));
        when(delegateMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectActiveUserIdsByPostCodes(anyList(), eq(9L)))
                .thenReturn(List.of(11L, 12L));

        Optional<PositionDelegateFacade.ResolvedPostActors> result =
                facade.resolvePostActors(9L, List.of("P_LEAD", "P_LEAD"), null);
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().userIds()).containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("超4跳拒绝（运行期纵深防御）：1→2→3→4→5 链解析终止抛可诊断异常，不产出办理人")
    void chainOverFourHopsRejectedAtRuntime() {
        when(postMapper.selectOne(any())).thenReturn(post(1L, "P_LEAD"));
        when(postMapper.selectById(4L)).thenReturn(post(4L, "P_D4"));
        when(postMapper.selectById(5L)).thenReturn(post(5L, "P_D5"));
        // 逐跳 ORG 查询（deptId=null 无 DEPT 前置）：1→2、2→3、3→4、4→5
        when(delegateMapper.selectOne(any())).thenReturn(
                rule(1L, 2L, "ORG", null),
                rule(2L, 3L, "ORG", null),
                rule(3L, 4L, "ORG", null),
                rule(4L, 5L, "ORG", null));

        assertThatThrownBy(() -> facade.resolvePostActors(9L, List.of("P_LEAD"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超过最大 4 跳");
    }

    @Test
    @DisplayName("跨租户越权拒绝：受托岗位属其他租户 → 关系失效异常处置，不自动回退、不产出办理人")
    void crossTenantTargetPostRejectedAtRuntime() {
        when(postMapper.selectOne(any())).thenReturn(post(1L, "P_LEAD"));
        SysPost foreign = post(2L, "P_REVIEW");
        foreign.setTenantId(666L);
        when(postMapper.selectById(2L)).thenReturn(foreign);
        when(delegateMapper.selectOne(any())).thenReturn(rule(1L, 2L, "ORG", null));

        assertThatThrownBy(() -> facade.resolvePostActors(9L, List.of("P_LEAD"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("越权")
                .hasMessageContaining("不自动回退");
    }

    @Test
    @DisplayName("运行期循环拒绝：1→2、2→1 沿链回到已访问岗位 → 循环异常，不产出办理人")
    void runtimeCycleDetectedInWalkChain() {
        when(postMapper.selectOne(any())).thenReturn(post(1L, "P_LEAD"));
        when(delegateMapper.selectOne(any())).thenReturn(
                rule(1L, 2L, "ORG", null),
                rule(2L, 1L, "ORG", null));

        assertThatThrownBy(() -> facade.resolvePostActors(9L, List.of("P_LEAD"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("循环");
    }

    private SysPost post(Long id, String code) {
        SysPost post = new SysPost();
        post.setId(id);
        post.setCode(code);
        post.setStatus(1);
        post.setTenantId(9L);
        return post;
    }

    private SysPostDelegate rule(Long source, Long target, String scope, Long deptId) {
        SysPostDelegate rule = new SysPostDelegate();
        rule.setSourcePostId(source);
        rule.setTargetPostId(target);
        rule.setScopeType(scope);
        rule.setDeptId(deptId);
        rule.setStatus(SysPostDelegate.STATUS_ENABLED);
        return rule;
    }
}
