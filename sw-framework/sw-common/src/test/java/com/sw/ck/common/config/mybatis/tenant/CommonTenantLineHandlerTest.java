package com.sw.ck.common.config.mybatis.tenant;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户行过滤：超管租户（系统超管）旁路与普通租户隔离的边界单测。
 * <p>
 * 超管租户 0 的系统超管是唯一拥有跨租户可见性的身份；普通用户（含同处租户 0 的普通用户）
 * 一律按登录租户过滤，隔离不被削弱。
 * </p>
 */
@DisplayName("租户行过滤：超管旁路与普通租户隔离")
class CommonTenantLineHandlerTest {

    private static final class StubLoginContext implements LoginContextProvider {
        private Long tenantId = 0L;
        private boolean superAdmin;

        @Override
        public Long getUserId() {
            return 1L;
        }

        @Override
        public Long getTenantId() {
            return tenantId;
        }

        @Override
        public Long getDeptId() {
            return null;
        }

        @Override
        public DataScopeType getDataScopeType() {
            return DataScopeType.ALL;
        }

        @Override
        public Set<Long> getCustomDeptIds() {
            return Set.of();
        }

        @Override
        public boolean isSuperAdmin() {
            return superAdmin;
        }
    }

    private final StubLoginContext loginContext = new StubLoginContext();
    private final TenantProperties properties = new TenantProperties();

    @AfterEach
    void tearDown() {
        while (TenantLineSuspension.isSuspended()) {
            TenantLineSuspension.restore();
        }
    }

    @Test
    @DisplayName("超管：业务表不追加 tenant_id 条件（跨租户可见）")
    void superAdminBypassesTenantFilter() {
        loginContext.superAdmin = true;
        CommonTenantLineHandler handler = new CommonTenantLineHandler(properties, loginContext);

        assertThat(handler.ignoreTable("sys_user")).as("超管不受租户列过滤").isTrue();
        assertThat(handler.ignoreTable("sys_dept")).isTrue();
        assertThat(handler.ignoreTable("sw_form_def")).isTrue();
    }

    @Test
    @DisplayName("普通用户：业务表仍按登录租户过滤；忽略表白名单不受影响")
    void normalUserStaysTenantScoped() {
        loginContext.superAdmin = false;
        loginContext.tenantId = 0L;
        properties.setIgnoreTables(java.util.List.of("sys_menu"));
        CommonTenantLineHandler handler = new CommonTenantLineHandler(properties, loginContext);

        assertThat(handler.ignoreTable("sys_user")).as("租户 0 的普通用户同样受租户过滤").isFalse();
        assertThat(handler.ignoreTable("sys_menu")).as("忽略表白名单照旧").isTrue();
        assertThat(handler.getTenantId()).as("普通用户取登录租户").isNotNull();
    }

    @Test
    @DisplayName("认证装载挂起：过滤临时跳过（登录链专用）")
    void suspensionSkipsFilter() {
        loginContext.superAdmin = false;
        CommonTenantLineHandler handler = new CommonTenantLineHandler(properties, loginContext);

        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            assertThat(handler.ignoreTable("sys_user")).isTrue();
        }
        assertThat(handler.ignoreTable("sys_user")).as("挂起恢复后照常过滤").isFalse();
    }

    @Test
    @DisplayName("无登录上下文：fail closed 拒绝生成租户条件")
    void missingLoginContextFailsClosed() {
        loginContext.tenantId = null;
        loginContext.superAdmin = false;
        CommonTenantLineHandler handler = new CommonTenantLineHandler(properties, loginContext);

        assertThatThrownBy(handler::getTenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户上下文缺失");
    }
}
