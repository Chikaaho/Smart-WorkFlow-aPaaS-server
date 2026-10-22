package com.sw.ck.system.service;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.tenant.TenantWorkAdmission;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.mapper.SysTenantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.IntSupplier;

/** 租户行锁与持久认领同事务，和停用写入形成明确先后，不把外部发送包进锁内。 */
@Service
public class TenantWorkAdmissionService implements TenantWorkAdmission {
    private final SysTenantMapper tenants;
    private final TransactionTemplate claims;

    public TenantWorkAdmissionService(SysTenantMapper tenants, PlatformTransactionManager transactions) {
        this.tenants = tenants;
        claims = new TransactionTemplate(transactions);
        claims.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        claims.setTimeout(10);
    }

    @Override
    public int claim(Long tenantId, IntSupplier persistentClaim) {
        Objects.requireNonNull(persistentClaim, "persistentClaim");
        if (tenantId == null) return 0;
        Integer claimed = claims.execute(status -> {
            try (var ignored = TenantLineSuspension.suspended()) {
                SysTenant tenant = tenants.selectGlobalByIdForUpdate(tenantId);
                if (tenant == null || !Integer.valueOf(0).equals(tenant.getStatus())
                        || tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
                    return 0;
                }
                return persistentClaim.getAsInt();
            }
        });
        return claimed == null ? 0 : claimed;
    }
}
