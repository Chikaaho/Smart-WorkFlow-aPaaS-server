package com.sw.ck.system.api.tenant;

/**
 * 租户有效性契约（I5 复验 G2a）。
 * <p>
 * 供跨模块（如 openapi 非浏览器入口）在建立代理上下文前校验租户存在、
 * 启用与有效期；实现由 sw-biz-system 提供，消费方只依赖本契约。
 * </p>
 */
public interface TenantValidityFacade {

    /**
     * 校验租户当前是否有效（存在、启用、未过期）。
     *
     * @param tenantId 租户 ID（null 视为无效）
     * @return 有效返回 true
     */
    boolean isValid(Long tenantId);
}
