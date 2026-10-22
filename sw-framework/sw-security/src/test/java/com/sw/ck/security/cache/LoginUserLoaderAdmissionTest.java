package com.sw.ck.security.cache;

import com.sw.ck.security.exception.SecurityInfrastructureException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.spi.UserDetailsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginUserLoaderAdmissionTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UserDetailsProvider> providers = mock(ObjectProvider.class);
    private final LoginUserCacheService cache = mock(LoginUserCacheService.class);
    private final UserDetailsProvider provider = mock(UserDetailsProvider.class);
    private final LoginUserLoader loader = new LoginUserLoader(providers, cache);

    @Test
    void cachedIdentityIsCheckedOnEveryRequestAndEvictedOnDenial() {
        LoginUser identity = identity(8L, 41L);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(cache.get(8L)).thenReturn(identity);
        when(provider.isCachedIdentityValid(identity)).thenReturn(true, false);
        assertThat(loader.loadByUserId(8L)).isSameAs(identity);
        assertThat(loader.loadByUserId(8L)).isNull();
        verify(provider, times(2)).isCachedIdentityValid(identity);
        verify(cache).evict(8L);
        verify(provider, never()).loadByUserId(anyLong());
        verify(cache, never()).cache(any());
    }

    @Test
    void infrastructureFailureOnCachedAdmissionPropagatesInsteadOfAllowingIdentity() {
        LoginUser identity = identity(8L, 41L);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(cache.get(8L)).thenReturn(identity);
        when(provider.isCachedIdentityValid(identity)).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> loader.loadByUserId(8L)).isInstanceOf(IllegalStateException.class);
        verify(cache, never()).cache(any());
    }

    @Test
    void missingProviderCannotBeHiddenByWarmCache() {
        when(cache.get(8L)).thenReturn(identity(8L, 41L));
        assertThatThrownBy(() -> loader.loadByUserId(8L)).isInstanceOf(SecurityInfrastructureException.class);
    }

    @Test
    void foreignUserInCacheIsRejectedWithoutCallingAdmission() {
        when(providers.getIfAvailable()).thenReturn(provider);
        when(cache.get(8L)).thenReturn(identity(9L, 41L));
        assertThat(loader.loadByUserId(8L)).isNull();
        verify(cache).evict(8L);
        verifyNoInteractions(provider);
    }

    @Test
    void compatibilityClaimedIdentityRequiresMatchingUserAndTenant() {
        UserDetailsProvider compatibility = mock(UserDetailsProvider.class, CALLS_REAL_METHODS);
        when(compatibility.loadByUserId(8L)).thenReturn(identity(8L, 41L), identity(9L, 41L), identity(8L, 42L));
        assertThat(compatibility.loadClaimedWorkIdentity(8L, 41L)).isNotNull();
        assertThat(compatibility.loadClaimedWorkIdentity(8L, 41L)).isNull();
        assertThat(compatibility.loadClaimedWorkIdentity(8L, 41L)).isNull();
        assertThat(compatibility.loadClaimedWorkIdentity(8L, null)).isNull();
        assertThat(compatibility.loadClaimedWorkIdentity(null, 41L)).isNull();
    }

    @Test
    void compatibilityProviderReloadMustMatchUserAndTenant() {
        UserDetailsProvider compatibility = mock(UserDetailsProvider.class, CALLS_REAL_METHODS);
        LoginUser cached = identity(8L, 41L);
        when(compatibility.loadByUserId(8L)).thenReturn(identity(8L, 41L), identity(8L, 42L), null);
        assertThat(compatibility.isCachedIdentityValid(cached)).isTrue();
        assertThat(compatibility.isCachedIdentityValid(cached)).isFalse();
        assertThat(compatibility.isCachedIdentityValid(cached)).isFalse();
        assertThat(compatibility.isCachedIdentityValid(identity(8L, null))).isFalse();
    }

    private static LoginUser identity(Long id, Long tenantId) {
        LoginUser user = new LoginUser();
        user.setUserId(id);
        user.setTenantId(tenantId);
        return user;
    }
}
