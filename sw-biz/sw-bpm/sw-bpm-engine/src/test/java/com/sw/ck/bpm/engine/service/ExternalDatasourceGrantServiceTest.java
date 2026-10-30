package com.sw.ck.bpm.engine.service;

import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.entity.ExternalDatasourceGrant;
import com.sw.ck.bpm.engine.mapper.ExternalDatasourceGrantMapper;
import com.sw.ck.bpm.engine.mapper.ExternalDatasourceMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部数据源引用授权服务行为测试（F020）。
 * <p>
 * Mapper 以桩注入：本测试判定授权策略与替换语义，不覆盖租户拦截器
 * （租户隔离由主库 TenantLineHandler 统一承担，见 G2/链路测试）。
 * </p>
 */
class ExternalDatasourceGrantServiceTest {

    private final ExternalDatasourceGrantMapper grantMapper = mock(ExternalDatasourceGrantMapper.class);
    private final ExternalDatasourceMapper datasourceMapper = mock(ExternalDatasourceMapper.class);
    private final PermissionService permissionService = new PermissionService();
    private final ExternalDatasourceGrantService service =
            new ExternalDatasourceGrantService(grantMapper, datasourceMapper, permissionService);

    @BeforeEach
    void setUp() {
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private static LoginUser login(Long userId, List<String> roles, List<String> permissions) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setUsername("operator");
        user.setRoles(roles);
        user.setPermissions(permissions);
        return user;
    }

    @Test
    @DisplayName("替换授权：新主体插入、移除主体软删、同键复活不重复插入")
    void replaceGrantsInsertsDeletesAndRevives() {
        when(datasourceMapper.selectById(9L)).thenReturn(new ExternalDatasource());
        ExternalDatasourceGrant revokedHistory = grant(9L, "USER", "42", 1);
        ExternalDatasourceGrant aliveRow = grant(9L, "ROLE", "designer", 0);
        when(grantMapper.selectAllIncludingDeleted(9L))
                .thenReturn(List.of(revokedHistory, aliveRow));

        service.replaceGrants(9L, List.of(
                new ExternalDatasourceGrantService.GrantItem("user", "42"),
                new ExternalDatasourceGrantService.GrantItem("ROLE", "designer")));

        verify(grantMapper).reviveById(revokedHistory.getId());
        verify(grantMapper, never()).insert(any(ExternalDatasourceGrant.class));
        verify(grantMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("替换授权：移除既有主体走软删；新增主体走插入")
    void replaceGrantsSoftDeletesRemovedAndInsertsNew() {
        when(datasourceMapper.selectById(9L)).thenReturn(new ExternalDatasource());
        ExternalDatasourceGrant aliveRow = grant(9L, "ROLE", "designer", 0);
        when(grantMapper.selectAllIncludingDeleted(9L)).thenReturn(List.of(aliveRow));

        service.replaceGrants(9L, List.of(new ExternalDatasourceGrantService.GrantItem("USER", "42")));

        verify(grantMapper).deleteById(aliveRow.getId());
        verify(grantMapper).insert(any(ExternalDatasourceGrant.class));
        verify(grantMapper, never()).reviveById(anyLong());
    }

    @Test
    @DisplayName("替换授权：主体类型与键非法、数据源不存在均拒绝")
    void replaceGrantsValidatesSubjectAndDatasource() {
        assertThatThrownBy(() -> service.replaceGrants(9L, List.of(
                new ExternalDatasourceGrantService.GrantItem("GROUP", "x"))))
                .isInstanceOf(BaseException.class);
        when(datasourceMapper.selectById(9L)).thenReturn(new ExternalDatasource());
        assertThatThrownBy(() -> service.replaceGrants(9L, List.of(
                new ExternalDatasourceGrantService.GrantItem("USER", "abc"))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.replaceGrants(9L, List.of(
                new ExternalDatasourceGrantService.GrantItem("ROLE", "非 法"))))
                .isInstanceOf(BaseException.class);
        when(datasourceMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.replaceGrants(404L, List.of()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("引用判定：管理员旁路需数据源存在；普通主体按用户/角色匹配；未登录拒绝")
    void isReferenceAllowedFollowsManagerBypassAndGrantMatch() {
        LoginUserHolder.set(login(42L, List.of("designer"), List.of()));
        when(datasourceMapper.selectById(9L)).thenReturn(new ExternalDatasource());
        when(datasourceMapper.selectById(7L)).thenReturn(new ExternalDatasource());
        when(grantMapper.selectList(any())).thenReturn(List.of(
                grant(9L, "USER", "42", 0),
                grant(7L, "ROLE", "designer", 0)));

        assertThat(service.isReferenceAllowed(9L)).isTrue();
        assertThat(service.isReferenceAllowed(7L)).isTrue();
        assertThat(service.isReferenceAllowed(8L)).isFalse();

        when(datasourceMapper.selectById(9L)).thenReturn(null);
        assertThat(service.isReferenceAllowed(9L)).isFalse();
    }

    @Test
    @DisplayName("管理员旁路：持 manage 权限直接放行且不查授权表")
    void managerBypassSkipsGrantLookup() {
        LoginUserHolder.set(login(42L, List.of(), List.of("workflow:datasource:manage")));
        when(datasourceMapper.selectById(9L)).thenReturn(new ExternalDatasource());
        assertThat(service.isReferenceAllowed(9L)).isTrue();
        verify(grantMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("可引用集合：管理员返回全部数据源；普通主体返回匹配授权行；未登录为空")
    void referenceAllowedDatasourceIdsSplitsManagerAndGranted() {
        assertThat(service.referenceAllowedDatasourceIds()).isEmpty();

        LoginUserHolder.set(login(42L, List.of(), List.of("workflow:datasource:manage")));
        ExternalDatasource ds1 = new ExternalDatasource();
        ds1.setId(9L);
        ExternalDatasource ds2 = new ExternalDatasource();
        ds2.setId(11L);
        when(datasourceMapper.selectList(any())).thenReturn(List.of(ds1, ds2));
        assertThat(service.referenceAllowedDatasourceIds()).containsExactlyInAnyOrder(9L, 11L);

        LoginUserHolder.set(login(42L, List.of("designer"), List.of()));
        when(grantMapper.selectList(any())).thenReturn(List.of(grant(9L, "ROLE", "designer", 0)));
        assertThat(service.referenceAllowedDatasourceIds()).containsExactly(9L);

        LoginUserHolder.set(login(42L, List.of(), List.of()));
        when(grantMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.referenceAllowedDatasourceIds()).isEqualTo(Set.of());
    }

    private static ExternalDatasourceGrant grant(Long datasourceId, String type, String key, int deleted) {
        ExternalDatasourceGrant row = new ExternalDatasourceGrant();
        row.setId(java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 1_000_000));
        row.setDatasourceId(datasourceId);
        row.setSubjectType(type);
        row.setSubjectKey(key);
        row.setDeleted(deleted);
        return row;
    }
}
