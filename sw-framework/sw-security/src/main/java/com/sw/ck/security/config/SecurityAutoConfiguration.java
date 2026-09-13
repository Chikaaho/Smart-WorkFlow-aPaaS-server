package com.sw.ck.security.config;

import com.sw.ck.common.config.mybatis.MybatisPlusConfig;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.exception.SecurityInfrastructureException;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.jwt.JwtTokenProviderImpl;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.security.support.SecurityLoginContextProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * before = MybatisPlusConfig.class：确保本类的 LoginContextProvider 先于
 * sw-common 的兜底默认实现注册，使 @ConditionalOnMissingBean 生效、降级实现不会抢先装载。
 * sw-common 不能反向依赖 sw-security，故此排序只能在依赖方向合法的这一侧声明。
 */
@AutoConfiguration(before = MybatisPlusConfig.class)
@EnableConfigurationProperties({SecurityProperties.class, JwtProperties.class,
        DebugAuthenticationProperties.class})
public class SecurityAutoConfiguration {

    @Bean
    public LoginContextProvider securityLoginContextProvider() {
        return new SecurityLoginContextProvider();
    }

    /**
     * 默认 JWT 实现，业务方可注册同类型自定义 Bean 覆盖（@ConditionalOnMissingBean）。
     */
    @Bean
    @ConditionalOnMissingBean(JwtTokenProvider.class)
    public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) {
        return new JwtTokenProviderImpl(jwtProperties);
    }

    /**
     * 登载+缓存骨架【永远创建】，不再用 {@code @ConditionalOnBean(UserDetailsProvider.class)}
     * 在定义期门控——那让创建与否取决于自动配置处理顺序（本类按 FQN 排序早于
     * SystemAutoConfiguration 被处理，UserDetailsProvider 实现此时尚未注册，条件静默不匹配，
     * 这两个 Bean 不创建，过滤器拿到 null，最终对所有受保护请求静默 401）。
     * {@link LoginUserLoader} 改为经 {@link ObjectProvider} 在运行期惰性解析实现，根治顺序敏感。
     */
    @Bean
    public LoginUserCacheService loginUserCacheService(RedisTemplate<String, Object> redisTemplate,
                                                         JwtProperties jwtProperties) {
        return new LoginUserCacheService(redisTemplate, jwtProperties);
    }

    @Bean
    public LoginUserLoader loginUserLoader(ObjectProvider<UserDetailsProvider> userDetailsProviderProvider,
                                            LoginUserCacheService loginUserCacheService) {
        return new LoginUserLoader(userDetailsProviderProvider, loginUserCacheService);
    }

    /**
     * 启动期硬校验：在运行期（所有 Bean 已就绪、不受自动配置处理顺序影响）检查是否存在
     * {@link UserDetailsProvider} 实现。缺失 → 开机即失败，绝不允许「安全链不可用却对所有
     * 受保护请求静默 401」上线。这是 {@code @ConditionalOnBean} 顺序敏感缺陷的根治性兜底：
     * 即便上层忘记提供实现，应用也会在启动时显式爆出装配缺陷，而非被 permitAll 长期掩盖。
     */
    @Bean
    public ApplicationRunner userDetailsProviderPresenceCheck(
            ObjectProvider<UserDetailsProvider> userDetailsProviderProvider) {
        return args -> {
            if (userDetailsProviderProvider.getIfAvailable() == null) {
                throw new SecurityInfrastructureException(
                        "启动自检失败：未发现任何 UserDetailsProvider 实现（应由 sw-biz-system 提供）。" +
                                "安全链将无法认证任何受保护请求，拒绝启动以暴露装配缺陷。");
            }
        };
    }

    /** 调试认证一旦在非 dev/test profile 激活，启动即失败，确保隔离不是约定而是门禁。 */
    @Bean
    public ApplicationRunner debugAuthenticationProfileCheck(
            DebugAuthenticationProperties debugAuthenticationProperties,
            Environment environment) {
        return args -> {
            if (debugAuthenticationProperties.isEnabled()
                    && !DebugAuthenticationProfile.isDevelopmentOnly(environment)) {
                throw new IllegalStateException(
                        "调试认证仅允许在 dev/test profile 使用，当前 profile 不满足 fail-closed 门禁");
            }
        };
    }

    /**
     * JWT 签名密钥启动期硬校验（I5）：密钥缺失、空白或仍为仓库已知占位值时启动失败。
     * 占位值来自 application.yml 默认表达式，出现在生产即意味着环境变量未注入——
     * 已知默认密钥可被任何持源码者伪造 token，属认证红线。
     */
    @Bean
    public ApplicationRunner jwtSecretPresenceCheck(JwtProperties jwtProperties) {
        return args -> {
            String secret = jwtProperties.getSecret();
            if (secret == null || secret.isBlank()
                    || secret.contains("CHANGE-ME")) {
                throw new IllegalStateException(
                        "JWT 签名密钥未配置或仍为仓库占位值：必须经外部安全配置注入 sw.security.jwt.secret"
                                + "（如环境变量 JWT_SECRET），默认/占位密钥不允许上线");
            }
        };
    }
}
