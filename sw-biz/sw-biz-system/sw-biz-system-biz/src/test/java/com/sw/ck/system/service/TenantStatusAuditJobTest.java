package com.sw.ck.system.service;

import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.entity.SysTenantStatusAudit;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.mapper.SysTenantStatusAuditMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantStatusAuditJobTest {

    @Test
    void expiredTenantRecordsEffectiveAdmissionResultWithoutChangingTenantRow() {
        SysTenantMapper tenants = mock(SysTenantMapper.class);
        SysTenantStatusAuditMapper audits = mock(SysTenantStatusAuditMapper.class);
        SysTenant tenant = new SysTenant();
        tenant.setId(94L);
        tenant.setStatus(0);
        tenant.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(tenants.selectList(any())).thenReturn(List.of(tenant));

        new TenantStatusAuditJob(tenants, audits).auditExpiredDue();

        ArgumentCaptor<SysTenantStatusAudit> audit = ArgumentCaptor.forClass(SysTenantStatusAudit.class);
        verify(audits).insert(audit.capture());
        assertThat(audit.getValue().getTenantId()).isEqualTo(94L);
        assertThat(audit.getValue().getAction()).isEqualTo("EXPIRED");
        assertThat(audit.getValue().getReason()).isEqualTo("租户有效期届满");
        assertThat(audit.getValue().getResult()).isEqualTo("已阻止业务准入");
        assertThat(audit.getValue().getOperatorId()).isZero();
    }
}
