package com.sw.ck.system.config;

import com.sw.ck.system.api.tenant.TenantValidityFacade;
import com.sw.ck.system.service.TenantValidityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I5 租户有效性契约装配验证：{@code SystemAutoConfiguration} 的 bean 由方法引用迁移为
 * {@code Optional<Boolean>} 形态后，仍然是恒 present 的 fail-closed 判定。
 */
class SystemAutoConfigurationTenantValidityTest {

    private final TenantValidityService tenantValidityService = mock(TenantValidityService.class);
    private final TenantValidityFacade facade =
            new SystemAutoConfiguration().tenantValidityFacade(tenantValidityService);

    @Test
    @DisplayName("有效租户：present true")
    void validTenantIsPresentTrue() {
        when(tenantValidityService.isValid(1L)).thenReturn(true);
        assertThat(facade.isValid(1L)).contains(true);
    }

    @Test
    @DisplayName("不存在/停用/过期租户：present false（不冒充无法判定）")
    void invalidTenantIsPresentFalse() {
        when(tenantValidityService.isValid(2L)).thenReturn(false);
        assertThat(facade.isValid(2L)).contains(false);
    }

    @Test
    @DisplayName("租户上下文缺失：present false（fail-closed，不得以 empty 放行）")
    void missingTenantContextIsPresentFalse() {
        when(tenantValidityService.isValid(null)).thenReturn(false);
        Optional<Boolean> result = facade.isValid(null);
        assertThat(result).as("恒 present：empty 不得作为放行或无法判定出口").isPresent();
        assertThat(result.orElseThrow()).isFalse();
    }
}
