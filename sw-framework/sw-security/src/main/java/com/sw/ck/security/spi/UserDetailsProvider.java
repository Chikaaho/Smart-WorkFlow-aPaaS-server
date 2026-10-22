package com.sw.ck.security.spi;

import com.sw.ck.security.holder.LoginUser;

/**
 * 由 sw-module-system 实现：security 框架本身不持有用户数据，仅定义加载契约。
 * <p>
 * 装载方法按 username/userId 返回完整 {@link LoginUser}；缓存命中通过
 * {@link #isCachedIdentityValid(LoginUser)} 校验当前准入，租户状态和有效期不能沿用旧快照。
 * <p>
 * 调用方（认证流程，Prompt 3）应优先从 {@code LoginUserCacheService} 读取缓存的完整
 * LoginUser，命中后实时校验准入，未命中时回查完整身份并写回缓存。JWT 本身只携带 userId，
 * 不下放 deptId/dataScope/permissions 等会变化的权限信息，以便权限变更后只需让缓存失效
 * 即可立即生效，无需等 token 过期。
 */
public interface UserDetailsProvider {

    LoginUser loadByUsername(String username);

    LoginUser loadByUserId(Long userId);

    /**
     * 每次缓存命中仍需校验当前业务准入。兼容实现默认权威重载；上层可覆盖为
     * 不装载完整角色权限的实时校验，但不得缓存租户的启用/有效期结论。
     */
    default boolean isCachedIdentityValid(LoginUser cached) {
        if (cached == null || cached.getUserId() == null || cached.getTenantId() == null) return false;
        LoginUser current = loadByUserId(cached.getUserId());
        return current != null && java.util.Objects.equals(current.getUserId(), cached.getUserId())
                && java.util.Objects.equals(current.getTenantId(), cached.getTenantId());
    }
}
