package com.sw.ck.security.config;

import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.exception.SecurityInfrastructureException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.spi.UserDetailsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉死「登录成功但所有受保护请求恒 401」的装配缺陷回归：根因是 LoginUserLoader 被
 * {@code @ConditionalOnBean(UserDetailsProvider.class)} 在定义期门控，而 SecurityAutoConfiguration
 * 按 FQN 排序早于 SystemAutoConfiguration 被处理，UserDetailsProvider 实现此时尚未注册，条件
 * 静默不匹配 → Bean 不创建 → 过滤器拿到 null → 静默放行 → 对所有受保护请求 401。
 * <p>
 * 修复后的不变量（本测试断言）：UserDetailsProvider 缺失时【绝不静默降级为 401】，而是
 * <ul>
 *   <li>装载路径抛 {@link SecurityInfrastructureException}（最终渲染 5xx）；</li>
 *   <li>启动自检直接失败（应用拒绝启动）。</li>
 * </ul>
 * 把这个坑钉死，防止再被 permitAll 长期掩盖。
 */
class SecurityAssemblyRegressionTest {

    @Test
    void loadByUserId_whenNoUserDetailsProvider_throwsInfrastructureException_notSilent401() {
        @SuppressWarnings("unchecked")
        ObjectProvider<UserDetailsProvider> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        LoginUserCacheService cache = mock(LoginUserCacheService.class);
        when(cache.get(1L)).thenReturn(null); // 缓存未命中 → 必须回查，回查时暴露装配缺失

        LoginUserLoader loader = new LoginUserLoader(emptyProvider, cache);

        assertThatThrownBy(() -> loader.loadByUserId(1L))
                .isInstanceOf(SecurityInfrastructureException.class)
                .hasMessageContaining("UserDetailsProvider");
    }

    @Test
    void loadByUserId_whenProviderPresent_delegatesAndReturnsUser() {
        UserDetailsProvider impl = mock(UserDetailsProvider.class);
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        when(impl.loadByUserId(1L)).thenReturn(user);

        @SuppressWarnings("unchecked")
        ObjectProvider<UserDetailsProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(impl);

        LoginUserCacheService cache = mock(LoginUserCacheService.class);
        when(cache.get(1L)).thenReturn(null);

        LoginUserLoader loader = new LoginUserLoader(provider, cache);

        assertThat(loader.loadByUserId(1L)).isSameAs(user);
    }

    @Test
    void startupSelfCheck_whenNoUserDetailsProvider_failsToStart() {
        @SuppressWarnings("unchecked")
        ObjectProvider<UserDetailsProvider> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        SecurityAutoConfiguration config = new SecurityAutoConfiguration();

        assertThatThrownBy(() -> config.userDetailsProviderPresenceCheck(emptyProvider).run(null))
                .isInstanceOf(SecurityInfrastructureException.class)
                .hasMessageContaining("启动自检失败");
    }

    @Test
    void startupSelfCheck_whenUserDetailsProviderPresent_passes() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<UserDetailsProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(UserDetailsProvider.class));

        SecurityAutoConfiguration config = new SecurityAutoConfiguration();

        // 不抛异常即视为通过（启动放行）
        config.userDetailsProviderPresenceCheck(provider).run(null);
    }

    @Test
    void debugAuthStartupCheck_rejectsNonDevelopmentProfile() {
        DebugAuthenticationProperties properties = new DebugAuthenticationProperties();
        properties.setEnabled(true);
        SecurityAutoConfiguration config = new SecurityAutoConfiguration();

        assertThatThrownBy(() -> config.debugAuthenticationProfileCheck(
                properties, environment("prod")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅允许在 dev/test profile");
    }

    @Test
    void debugAuthStartupCheck_allowsExplicitDevelopmentProfile() throws Exception {
        DebugAuthenticationProperties properties = new DebugAuthenticationProperties();
        properties.setEnabled(true);
        SecurityAutoConfiguration config = new SecurityAutoConfiguration();

        config.debugAuthenticationProfileCheck(
                properties, environment("dev")).run(null);
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}
