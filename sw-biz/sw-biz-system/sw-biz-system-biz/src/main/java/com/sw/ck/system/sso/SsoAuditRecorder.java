package com.sw.ck.system.sso;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SsoAuditRecord;
import com.sw.ck.system.mapper.SsoAuditRecordMapper;
import com.sw.ck.system.vo.SsoAuditListVO;
import com.sw.ck.system.vo.SsoAuditRecordVO;

import jakarta.annotation.PostConstruct;

import java.util.List;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SSO 审计写入与查询（I5 复验 G7）。自 SsoAuthService 拆出（行为不变）。
 */
@Service
@Log4j2
class SsoAuditRecorder {

    @Autowired
    private SsoAuditRecordMapper auditMapper;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate denialTxTemplate;

    @PostConstruct
    void initDenialTxTemplate() {
        if (transactionManager != null) {
            TransactionTemplate tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.denialTxTemplate = tpl;
        }
    }

    void audit(String provider, String eventType, String result, Long actorId,
               Long localUserId, String externalDigest, String detail) {
        audit(provider, eventType, result, actorId, localUserId, externalDigest, detail, null);
    }

    /**
     * 审计写入。显式 tenantId 优先（回调/登录前发起路径无登录态，租户拦截器为
     * fail-closed，必须挂起过滤并显式携带 state 所属租户），否则取当前登录态租户。
     */
    void audit(String provider, String eventType, String result, Long actorId,
               Long localUserId, String externalDigest, String detail, Long explicitTenantId) {
        SsoAuditRecord record = buildAuditRecord(provider, eventType, result, actorId,
                localUserId, externalDigest, detail, explicitTenantId);
        try {
            insertAudit(record);
        } catch (Exception e) {
            log.warn("SSO 审计写入失败: provider={}, event={}", provider, eventType, e);
        }
    }

    /**
     * 拒绝/重放/冲突审计：以独立事务（REQUIRES_NEW）立即提交。
     * <p>
     * 拒绝路径在审计后必然抛出业务异常，外层 {@code @Transactional} 会整体回滚；
     * 若拒绝审计随外层事务回滚，重放、冲突与越权拒绝将无法按方向 §3.4/验收 #15
     * 持久查询。成功路径审计仍随业务事务提交（与动作保持原子）。
     * </p>
     */
    void auditDenial(String provider, String eventType, String result, Long actorId,
                     Long localUserId, String externalDigest, String detail, Long explicitTenantId) {
        SsoAuditRecord record = buildAuditRecord(provider, eventType, result, actorId,
                localUserId, externalDigest, detail, explicitTenantId);
        try {
            if (denialTxTemplate != null) {
                denialTxTemplate.execute(status -> {
                    insertAudit(record);
                    return Boolean.TRUE;
                });
            } else {
                insertAudit(record);
            }
        } catch (Exception e) {
            log.warn("SSO 拒绝审计写入失败: provider={}, event={}", provider, eventType, e);
        }
    }

    void auditDenial(String provider, String eventType, String result, Long actorId,
                     Long localUserId, String externalDigest, String detail) {
        auditDenial(provider, eventType, result, actorId, localUserId, externalDigest, detail, null);
    }

    /** 控制器层越权/候选拒绝审计入口（独立事务提交，不回随响应路径丢失）。 */
    void auditRejection(String provider, String eventType, String detail) {
        LoginUser current = LoginUserHolder.get();
        auditDenial(provider, eventType, SsoAuditResultEnum.DENIED.getCode(),
                current == null ? null : current.getUserId(), null, null, detail,
                current == null ? null : current.getTenantId());
    }

    private SsoAuditRecord buildAuditRecord(String provider, String eventType, String result, Long actorId,
                                            Long localUserId, String externalDigest, String detail, Long explicitTenantId) {
        SsoAuditRecord record = new SsoAuditRecord();
        LoginUser current = LoginUserHolder.get();
        record.setTenantId(explicitTenantId != null ? explicitTenantId
                : (current != null && current.getTenantId() != null ? current.getTenantId() : 0L));
        record.setProvider(provider);
        record.setEventType(eventType);
        record.setResult(result);
        record.setActorId(actorId != null ? actorId : (current != null ? current.getUserId() : null));
        record.setLocalUserId(localUserId);
        record.setExternalDigest(externalDigest);
        record.setDetail(detail);
        return record;
    }

    private void insertAudit(SsoAuditRecord record) {
        if (LoginUserHolder.get() == null) {
            try (Suspended ignored =
                         TenantLineSuspension.suspended()) {
                auditMapper.insert(record);
            }
        } else {
            auditMapper.insert(record);
        }
    }

    // ==================== 审计查询（I5 复验 G7） ====================

    /**
     * 按租户隔离查询 SSO 审计（调用方须经 {@code system:sso:audit:query} 权限守卫；
     * 无权/跨租户查询被拒绝由权限链与本查询的显式租户条件共同保证）。
     */
    SsoAuditListVO queryAudit(String provider, String eventType, String result,
                              Long localUserId, int page, int size) {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失，不能查询 SSO 审计");
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        var wrapper = Wrappers.<SsoAuditRecord>lambdaQuery()
                .eq(SsoAuditRecord::getTenantId, current.getTenantId())
                .eq(provider != null && !provider.isBlank(), SsoAuditRecord::getProvider, provider)
                .eq(eventType != null && !eventType.isBlank(), SsoAuditRecord::getEventType, eventType)
                .eq(result != null && !result.isBlank(), SsoAuditRecord::getResult, result)
                .eq(localUserId != null, SsoAuditRecord::getLocalUserId, localUserId)
                .orderByDesc(SsoAuditRecord::getId)
                .last("LIMIT " + safeSize + " OFFSET " + (long) Math.max(page, 0) * safeSize);
        List<SsoAuditRecord> records = auditMapper.selectList(wrapper);
        List<SsoAuditRecordVO> view = records.stream().map(r -> {
            SsoAuditRecordVO item = new SsoAuditRecordVO();
            item.setId(String.valueOf(r.getId()));
            item.setProvider(r.getProvider());
            item.setEventType(r.getEventType());
            item.setResult(r.getResult());
            item.setActorId(String.valueOf(r.getActorId()));
            item.setLocalUserId(String.valueOf(r.getLocalUserId()));
            item.setExternalDigestPrefix(r.getExternalDigest() == null ? ""
                    : r.getExternalDigest().substring(0, Math.min(8, r.getExternalDigest().length())));
            item.setDetail(r.getDetail() == null ? "" : r.getDetail());
            item.setCreateTime(String.valueOf(r.getCreateTime()));
            return item;
        }).toList();
        SsoAuditListVO response = new SsoAuditListVO();
        response.setRecords(view);
        response.setTenantId(String.valueOf(current.getTenantId()));
        return response;
    }
}
