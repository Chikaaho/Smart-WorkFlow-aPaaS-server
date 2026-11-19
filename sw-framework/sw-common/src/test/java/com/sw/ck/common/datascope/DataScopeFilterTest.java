package com.sw.ck.common.datascope;

import com.sw.ck.common.security.LoginContextProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataScopeFilter#resolve} 解析语义验证。
 * <p>
 * 与 {@code DataScopeHandler#getSqlSegment} 的逐档语义对齐：超管短路 / ALL 放行 /
 * SELF（null userId 恒假）/ DEPT（null deptId 恒假）/ DEPT_AND_CHILD（含下级）/
 * CUSTOM（并集，空集恒假）。
 * </p>
 */
@DisplayName("DataScopeFilter 解析单元测试")
class DataScopeFilterTest {

    private final LoginContextProvider loginContext = mock(LoginContextProvider.class);
    private final DeptScopeProvider deptScopeProvider = mock(DeptScopeProvider.class);

    // ==================== 短路 / 放行 ====================

    @Test
    void superAdmin_shouldReturnNone() {
        when(loginContext.isSuperAdmin()).thenReturn(true);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getUserId()).isNull();
        assertThat(filter.getDeptIds()).isNull();
        assertThat(filter.isAlwaysFalse()).isFalse();
    }

    @Test
    void allScope_shouldReturnNone() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.ALL);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getUserId()).isNull();
        assertThat(filter.getDeptIds()).isNull();
        assertThat(filter.isAlwaysFalse()).isFalse();
    }

    @Test
    void nullScopeType_shouldReturnNone() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(null);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getUserId()).isNull();
        assertThat(filter.getDeptIds()).isNull();
        assertThat(filter.isAlwaysFalse()).isFalse();
    }

    // ==================== SELF ====================

    @Test
    void selfScope_shouldCarryUserId() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.SELF);
        when(loginContext.getUserId()).thenReturn(42L);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getUserId()).isEqualTo(42L);
        assertThat(filter.getDeptIds()).isNull();
        assertThat(filter.isAlwaysFalse()).isFalse();
    }

    @Test
    void selfScope_withoutUserId_shouldBeAlwaysFalse() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.SELF);
        when(loginContext.getUserId()).thenReturn(null);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.isAlwaysFalse()).as("SELF 且取不到 userId 应恒假（对齐 handler）").isTrue();
    }

    // ==================== DEPT ====================

    @Test
    void deptScope_shouldCarrySingleDeptId() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.DEPT);
        when(loginContext.getDeptId()).thenReturn(11L);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getDeptIds()).containsExactly(11L);
        assertThat(filter.getUserId()).isNull();
    }

    @Test
    void deptScope_withoutDeptId_shouldBeEmptyList() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.DEPT);
        when(loginContext.getDeptId()).thenReturn(null);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getDeptIds()).as("DEPT 且取不到 deptId 应为空列表（SQL 侧恒假）").isEmpty();
    }

    // ==================== DEPT_AND_CHILD ====================

    @Test
    void deptAndChildScope_shouldMergeSelfAndChildren() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.DEPT_AND_CHILD);
        when(loginContext.getDeptId()).thenReturn(11L);
        when(deptScopeProvider.listChildDeptIds(11L)).thenReturn(List.of(111L, 112L));

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getDeptIds()).containsExactlyInAnyOrder(11L, 111L, 112L);
        assertThat(filter.getUserId()).isNull();
    }

    @Test
    void deptAndChildScope_shouldNotQueryChildrenWhenDeptIdMissing() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.DEPT_AND_CHILD);
        when(loginContext.getDeptId()).thenReturn(null);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getDeptIds()).isEmpty();
        verify(deptScopeProvider, never()).listChildDeptIds(ArgumentMatchers.any());
    }

    // ==================== CUSTOM ====================

    @Test
    void customScope_shouldCarryUnionDeptIds() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.CUSTOM);
        when(loginContext.getCustomDeptIds()).thenReturn(Set.of(10L, 20L));

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getDeptIds()).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void customScope_withEmptyIds_shouldBeEmptyList() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.CUSTOM);
        when(loginContext.getCustomDeptIds()).thenReturn(Set.of());

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getDeptIds()).as("CUSTOM 未配置部门应为空列表（SQL 侧恒假）").isEmpty();
    }

    @Test
    void customScope_withNullIds_shouldBeEmptyList() {
        when(loginContext.isSuperAdmin()).thenReturn(false);
        when(loginContext.getDataScopeType()).thenReturn(DataScopeType.CUSTOM);
        when(loginContext.getCustomDeptIds()).thenReturn(null);

        DataScopeFilter filter = DataScopeFilter.resolve(loginContext, deptScopeProvider);

        assertThat(filter.getDeptIds()).isEmpty();
    }
}
