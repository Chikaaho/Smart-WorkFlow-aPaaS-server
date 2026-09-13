package com.sw.ck.system.service;

import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.mapper.SysTenantMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * I5 租户有效性校验行为证据（方向 §3.2/§4.B10）：
 * 租户存在/启用/未过期 → 有效；不存在/停用/过期/null → 无效。
 */
@DisplayName("I5 租户有效性校验行为证据")
class TenantValidityServiceTest {

    private final SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
    private final TenantValidityService service = new TenantValidityService(tenantMapper);

    private SysTenant tenant(Long id, Integer status, LocalDateTime expireAt) {
        SysTenant t = new SysTenant();
        t.setId(id);
        t.setName("租户" + id);
        t.setCode("t" + id);
        t.setStatus(status);
        t.setExpireTime(expireAt);
        return t;
    }

    @Test
    void activeTenant_shouldBeValid() {
        when(tenantMapper.selectGlobalById(1L)).thenReturn(tenant(1L, 0, null));
        assertThat(service.isValid(1L)).isTrue();
    }

    @Test
    void missingTenant_shouldBeInvalid() {
        when(tenantMapper.selectGlobalById(9L)).thenReturn(null);
        assertThat(service.isValid(9L)).isFalse();
    }

    @Test
    void disabledTenant_shouldBeInvalid() {
        when(tenantMapper.selectGlobalById(2L)).thenReturn(tenant(2L, 1, null));
        assertThat(service.isValid(2L)).isFalse();
    }

    @Test
    void expiredTenant_shouldBeInvalid() {
        when(tenantMapper.selectGlobalById(3L))
                .thenReturn(tenant(3L, 0, LocalDateTime.now().minusDays(1)));
        assertThat(service.isValid(3L)).isFalse();
    }

    @Test
    void nullTenantId_shouldBeInvalid() {
        assertThat(service.isValid(null)).isFalse();
    }

    @Test
    void unknownStatusTenant_shouldBeInvalid() {
        when(tenantMapper.selectGlobalById(4L)).thenReturn(tenant(4L, 7, null));
        assertThat(service.isValid(4L)).isFalse();
    }
}
