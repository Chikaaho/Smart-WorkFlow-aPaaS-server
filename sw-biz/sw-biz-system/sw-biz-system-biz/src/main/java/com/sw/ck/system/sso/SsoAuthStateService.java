package com.sw.ck.system.sso;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.system.entity.SsoAuthState;
import com.sw.ck.system.mapper.SsoAuthStateMapper;
import com.sw.ck.system.security.SystemErrorKeys;

import jakarta.annotation.PostConstruct;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一次性授权 state 的签发、回调校验（防重放）与原子消费。自 SsoAuthService
 * 拆出（行为不变）。
 */
@Service
class SsoAuthStateService {

    /** state 有效期（秒）：一次性、限时 */
    static final long STATE_TTL_SECONDS = 300;

    @Autowired
    private SsoAuthStateMapper stateMapper;
    @Autowired
    private SsoAuditRecorder auditRecorder;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate consumeTxTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void initConsumeTxTemplate() {
        if (transactionManager != null) {
            TransactionTemplate tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.consumeTxTemplate = tpl;
        }
    }

    /**
     * 签发一次性 state：生成随机 token、摘要落库（明文不落 SQL/日志，I5 复验
     * G7b），返回明文 state 供授权 URL 与回跳携带。
     */
    String issueState(String provider, Long tenantId, String redirectPath, String configDigest) {
        String state = randomToken(32);
        SsoAuthState stateRow = new SsoAuthState();
        stateRow.setStateValue(SsoAuthService.sha256(state));
        stateRow.setProvider(provider);
        stateRow.setRedirectPath(sanitizeRedirect(redirectPath));
        stateRow.setConsumed(0);
        stateRow.setExpireAt(LocalDateTime.now().plusSeconds(STATE_TTL_SECONDS));
        stateRow.setTenantId(tenantId);
        stateRow.setConfigDigest(configDigest);
        stateMapper.insert(stateRow);
        return state;
    }

    /**
     * 回调侧 state 校验：存在、未过期、未消费、Provider 匹配；不满足一律拒绝
     * 并写拒绝审计后抛 {@link SsoRejectionException}。
     */
    SsoAuthState validateForCallback(String provider, String state) {
        String stateDigest = SsoAuthService.sha256(state);
        SsoAuthState stateRow = stateMapper.selectGlobalByState(stateDigest);
        if (stateRow == null) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.REPLAY_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null, "state not found");
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        if (stateRow.getConsumed() != null && stateRow.getConsumed() == 1) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.REPLAY_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null, "state replayed", stateRow.getTenantId());
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        if (stateRow.getExpireAt().isBefore(LocalDateTime.now())) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.LOGIN_FAILED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null, "state expired", stateRow.getTenantId());
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        if (!provider.equals(stateRow.getProvider())) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.LOGIN_FAILED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null, "provider mismatch", stateRow.getTenantId());
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        return stateRow;
    }

    /**
     * 原子消费（I5 复验 G5b）：必须在外呼之前、且以独立事务提交——
     * 否则换票失败回滚会把 state 还原为未消费，重放会再次打穿到 Provider。
     * 返回是否由本次调用完成消费（并发重放返回 false）。
     */
    boolean tryConsume(SsoAuthState stateRow) {
        final Long stateId = stateRow.getId();
        Integer consumed = consumeTxTemplate.execute(status -> stateMapper.update(null,
                Wrappers.<SsoAuthState>lambdaUpdate()
                        .eq(SsoAuthState::getId, stateId)
                        .eq(SsoAuthState::getConsumed, 0)
                        .set(SsoAuthState::getConsumed, 1)));
        return consumed != null && consumed == 1;
    }

    private String sanitizeRedirect(String redirectPath) {
        if (redirectPath == null || redirectPath.isBlank()) {
            return "/workspace";
        }
        // 仅站内相对路径；拒绝协议相对与外域（open-redirect 防护）
        if (!redirectPath.startsWith("/") || redirectPath.startsWith("//")) {
            return "/workspace";
        }
        return redirectPath;
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
