package com.sw.ck.system.sso;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SsoProviderConfig;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.service.TenantValidityService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 授权发起编排：已登录发起（绑定入口）与登录前安全发起（租户解析 + state 签发 +
 * 授权 URL 构造）。自 SsoAuthService 拆出（行为不变）；事务边界仍由门面
 * {@code SsoAuthService} 的 {@code @Transactional} 方法承担。
 */
@Service
class SsoAuthorizeStartService {

    @Autowired
    private SsoProviderConfigService configService;
    @Autowired
    private SsoTenantResolveService tenantResolveService;
    @Autowired
    private SsoAuthStateService stateService;
    @Autowired
    private SsoProviderClients clients;
    @Autowired
    private SsoCallbackPolicy callbackPolicy;
    @Autowired
    private SsoAuditRecorder auditRecorder;
    @Autowired
    private TenantValidityService tenantValidityService;

    /**
     * 服务端授权发起：签发一次性 state 并构造 Provider 授权 URL。
     * state 与租户/Provider/发起会话绑定（当前登录用户可为 null——登录页发起）。
     */
    SsoAuthService.AuthorizeStart startAuthorize(String provider, String redirectPath) {
        SsoAuthService.requireProvider(provider);
        SsoProviderConfig config = configService.loadEnabledConfig(provider);
        if (config == null || config.getEnabled() != 1) {
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        LoginUser current = LoginUserHolder.get();
        Long tenantId = current != null ? current.getTenantId() : null;
        if (tenantId == null) {
            // 登录前发起必须带租户上下文（租户级配置决定 Provider 凭据与绑定域）
            throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
        }
        tenantValidityService.requireValid(tenantId);
        String state = stateService.issueState(provider, tenantId, redirectPath,
                configService.configFingerprint(provider, config));

        SsoProviderClient client = clients.get(provider);
        String authorizeUrl = client.buildAuthorizeUrl(
                configService.decryptConfig(config), callbackPolicy.resolveCallbackUrl(provider), state);
        auditRecorder.audit(provider, "AUTH_START", SysAuditLog.RESULT_SUCCESS, current == null ? null : current.getUserId(),
                null, null, null);
        return new SsoAuthService.AuthorizeStart(authorizeUrl, state);
    }

    /**
     * 登录前安全发起——租户名称入口：登录页免填企业（B021-016）且未提供名称时，
     * 按 Provider 反查唯一可用企业；提供了名称则按名称精确解析（V012-BUG-019 既有语义）。
     */
    SsoAuthService.AuthorizeStart startAuthorizeLogin(String provider, String tenantName, String redirectPath) {
        Long tenantId = tenantName == null || tenantName.isBlank()
                ? tenantResolveService.resolveDefaultTenantIdForProvider(provider)
                : tenantResolveService.resolveTenantIdByNameExact(tenantName);
        return startAuthorizeLogin(provider, tenantId, redirectPath);
    }

    /**
     * 登录前安全发起（I5 复验 G5）：无既有登录态时确定租户并发起授权。
     * <p>
     * 租户来源为显式入参并经服务端权威校验（租户存在/启用/未过期 + 该租户的
     * Provider 配置已启用）；该入参只用于定位租户级 Provider 配置与绑定域，
     * 不授予任何权限——已绑定登录最终由服务端按绑定行的 tenantId 装载
     * LoginUser/角色/数据范围。租户无效或 Provider 未启用均 fail closed。
     * </p>
     */
    SsoAuthService.AuthorizeStart startAuthorizeLogin(String provider, Long tenantId, String redirectPath) {
        SsoAuthService.requireProvider(provider);
        if (tenantId == null) {
            throw new SsoRejectionException(SystemErrorKeys.SSO_TENANT_REQUIRED, SsoAuthService.SSO_TENANT_REQUIRED_MSG);
        }
        // 免认证路径无登录态：租户语义全部由显式谓词承担（state.tenantId、配置行
        // tenant_id、绑定行 tenant_id），挂起拦截器避免 fail-closed 误伤
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            tenantValidityService.requireValid(tenantId);
            SsoProviderConfig config = configService.loadEnabledConfigGlobal(provider, tenantId);
            if (config == null || config.getEnabled() != 1) {
                throw new SsoRejectionException(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SsoAuthService.SSO_LOGIN_FAILED_MSG);
            }
            String state = stateService.issueState(provider, tenantId, redirectPath,
                    configService.configFingerprint(provider, config));

            SsoProviderClient client = clients.get(provider);
            String authorizeUrl = client.buildAuthorizeUrl(
                    configService.decryptConfig(config), callbackPolicy.resolveCallbackUrl(provider), state);
            auditRecorder.audit(provider, "AUTH_START", SysAuditLog.RESULT_SUCCESS, null, null, null, "pre-login tenant=" + tenantId, tenantId);
            return new SsoAuthService.AuthorizeStart(authorizeUrl, state);
        }
    }
}
