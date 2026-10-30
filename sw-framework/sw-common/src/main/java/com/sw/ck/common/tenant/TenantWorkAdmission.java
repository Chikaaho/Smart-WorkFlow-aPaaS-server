package com.sw.ck.common.tenant;

import java.util.function.IntSupplier;

/**
 * 租户后台工作的持久认领边界，由上层租户权威实现。
 * 回调只能执行有界的数据库条件认领，不能调用外部服务或执行完整业务。
 * 实现须将租户有效性和认领串行化，并在返回前提交认领；失效返回 0，保留原对象。
 */
@FunctionalInterface
public interface TenantWorkAdmission {
    int claim(Long tenantId, IntSupplier persistentClaim);
}
