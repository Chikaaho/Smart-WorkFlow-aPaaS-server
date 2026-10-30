package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.entity.SysTenantStatusAudit;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.mapper.SysTenantStatusAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 将租户自然过期转成一次可追溯事件，不改变租户行或任何业务对象状态。 */
@Component
public class TenantStatusAuditJob {

    private static final Logger log = LoggerFactory.getLogger(TenantStatusAuditJob.class);
    private static final int MAX_BATCH = 100;

    private final SysTenantMapper tenantMapper;
    private final SysTenantStatusAuditMapper auditMapper;

    public TenantStatusAuditJob(SysTenantMapper tenantMapper, SysTenantStatusAuditMapper auditMapper) {
        this.tenantMapper = tenantMapper;
        this.auditMapper = auditMapper;
    }

    @Scheduled(fixedDelayString = "${sw.tenant.expiry-audit.fixed-delay-ms:60000}")
    public void auditExpiredDue() {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            List<SysTenant> expired = tenantMapper.selectList(Wrappers.<SysTenant>lambdaQuery()
                    .eq(SysTenant::getDeleted, 0)
                    .isNotNull(SysTenant::getExpireTime)
                    .le(SysTenant::getExpireTime, LocalDateTime.now())
                    .orderByAsc(SysTenant::getExpireTime)
                    .last("LIMIT " + MAX_BATCH));
            int recorded = 0;
            for (SysTenant tenant : expired) {
                LocalDateTime now = LocalDateTime.now();
                SysTenantStatusAudit audit = new SysTenantStatusAudit();
                audit.setTenantId(tenant.getId());
                audit.setAction("EXPIRED");
                audit.setOldStatus(tenant.getStatus());
                audit.setNewStatus(tenant.getStatus());
                audit.setExpireTime(tenant.getExpireTime());
                audit.setReason("租户有效期届满");
                audit.setOperatorId(0L);
                audit.setOccurredAt(now);
                audit.setResult("已阻止业务准入");
                audit.setCreateTime(now);
                audit.setCreateBy(0L);
                audit.setUpdateTime(now);
                audit.setUpdateBy(0L);
                audit.setDeleted(0);
                audit.setVersion(0L);
                try {
                    auditMapper.insert(audit);
                    recorded++;
                } catch (DuplicateKeyException alreadyRecorded) {
                    // 同一租户、同一有效期只记录一次；后续查询继续被准入层拒绝。
                }
            }
            if (recorded > 0) {
                log.warn("租户过期已记录: count={}", recorded);
            }
        }
    }
}
