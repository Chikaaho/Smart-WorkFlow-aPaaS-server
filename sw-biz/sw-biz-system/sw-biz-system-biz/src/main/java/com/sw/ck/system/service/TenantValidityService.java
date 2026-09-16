package com.sw.ck.system.service;

import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.mapper.SysTenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 租户有效性校验服务（I5）。
 * <p>
 * 第一方与第三方登录、refresh、缓存重载及关键异步身份恢复统一经本服务校验租户
 * 存在、启用状态与有效期；租户失效后不得建立新会话，既有会话在下一次权威装载
 * 时收敛（装载返回 null → 缓存驱逐 → 下次请求 401）。
 * </p>
 * <p>
 * 校验语义（I5 §3.2）：
 * <ul>
 *   <li>租户行不存在 → 无效；</li>
 *   <li>status != 0（停用/未知值）→ 无效；</li>
 *   <li>expire_time 非空且早于当前时间 → 无效；</li>
 *   <li>tenantId 为 null → 无效（fail closed，不按租户 0 放行）。</li>
 * </ul>
 * </p>
 */
@Service
public class TenantValidityService {

    private static final Logger log = LoggerFactory.getLogger(TenantValidityService.class);

    private final SysTenantMapper tenantMapper;

    public TenantValidityService(SysTenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    /**
     * 校验租户当前是否有效（存在、启用、未过期）。
     *
     * @param tenantId 租户 ID
     * @return 有效返回 true；无效或参数为 null 返回 false
     */
    public boolean isValid(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        // sys_tenant 为全局表：登录/装载路径可能尚无租户上下文，读取必须挂起租户过滤
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            SysTenant tenant = tenantMapper.selectGlobalById(tenantId);
            if (tenant == null) {
                log.warn("租户不存在: tenantId={}", tenantId);
                return false;
            }
            if (tenant.getStatus() == null || tenant.getStatus() != 0) {
                log.warn("租户已停用: tenantId={}, status={}", tenantId, tenant.getStatus());
                return false;
            }
            if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
                log.warn("租户已过期: tenantId={}, expireTime={}", tenantId, tenant.getExpireTime());
                return false;
            }
            return true;
        }
    }

    /**
     * 校验失败时抛出 IllegalStateException（用于登录/装载等必须中断的路径）。
     * <p>异常文案不携带租户标识；租户号只进上面的结构化日志，避免其随响应外显。</p>
     */
    public void requireValid(Long tenantId) {
        if (!isValid(tenantId)) {
            throw new IllegalStateException("所属租户当前不可用");
        }
    }
}
