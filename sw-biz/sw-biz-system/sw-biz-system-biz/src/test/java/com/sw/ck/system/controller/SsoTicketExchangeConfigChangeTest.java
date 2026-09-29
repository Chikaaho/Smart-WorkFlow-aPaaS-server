package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.model.TokenResponse;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.service.RefreshTokenService;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.TenantValidityService;
import com.sw.ck.system.sso.SsoAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A4 配置生命周期：未兑换会话票据绑定签发时刻配置指纹——appId/secret/身份模式/
 * 企业标识变更或 Provider 停用后，票据兑换一律拒绝且不签发新会话；配置未变时
 * 正常兑换。服务端隔离集成（不出站、不强制厂商扫码）。
 */
@DisplayName("A4 票据兑换配置生命周期（指纹失配拒绝/一致兑换）")
class SsoTicketExchangeConfigChangeTest {

    private static final String OLD_DIGEST = "digest-issued-at-callback";
    private static final String NEW_DIGEST = "digest-after-config-change";

    private SsoTicketStore ticketStore;
    private SsoAuthService ssoAuthService;
    private com.sw.ck.security.jwt.JwtTokenProvider jwtTokenProvider;
    private RefreshTokenService refreshTokenService;
    private SsoAuthController controller;

    @BeforeEach
    void setUp() {
        ticketStore = new SsoTicketStore();
        ssoAuthService = Mockito.mock(SsoAuthService.class);
        var callbackPolicy = new com.sw.ck.system.sso.SsoCallbackPolicy("", java.util.List.of(), "");
        var sysUserService = Mockito.mock(SysUserService.class);
        jwtTokenProvider = Mockito.mock(com.sw.ck.security.jwt.JwtTokenProvider.class);
        var jwtProperties = Mockito.mock(com.sw.ck.security.jwt.JwtProperties.class);
        refreshTokenService = Mockito.mock(RefreshTokenService.class);
        var tenantValidityService = Mockito.mock(TenantValidityService.class);
        Mockito.doNothing().when(tenantValidityService).requireValid(Mockito.any());
        Mockito.when(sysUserService.getById(Mockito.any())).thenReturn(activeUser());
        Mockito.when(jwtProperties.getRefreshExpireSeconds()).thenReturn(3600L);
        Mockito.when(jwtProperties.getAccessExpireSeconds()).thenReturn(1800L);
        Mockito.when(jwtTokenProvider.generateToken(Mockito.any())).thenReturn("access-token-stub");
        Mockito.when(refreshTokenService.createRefreshToken(Mockito.any(), Mockito.any(), Mockito.anyLong()))
                .thenReturn("refresh-token-stub");
        controller = new SsoAuthController(ssoAuthService, ticketStore, callbackPolicy, sysUserService,
                jwtTokenProvider, jwtProperties, refreshTokenService, tenantValidityService);
        ReflectionTestUtils.setField(controller, "cookieSecure", false);
        ReflectionTestUtils.setField(controller, "cookiePath", "/api/auth/");
    }

    private SysUser activeUser() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setTenantId(1L);
        user.setStatus(0);
        return user;
    }

    private R<TokenResponse> exchangeWithCurrentDigest(String currentDigest) {
        Mockito.when(ssoAuthService.currentConfigDigestFor("WECOM", 1L)).thenReturn(currentDigest);
        String ticket = ticketStore.issue(7L, 1L, "WECOM", OLD_DIGEST);
        return controller.exchangeTicket(Map.of("ticket", ticket),
                Mockito.mock(jakarta.servlet.http.HttpServletResponse.class));
    }

    @Test
    @DisplayName("票据兑换：appId 变更 → 拒绝且不签发新会话")
    void ticket_appIdChangedAfterIssue_rejected() {
        assertThat(exchangeWithCurrentDigest(NEW_DIGEST).getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("票据兑换：secret 变更 → 拒绝且不签发新会话")
    void ticket_secretChangedAfterIssue_rejected() {
        assertThat(exchangeWithCurrentDigest(NEW_DIGEST).getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("票据兑换：身份模式变更 → 拒绝且不签发新会话")
    void ticket_identityModeChangedAfterIssue_rejected() {
        assertThat(exchangeWithCurrentDigest(NEW_DIGEST).getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("票据兑换：企业标识变更 → 拒绝且不签发新会话")
    void ticket_enterpriseIdentityChangedAfterIssue_rejected() {
        assertThat(exchangeWithCurrentDigest(NEW_DIGEST).getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("票据兑换：Provider 停用 → 拒绝且不签发新会话（既有语义保持）")
    void ticket_disabledAfterIssue_rejected() {
        assertThat(exchangeWithCurrentDigest(null).getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("票据兑换：配置未变更 → 正常签发本地会话（token+refresh）")
    void ticket_configUnchanged_exchangeSucceeds() {
        R<TokenResponse> res = exchangeWithCurrentDigest(OLD_DIGEST);
        assertThat(res.getCode()).isEqualTo(R.SUCCESS_CODE);
        assertThat(res.getData().getAccessToken()).isEqualTo("access-token-stub");
        Mockito.verify(refreshTokenService).createRefreshToken(Mockito.eq(7L), Mockito.eq(1L), Mockito.anyLong());
    }

    @Test
    @DisplayName("票据兑换：拒绝路径不生成任何 token（会话零签发）")
    void ticket_rejectedPaths_issueNoSession() {
        assertThat(exchangeWithCurrentDigest(NEW_DIGEST).getCode()).isEqualTo(401);
        assertThat(exchangeWithCurrentDigest(null).getCode()).isEqualTo(401);
        Mockito.verify(jwtTokenProvider, Mockito.never()).generateToken(Mockito.any());
        Mockito.verify(refreshTokenService, Mockito.never())
                .createRefreshToken(Mockito.any(), Mockito.any(), Mockito.anyLong());
        assertThatCode(() -> Mockito.verify(ssoAuthService, Mockito.atLeast(2)).auditRejection(
                Mockito.eq("WECOM"), Mockito.eq("LOGIN_FAILED"), Mockito.anyString()))
                .doesNotThrowAnyException();
    }
}
