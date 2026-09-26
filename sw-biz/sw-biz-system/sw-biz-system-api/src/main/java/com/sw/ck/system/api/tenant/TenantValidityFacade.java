package com.sw.ck.system.api.tenant;

import java.util.Optional;

/**
 * 租户有效性契约（I5 复验 G2a）。
 * <p>
 * 供跨模块（如 openapi 非浏览器入口）在建立代理上下文前校验租户存在、
 * 启用与有效期；实现由 sw-biz-system 提供，消费方只依赖本契约。
 * </p>
 * <p>
 * 本契约是 fail-closed 判定：判定结果必须由 present 值承载，
 * 调用方不得把 empty 当作"有效"。
 * </p>
 */
public interface TenantValidityFacade {

    /**
     * 校验租户当前是否有效（存在、启用、未过期）。
     *
     * @param tenantId 租户 ID
     * @return present = 判定结果（true 有效 / false 无效，{@code null} 与不存在的租户一律判定为无效）；
     *         当前契约恒 present，empty 不得作为"无法判定"的宽泛出口
     */
    Optional<Boolean> isValid(Long tenantId);
}
