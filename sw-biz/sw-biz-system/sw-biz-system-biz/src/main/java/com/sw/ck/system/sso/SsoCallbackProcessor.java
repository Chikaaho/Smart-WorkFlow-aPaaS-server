package com.sw.ck.system.sso;

import com.sw.ck.system.entity.SsoAuthState;
import com.sw.ck.system.entity.SsoProviderConfig;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.service.TenantValidityService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 回调编排：state 校验与原子消费 → 配置指纹校验 → code 换票（token 交换）→
 * 企业归属校验 → B 端准入。自 SsoAuthService 拆出（行为不变）；租户拦截器
 * 挂起与事务边界仍由门面 {@code SsoAuthService#handleCallback} 承担。
 */
@Service
class SsoCallbackProcessor {

    @Autowired
    private SsoAuthStateService stateService;
    @Autowired
    private SsoProviderConfigService configService;
    @Autowired
    private SsoProviderClients clients;
    @Autowired
    private SsoCallbackPolicy callbackPolicy;
    @Autowired
    private TenantValidityService tenantValidityService;
    @Autowired
    private SsoAuditRecorder auditRecorder;
    @Autowired
    private SsoUserBindingService userBindingService;

    /**
     * 回调换票 + 已绑定登录定位。
     * <p>
     * state 校验：存在、未过期、未消费、Provider/租户匹配；原子消费后用一次性
     * code 换外部稳定标识；已绑定 → 返回本地 userId 进入既有登录链；未绑定 →
     * 返回绑定候选（挂起会话由调用方在受控事务内完成绑定）。
     * </p>
     */
    SsoAuthService.CallbackResult doHandleCallback(String provider, String code, String state) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.LOGIN_FAILED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null, "missing code/state");
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        SsoAuthState stateRow = stateService.validateForCallback(provider, state);
        // 回调可能发生在 state 签发后租户被停用/过期的窗口内；在消费 state
        // 与外呼前再次校验，确保失效租户既不能继续登录，也不产生 Provider 出站。
        tenantValidityService.requireValid(stateRow.getTenantId());
        if (!stateService.tryConsume(stateRow)) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.REPLAY_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null, "state concurrent replay", stateRow.getTenantId());
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }

        SsoProviderConfig config = configService.loadEnabledConfigGlobal(provider, stateRow.getTenantId());
        if (config == null || config.getEnabled() != 1) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.LOGIN_FAILED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null, "provider disabled", stateRow.getTenantId());
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        // A4 配置生命周期绑定：在途授权按发起时刻配置指纹校验——appId/secret/
        // 身份模式/企业标识/启停任一变化（或历史行无指纹）一律安全失败并可重新
        // 发起，不串用新旧配置（方向 §三）。
        String configDigest = configService.configFingerprint(provider, config);
        if (stateRow.getConfigDigest() == null || !stateRow.getConfigDigest().equals(configDigest)) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.LOGIN_FAILED.getCode(), SsoAuditResultEnum.DENIED.getCode(), null, null, null,
                    "config changed since authorize start", stateRow.getTenantId());
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        String externalId;
        String vendorEnterpriseId;
        String vendorMobile;
        try {
            SsoProviderClient.ExchangeResult er = clients.get(provider).exchangeExternalId(configService.decryptConfig(config), code,
                    callbackPolicy.resolveCallbackUrl(provider));
            externalId = er.externalId();
            vendorEnterpriseId = er.enterpriseId();
            vendorMobile = er.mobile();
        } catch (SsoProviderClient.SsoProviderException e) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.LOGIN_FAILED.getCode(), SsoAuditResultEnum.FAILED.getCode(), null, null, SsoAuthService.sha256(externalFingerprint(e)),
                    "provider exchange failed", stateRow.getTenantId());
            throw e;
        }
        // G3b 裁决：租户级 Provider 配置（extra_config.enterpriseId）承载允许企业标识；
        // 厂商可信企业字段与配置不一致 → 拒绝（企业成员模式错配）。配置缺省=个人模式（显式审计）。
        String configuredEnterprise = configService.configuredEnterpriseId(config);
        String scopeMarker;
        if (configuredEnterprise != null && !configuredEnterprise.isBlank()) {
            if (vendorEnterpriseId == null || vendorEnterpriseId.isBlank()
                    || !configuredEnterprise.equals(vendorEnterpriseId)) {
                auditRecorder.auditDenial(provider, SsoAuditActionEnum.LOGIN_FAILED.getCode(), SsoAuditResultEnum.ENTERPRISE_MISMATCH.getCode(), null, null, null,
                        "enterprise mismatch (vendor field vs configured)", stateRow.getTenantId());
                throw new SsoRejectionException(SystemErrorKeys.SSO_BINDING_CONFLICT, SsoAuthService.SSO_LOGIN_FAILED_MSG);
            }
            scopeMarker = "enterprise";
        } else {
            scopeMarker = "personal";
        }
        auditRecorder.audit(provider, "EXCHANGE", SysAuditLog.RESULT_SUCCESS, null, null, SsoAuthService.sha256(externalId),
                "scope=" + scopeMarker, stateRow.getTenantId());
        return userBindingService.admitCallback(provider, stateRow, externalId, vendorMobile, configDigest);
    }

    private String externalFingerprint(Exception e) {
        return e.getClass().getSimpleName() + ":" + e.getMessage();
    }
}
