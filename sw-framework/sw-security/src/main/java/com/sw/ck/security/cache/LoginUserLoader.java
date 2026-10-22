package com.sw.ck.security.cache;

import com.sw.ck.security.exception.SecurityInfrastructureException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.spi.UserDetailsProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 登录上下文「装载 + 缓存」骨架：缓存命中仍校验实时准入；未命中回查 {@link UserDetailsProvider}
 * 并写回缓存。认证流程（FilterChain）应通过本类获取 LoginUser，而不是直接调用
 * {@link UserDetailsProvider}，以保证缓存策略统一生效。
 * <p>
 * {@link UserDetailsProvider} 是定义在下层（sw-security）的 SPI，实现由上层（sw-biz-system）
 * 提供。本类不再用 {@code @ConditionalOnBean} 在【定义期】门控自身是否创建（那会让创建与否
 * 取决于自动配置处理顺序——若 SecurityAutoConfiguration 早于 SystemAutoConfiguration 被处理，
 * 实现尚未注册，条件静默不匹配，本 Bean 不创建，最终对所有受保护请求静默 401）。改为永远创建，
 * 经 {@link ObjectProvider} 在【运行期】惰性解析实现：缺失则抛
 * {@link SecurityInfrastructureException}（→ 5xx），而非降级为 401。
 */
@RequiredArgsConstructor
public class LoginUserLoader {

    private final ObjectProvider<UserDetailsProvider> userDetailsProviderProvider;
    private final LoginUserCacheService loginUserCacheService;

    public LoginUser loadByUserId(Long userId) {
        UserDetailsProvider userDetailsProvider = userDetailsProviderProvider.getIfAvailable();
        if (userDetailsProvider == null) {
            throw new SecurityInfrastructureException(
                    "未发现任何 UserDetailsProvider 实现（应由 sw-biz-system 提供）：安全链未正确装配，" +
                            "无法装载登录用户。拒绝降级为 401，以暴露装配缺陷。");
        }
        LoginUser cached = loginUserCacheService.get(userId);
        if (cached != null) {
            if (!java.util.Objects.equals(userId, cached.getUserId())
                    || !userDetailsProvider.isCachedIdentityValid(cached)) {
                loginUserCacheService.evict(userId);
                return null;
            }
            return cached;
        }
        LoginUser loginUser = userDetailsProvider.loadByUserId(userId);
        if (loginUser != null) {
            loginUserCacheService.cache(loginUser);
        }
        return loginUser;
    }

    /**
     * 踢人下线 / 权限变更立即生效：清掉缓存即可，token 本身无需作废，
     * 下一次请求会因缓存未命中重新回查最新数据。
     */
    public void kickOut(Long userId) {
        loginUserCacheService.evict(userId);
    }
}
