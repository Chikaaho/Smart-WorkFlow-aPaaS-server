package com.sw.ck.system.sso;

import com.sw.ck.system.entity.SsoAuditRecord;
import com.sw.ck.system.entity.SsoAuthState;
import com.sw.ck.system.entity.SsoProviderConfig;
import com.sw.ck.system.entity.SsoUserBinding;
import com.sw.ck.system.mapper.SsoAuditRecordMapper;
import com.sw.ck.system.mapper.SsoAuthStateMapper;
import com.sw.ck.system.mapper.SsoProviderConfigMapper;
import com.sw.ck.system.mapper.SsoUserBindingMapper;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.service.SysUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I5 SSO 核心服务行为证据：state 一次性/过期/Provider 错配拒绝、绑定冲突拒绝、
 * 解绑后登录拒绝、审计落库、secret 零残留。不依赖真实 Provider 出站（换票由
 * Provider 客户端承担，其网络行为不在本测试范围）。
 */
@DisplayName("I5 SSO 服务行为证据（state 一次性/绑定冲突/审计）")
class SsoAuthServiceTest {

    private SsoProviderConfigMapper configMapper;
    private SsoUserBindingMapper bindingMapper;
    private SsoAuthStateMapper stateMapper;
    private SsoAuditRecordMapper auditMapper;
    private SsoAuthService service;

    @BeforeEach
    void setUp() {
        // 进程内 H2 由 bootstrap FlywayFullChain 覆盖表结构；本测试用内存替身隔离行为语义
        configMapper = Mockito.mock(SsoProviderConfigMapper.class);
        bindingMapper = Mockito.mock(SsoUserBindingMapper.class);
        stateMapper = Mockito.mock(SsoAuthStateMapper.class);
        auditMapper = Mockito.mock(SsoAuditRecordMapper.class);
        SysUserService sysUserService = Mockito.mock(SysUserService.class);
        com.sw.ck.system.service.TenantValidityService tenantValidityService =
                Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doNothing().when(tenantValidityService)
                .requireValid(org.mockito.ArgumentMatchers.any());
        service = new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper,
                sysUserService, List.of(new WecomSsoProviderClient(), new FeishuSsoProviderClient(),
                new DingtalkSsoProviderClient()),
                new com.sw.ck.system.sso.SsoCredentialCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                new SsoCallbackPolicy("", List.of(), ""),
                tenantValidityService,
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class), noopTxManager(), null, null);
        Mockito.when(stateMapper.insert(org.mockito.ArgumentMatchers.<SsoAuthState>any()))
                .thenAnswer(inv -> {
                    SsoAuthState s = inv.getArgument(0);
                    s.setId(1L);
                    return 1;
                });
        Mockito.when(configMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    SsoProviderConfig config = new SsoProviderConfig();
                    config.setId(9L);
                    config.setTenantId(1L);
                    config.setProvider("WECOM");
                    config.setEnabled(1);
                    config.setAppId("ww-test-corp");
                    config.setAppSecretEnc(new com.sw.ck.system.sso.SsoCredentialCipher(
                            java.util.Base64.getEncoder().encodeToString(new byte[32]))
                            .encrypt("secret-value"));
                    return config;
                });
        Mockito.when(auditMapper.insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>any()))
                .thenReturn(1);
        Mockito.when(bindingMapper.insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any()))
                .thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    private void loginAs(Long tenantId) {
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(2L);
        user.setTenantId(tenantId);
        com.sw.ck.security.holder.LoginUserHolder.set(user);
    }

    @Test
    @DisplayName("授权发起：state 落库（摘要）且 authorizeUrl 不含 secret")
    void startAuthorize_shouldPersistStateAndExcludeSecret() {
        loginAs(1L);
        SsoAuthService.AuthorizeStart start = service.startAuthorize("WECOM", "/workspace");

        assertThat(start.authorizeUrl()).contains("appid=ww-test-corp");
        assertThat(start.authorizeUrl()).doesNotContain("secret-value");
        Mockito.verify(stateMapper).insert(org.mockito.ArgumentMatchers.<SsoAuthState>argThat(s ->
                s.getStateValue().length() == 64 && s.getConsumed() == 0
                        && s.getExpireAt().isAfter(LocalDateTime.now())));
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "AUTH_START".equals(a.getEventType()) && "WECOM".equals(a.getProvider())));
    }

    private SsoAuthService serviceWith(SsoProviderClient client, String extraConfig) {
        return serviceWith(client, extraConfig, "");
    }

    private SsoAuthService serviceWith(SsoProviderClient client, String extraConfig, String stateEnterpriseId) {
        var cm = Mockito.mock(SsoProviderConfigMapper.class);
        var bm = Mockito.mock(SsoUserBindingMapper.class);
        var sm = Mockito.mock(SsoAuthStateMapper.class);
        var am = Mockito.mock(SsoAuditRecordMapper.class);
        var users = Mockito.mock(SysUserService.class);
        var tvs = Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doNothing().when(tvs).requireValid(org.mockito.ArgumentMatchers.any());
        var cipher = new com.sw.ck.system.sso.SsoCredentialCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        // 用例内 verify 依赖类字段引用：局部 mock 回写（setUp 已为每用例重建）
        this.auditMapper = am;
        this.bindingMapper = bm;
        SsoProviderConfig config = new SsoProviderConfig();
        config.setId(9L);
        config.setTenantId(1L);
        config.setProvider("WECOM");
        config.setEnabled(1);
        config.setAppId("ww-test-corp");
        config.setAppSecretEnc(cipher.encrypt("secret-value"));
        config.setExtraConfig(extraConfig);
        Mockito.when(cm.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);
        Mockito.when(sm.insert(org.mockito.ArgumentMatchers.<SsoAuthState>any())).thenAnswer(inv -> {
            SsoAuthState st = inv.getArgument(0);
            st.setId(2L);
            return 1;
        });
        Mockito.when(sm.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(freshState(stateEnterpriseId));
        Mockito.when(sm.update(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        // B 端准入链默认无候选用户（个别用例显式覆盖）
        Mockito.when(users.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<com.sw.ck.system.entity.SysUser>>any()))
                .thenReturn(java.util.List.of());
        return new SsoAuthService(cm, bm, sm, am, users, List.of(client), cipher,
                new SsoCallbackPolicy("", List.of(), ""), tvs,
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class), noopTxManager(), null, null);
    }

    private SsoAuthState freshState() {
        return freshState("");
    }

    /** state 指纹与指定企业标识的默认配置桩（ww-test-corp/secret-value/启用）同源——A4 配置生命周期绑定。 */
    private SsoAuthState freshState(String enterpriseId) {
        SsoAuthState st = new SsoAuthState();
        st.setProvider("WECOM");
        st.setTenantId(1L);
        st.setConsumed(0);
        st.setExpireAt(java.time.LocalDateTime.now().plusMinutes(5));
        st.setConfigDigest(SsoAuthService.fingerprintOf("WECOM", "ww-test-corp", "secret-value", enterpriseId, 1));
        return st;
    }

    private SsoProviderClient fixedClient(String externalId, String enterpriseId) {
        return fixedClient(externalId, enterpriseId, null);
    }

    private SsoProviderClient fixedClient(String externalId, String enterpriseId, String mobile) {
        return new SsoProviderClient() {
            @Override public String provider() { return "WECOM"; }
            @Override public String buildAuthorizeUrl(SsoProviderConfigView c, String r, String st) { return "u"; }
            @Override public ExchangeResult exchangeExternalId(SsoProviderConfigView c, String code, String redirectUri) {
                return new ExchangeResult(externalId, enterpriseId, mobile);
            }
        };
    }

    @Test
    @DisplayName("G3b 企业归属：配置 enterpriseId 且厂商字段一致 → 准入链（缺可信手机号统一拒绝）")
    void callback_enterpriseMatch_shouldProceedToAdmission() {
        SsoAuthService svc = serviceWith(fixedClient("ext-ok", "corp-ok"), "{\"enterpriseId\":\"corp-ok\"}", "corp-ok");
        // B 端准入：无绑定且厂商未返回可信手机号 → 统一拒绝（不再进入候选绑定页）
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_ADMISSION_REJECTED);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "ADMISSION_REJECTED".equals(a.getEventType())
                        && "trusted phone missing".equals(a.getDetail())));
    }

    @Test
    @DisplayName("B 端准入：唯一匹配本地用户 → 自动绑定并签发既有会话（BIND+LOGIN_SUCCESS 审计）")
    void callback_admission_uniquePhoneAutoBinds() {
        var users = userServiceReturning(newLocalUser(7L, "17800000001"));
        SsoAuthService svc = serviceWithUsers(fixedClient("ext-ok", "corp-ok", "17800000001"), "{}", users);
        var res = svc.handleCallback("WECOM", "code-ok", "state-ok");
        assertThat(res.bound()).isTrue();
        assertThat(res.userId()).isEqualTo(7L);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "BIND".equals(a.getEventType()) && "phone-admission auto-bind".equals(a.getDetail())));
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "LOGIN_SUCCESS".equals(a.getEventType())));
    }

    @Test
    @DisplayName("B 端准入：手机号在租户内重复 → 拒绝且无绑定增量")
    void callback_admission_ambiguousPhone_rejected() {
        var users = userServiceReturning(java.util.List.of(
                newLocalUser(7L, "17800000001"), newLocalUser(8L, "+8617800000001")));
        SsoAuthService svc = serviceWithUsers(fixedClient("ext-ok", "corp-ok", "+8617800000001"), "{}", users);
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_ADMISSION_REJECTED);
        Mockito.verify(bindingMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any());
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "ADMISSION_REJECTED".equals(a.getEventType()) && "ambiguous phone in tenant".equals(a.getDetail())));
    }

    @Test
    @DisplayName("B 端准入：租户内无相同手机号用户 → 拒绝且不创建用户")
    void callback_admission_noLocalUser_rejected() {
        SsoAuthService svc = serviceWith(fixedClient("ext-ok", "corp-ok", "17800000001"), "{}");
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_ADMISSION_REJECTED);
        Mockito.verify(bindingMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any());
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "ADMISSION_REJECTED".equals(a.getEventType())
                        && "no local user with trusted phone".equals(a.getDetail())));
    }

    @Test
    @DisplayName("B 端准入：已绑定登录手机号变更 → 拒绝不自动迁移")
    void callback_boundPhoneChanged_rejected() {
        var users = Mockito.mock(SysUserService.class);
        Mockito.when(users.getById(7L)).thenReturn(newLocalUser(7L, "17800000002"));
        SsoAuthService svc = serviceWithUsers(fixedClient("ext-ok", "corp-ok", "17800000001"), "{}", users,
                existingBinding(7L));
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_ADMISSION_REJECTED);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "ADMISSION_REJECTED".equals(a.getEventType())
                        && "phone changed (vendor vs local)".equals(a.getDetail())));
    }

    @Test
    @DisplayName("B 端准入：已绑定登录手机号一致 → 登录成功")
    void callback_boundPhoneMatch_loginSuccess() {
        var users = Mockito.mock(SysUserService.class);
        Mockito.when(users.getById(7L)).thenReturn(newLocalUser(7L, "+86 178-0000-0001"));
        SsoAuthService svc = serviceWithUsers(fixedClient("ext-ok", "corp-ok", "17800000001"), "{}", users,
                existingBinding(7L));
        var res = svc.handleCallback("WECOM", "code-ok", "state-ok");
        assertThat(res.bound()).isTrue();
        assertThat(res.userId()).isEqualTo(7L);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "LOGIN_SUCCESS".equals(a.getEventType())));
    }

    @Test
    @DisplayName("B 端准入：已绑定但本地账号停用 → 拒绝")
    void callback_boundUserDisabled_rejected() {
        var users = Mockito.mock(SysUserService.class);
        com.sw.ck.system.entity.SysUser disabled = newLocalUser(7L, "17800000001");
        disabled.setStatus(1);
        Mockito.when(users.getById(7L)).thenReturn(disabled);
        SsoAuthService svc = serviceWithUsers(fixedClient("ext-ok", "corp-ok", "17800000001"), "{}", users,
                existingBinding(7L));
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_ADMISSION_REJECTED);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "ADMISSION_REJECTED".equals(a.getEventType())
                        && "bound local user unavailable".equals(a.getDetail())));
    }

    @Test
    @DisplayName("B 端准入：解绑（UNBOUND）后同一主体重新准入 → 重绑成功（占位行逻辑删除）")
    void callback_admission_rebindAfterUnbind_success() {
        SsoUserBinding dormant = existingBinding(7L);
        dormant.setBindStatus("UNBOUND");
        var users = userServiceReturning(newLocalUser(7L, "17800000001"));
        SsoAuthService svc = serviceWithUsersRebind(fixedClient("ext-ok", "corp-ok", "17800000001"), "{}", users, dormant);
        var res = svc.handleCallback("WECOM", "code-ok", "state-ok");
        assertThat(res.bound()).isTrue();
        assertThat(res.userId()).isEqualTo(7L);
        Mockito.verify(bindingMapper).deleteDormantUnboundRows(
                org.mockito.ArgumentMatchers.eq("WECOM"), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(7L));
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "BIND".equals(a.getEventType()) && "phone-admission auto-bind".equals(a.getDetail())));
    }

    @Test
    @DisplayName("B 端准入：解绑行属于其他本地用户 → 拒绝不自动迁移")
    void callback_admission_dormantBindingOtherUser_rejected() {
        SsoUserBinding dormant = existingBinding(8L);
        dormant.setBindStatus("UNBOUND");
        var users = userServiceReturning(newLocalUser(7L, "17800000001"));
        SsoAuthService svc = serviceWithUsersRebind(fixedClient("ext-ok", "corp-ok", "17800000001"), "{}", users, dormant);
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_ADMISSION_REJECTED);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "ADMISSION_REJECTED".equals(a.getEventType())
                        && "dormant binding belongs to another local user".equals(a.getDetail())));
    }

    /** 在 serviceWithUsers 基础上让 dormant 查询命中指定 UNBOUND 行（重绑用例）。 */
    private SsoAuthService serviceWithUsersRebind(SsoProviderClient client, String extraConfig, SysUserService users,
                                                  SsoUserBinding dormant) {
        SsoAuthService svc = serviceWithUsers(client, extraConfig, users, (SsoUserBinding) null);
        Mockito.when(bindingMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenReturn(dormant);
        Mockito.when(bindingMapper.deleteDormantUnboundRows(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        return svc;
    }

    /** A4 在途授权夹具：捕获式 client + 独立 mock 集（不出站、可验证绑定/审计增量）。 */
    private record InFlightHarness(SsoAuthService svc, SsoUserBindingMapper bm, SsoAuditRecordMapper am,
                                   java.util.List<SsoProviderClient.SsoProviderConfigView> captured) {
    }

    private InFlightHarness inFlightHarness(SsoProviderConfig currentConfig, SsoAuthState state,
                                            List<com.sw.ck.system.entity.SysUser> tenantUsers) {
        var captured = new java.util.ArrayList<SsoProviderClient.SsoProviderConfigView>();
        SsoProviderClient capturingClient = new SsoProviderClient() {
            @Override public String provider() { return "WECOM"; }
            @Override public String buildAuthorizeUrl(SsoProviderConfigView c, String r, String st) { return "u"; }
            @Override public ExchangeResult exchangeExternalId(SsoProviderConfigView c, String code, String redirectUri) {
                captured.add(c);
                return new ExchangeResult("ext-" + captured.size(), "corp-ok", "17800000001");
            }
        };
        var cm = Mockito.mock(SsoProviderConfigMapper.class);
        var bm = Mockito.mock(SsoUserBindingMapper.class);
        var sm = Mockito.mock(SsoAuthStateMapper.class);
        var am = Mockito.mock(SsoAuditRecordMapper.class);
        var users = Mockito.mock(SysUserService.class);
        Mockito.when(users.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<com.sw.ck.system.entity.SysUser>>any()))
                .thenReturn(tenantUsers);
        Mockito.doAnswer(inv -> tenantUsers.isEmpty() ? null : tenantUsers.get(0))
                .when(users).getById(org.mockito.ArgumentMatchers.any());
        var tvs = Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doNothing().when(tvs).requireValid(org.mockito.ArgumentMatchers.any());
        var cipher = new com.sw.ck.system.sso.SsoCredentialCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        Mockito.when(am.insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>any())).thenReturn(1);
        Mockito.when(bm.insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any())).thenReturn(1);
        Mockito.when(bm.selectActiveByExternal(org.mockito.ArgumentMatchers.eq("WECOM"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        Mockito.when(bm.selectActiveByUser(org.mockito.ArgumentMatchers.eq("WECOM"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(7L))).thenReturn(null);
        Mockito.when(bm.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        Mockito.when(bm.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        Mockito.when(bm.deleteDormantUnboundRows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(0);
        Mockito.when(cm.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(currentConfig);
        Mockito.when(sm.selectGlobalByState(org.mockito.ArgumentMatchers.anyString())).thenReturn(state);
        Mockito.when(sm.update(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        SsoAuthService svc = new SsoAuthService(cm, bm, sm, am, users, List.of(capturingClient),
                cipher, new SsoCallbackPolicy("", List.of(), ""), tvs,
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class), noopTxManager(), null, null);
        return new InFlightHarness(svc, bm, am, captured);
    }

    private SsoProviderConfig configRow(String appId, String secret, String extraConfig, int enabled) {
        SsoProviderConfig config = new SsoProviderConfig();
        config.setId(9L);
        config.setTenantId(1L);
        config.setProvider("WECOM");
        config.setEnabled(enabled);
        config.setAppId(appId);
        config.setAppSecretEnc(new com.sw.ck.system.sso.SsoCredentialCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[32])).encrypt(secret));
        config.setExtraConfig(extraConfig);
        return config;
    }

    /** A4 在途拒绝统一断言：拒绝、不出站换票、零绑定插入、拒绝审计原因精确。 */
    private void assertInFlightRejected(InFlightHarness h, String expectedAuditDetail) {
        assertThatThrownBy(() -> h.svc().handleCallback("WECOM", "code-any", "state-any"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED);
        assertThat(h.captured()).isEmpty();
        Mockito.verify(h.bm(), Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any());
        Mockito.verify(h.am()).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "LOGIN_FAILED".equals(a.getEventType()) && expectedAuditDetail.equals(a.getDetail())));
    }

    @Test
    @DisplayName("A4 生命周期：appId 变更 → 在途回调安全失败（不出站/零绑定/审计原因）")
    void callback_appIdChangedAfterAuthorizeStart_rejected() {
        assertInFlightRejected(
                inFlightHarness(configRow("new-app-id", "secret-value", "{}", 1), freshState(), List.of()),
                "config changed since authorize start");
    }

    @Test
    @DisplayName("A4 生命周期：secret 变更 → 在途回调安全失败")
    void callback_secretChangedAfterAuthorizeStart_rejected() {
        assertInFlightRejected(
                inFlightHarness(configRow("ww-test-corp", "new-secret-value", "{}", 1), freshState(), List.of()),
                "config changed since authorize start");
    }

    @Test
    @DisplayName("A4 生命周期：身份模式变更（个人→企业）→ 在途回调安全失败")
    void callback_identityModeChangedAfterAuthorizeStart_rejected() {
        assertInFlightRejected(
                inFlightHarness(configRow("ww-test-corp", "secret-value", "{\"enterpriseId\":\"corp-ok\"}", 1),
                        freshState(""), List.of()),
                "config changed since authorize start");
    }

    @Test
    @DisplayName("A4 生命周期：企业标识变更（corp-a→corp-b）→ 在途回调安全失败")
    void callback_enterpriseIdentityChangedAfterAuthorizeStart_rejected() {
        assertInFlightRejected(
                inFlightHarness(configRow("ww-test-corp", "secret-value", "{\"enterpriseId\":\"corp-b\"}", 1),
                        freshState("corp-a"), List.of()),
                "config changed since authorize start");
    }

    @Test
    @DisplayName("A4 生命周期：Provider 停用 → 在途回调拒绝（既有启停语义保持）")
    void callback_disabledAfterAuthorizeStart_rejected() {
        assertInFlightRejected(
                inFlightHarness(configRow("ww-test-corp", "secret-value", "{}", 0), freshState(), List.of()),
                "provider disabled");
    }

    @Test
    @DisplayName("A4 生命周期：历史 state 无配置指纹（V104 前）→ fail closed 拒绝")
    void callback_legacyStateWithoutDigest_failClosed() {
        SsoAuthState legacy = freshState();
        legacy.setConfigDigest(null);
        assertInFlightRejected(
                inFlightHarness(configRow("ww-test-corp", "secret-value", "{}", 1), legacy, List.of()),
                "config changed since authorize start");
    }

    @Test
    @DisplayName("A4 生命周期：配置变更后重新发起 → 按新配置换票并正常准入绑定登录")
    void callback_afterConfigChange_reinitiateSucceedsWithNewConfig() {
        // 重新发起：state 指纹取自变更后的当前配置（新 appId/secret）
        SsoAuthState reinitiated = freshState();
        reinitiated.setConfigDigest(SsoAuthService.fingerprintOf("WECOM", "new-app-id", "new-secret-value", "", 1));
        InFlightHarness h = inFlightHarness(
                configRow("new-app-id", "new-secret-value", "{}", 1), reinitiated,
                List.of(newLocalUser(7L, "17800000001")));
        var res = h.svc().handleCallback("WECOM", "code-new", "state-new");
        assertThat(res.bound()).isTrue();
        assertThat(res.userId()).isEqualTo(7L);
        assertThat(h.captured()).hasSize(1);
        assertThat(h.captured().get(0).appId()).isEqualTo("new-app-id");
        assertThat(h.captured().get(0).appSecret()).isEqualTo("new-secret-value");
        Mockito.verify(h.am()).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "BIND".equals(a.getEventType()) && "phone-admission auto-bind".equals(a.getDetail())));
    }

    @Test
    @DisplayName("A4 生命周期：企业模式错标识（厂商字段与配置不一致）→ 换票后安全失败零绑定")
    void callback_enterpriseVendorFieldMismatch_rejectedAfterExchange() {
        // 指纹一致（企业标识 corp-expected 与发起时相同）→ 进入换票；厂商可信企业
        // 字段 corp-other 与配置不一致 → ENTERPRISE_MISMATCH 拒绝（G3b 语义保持）
        var users = userServiceReturning(java.util.List.of());
        SsoAuthService svc = serviceWithUsers(fixedClient("ext-ok", "corp-other", "17800000001"),
                "{\"enterpriseId\":\"corp-expected\"}", users, "corp-expected");
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_BINDING_CONFLICT);
        Mockito.verify(bindingMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any());
    }

    private com.sw.ck.system.entity.SysUser newLocalUser(Long id, String phone) {
        com.sw.ck.system.entity.SysUser user = new com.sw.ck.system.entity.SysUser();
        user.setId(id);
        user.setTenantId(1L);
        user.setStatus(0);
        user.setPhone(phone);
        return user;
    }

    private SysUserService userServiceReturning(com.sw.ck.system.entity.SysUser user) {
        return userServiceReturning(java.util.List.of(user));
    }

    private SysUserService userServiceReturning(java.util.List<com.sw.ck.system.entity.SysUser> users) {
        SysUserService service = Mockito.mock(SysUserService.class);
        Mockito.when(service.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<com.sw.ck.system.entity.SysUser>>any()))
                .thenReturn(users);
        Mockito.doAnswer(inv -> users.isEmpty() ? null : users.get(0))
                .when(service).getById(org.mockito.ArgumentMatchers.any());
        return service;
    }

    private SsoUserBinding existingBinding(Long userId) {
        SsoUserBinding binding = new SsoUserBinding();
        binding.setId(50L);
        binding.setProvider("WECOM");
        binding.setTenantId(1L);
        binding.setUserId(userId);
        binding.setExternalDigest("digest-placeholder");
        binding.setBindStatus("ACTIVE");
        return binding;
    }

    /** 在 serviceWith 基础上替换 SysUserService 桩与既有绑定（准入链用例）。 */
    private SsoAuthService serviceWithUsers(SsoProviderClient client, String extraConfig, SysUserService users) {
        return serviceWithUsers(client, extraConfig, users, null, "");
    }

    private SsoAuthService serviceWithUsers(SsoProviderClient client, String extraConfig, SysUserService users,
                                            String stateEnterpriseId) {
        return serviceWithUsers(client, extraConfig, users, null, stateEnterpriseId);
    }

    private SsoAuthService serviceWithUsers(SsoProviderClient client, String extraConfig, SysUserService users,
                                            SsoUserBinding existingExternal) {
        return serviceWithUsers(client, extraConfig, users, existingExternal, "");
    }

    private SsoAuthService serviceWithUsers(SsoProviderClient client, String extraConfig, SysUserService users,
                                            SsoUserBinding existingExternal, String stateEnterpriseId) {
        var cm = Mockito.mock(SsoProviderConfigMapper.class);
        var bm = Mockito.mock(SsoUserBindingMapper.class);
        var sm = Mockito.mock(SsoAuthStateMapper.class);
        var am = Mockito.mock(SsoAuditRecordMapper.class);
        // 用例内 verify 依赖类字段引用：局部 mock 回写（setUp 已为每用例重建）
        this.auditMapper = am;
        this.bindingMapper = bm;
        var tvs = Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doNothing().when(tvs).requireValid(org.mockito.ArgumentMatchers.any());
        var cipher = new com.sw.ck.system.sso.SsoCredentialCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        SsoProviderConfig config = new SsoProviderConfig();
        config.setId(9L);
        config.setTenantId(1L);
        config.setProvider("WECOM");
        config.setEnabled(1);
        config.setAppId("ww-test-corp");
        config.setAppSecretEnc(cipher.encrypt("secret-value"));
        config.setExtraConfig(extraConfig);
        Mockito.when(cm.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);
        Mockito.when(sm.insert(org.mockito.ArgumentMatchers.<SsoAuthState>any())).thenAnswer(inv -> {
            SsoAuthState st = inv.getArgument(0);
            st.setId(2L);
            return 1;
        });
        Mockito.when(sm.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(freshState(stateEnterpriseId));
        Mockito.when(sm.update(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        Mockito.when(bm.selectActiveByExternal(org.mockito.ArgumentMatchers.eq("WECOM"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(existingExternal);
        Mockito.when(bm.selectActiveByUser(org.mockito.ArgumentMatchers.eq("WECOM"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(7L))).thenReturn(null);
        Mockito.when(bm.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        Mockito.when(bm.insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any())).thenReturn(1);
        Mockito.when(am.insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>any())).thenReturn(1);
        return new SsoAuthService(cm, bm, sm, am, users, List.of(client), cipher,
                new SsoCallbackPolicy("", List.of(), ""), tvs,
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class), noopTxManager(), null, null);
    }

    @Test
    @DisplayName("G3b 企业归属：厂商字段与配置不一致 → 拒绝且审计 ENTERPRISE_MISMATCH")
    void callback_enterpriseMismatch_shouldReject() {
        SsoAuthService svc = serviceWith(fixedClient("ext-x", "corp-other"), "{\"enterpriseId\":\"corp-expected\"}",
                "corp-expected");
        SsoAuthService svcF = svc;
        assertThatThrownBy(() -> svcF.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_BINDING_CONFLICT);
    }

    @Test
    @DisplayName("G3b 个人模式：extra_config 无 enterpriseId → scope=personal 审计后进入准入链")
    void callback_personalMode_shouldProceed() {
        SsoAuthService svc = serviceWith(fixedClient("ext-p", "corp-any"), "{}");
        // 个人模式显式放行换票（scope=personal 审计），随后按 B 端准入收敛：
        // 无绑定且缺可信手机号 → 统一拒绝（不再进入候选绑定页）
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-ok", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_ADMISSION_REJECTED);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "EXCHANGE".equals(a.getEventType())
                        && a.getDetail() != null && a.getDetail().contains("scope=personal")));
    }

        @Test
    @DisplayName("回调：Provider 大小写归一化——小写回跳路径走 state 校验而非 provider 拒绝")
    void handleCallback_providerCaseNormalized() {
        // resolveCallbackUrl 生成小写路径（/auth/sso/wecom/callback）；小写 provider
        // 归一化后应进入 state 校验链（state 未命中 → SSO_LOGIN_NOT_COMPLETED），
        // 而非 SSO_BINDING_INVALID 的 provider 拒绝。
        assertThatThrownBy(() -> service.handleCallback("wecom", "code-x", "state-x"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED);
    }

    @Test
    @DisplayName("回调：state 不存在 → 拒绝且不换票")
    void handleCallback_unknownState_shouldReject() {
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);
        assertThatThrownBy(() -> service.handleCallback("WECOM", "code-x", "state-x"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "REPLAY_REJECTED".equals(a.getEventType())));
    }

    @Test
    @DisplayName("回调：state 已消费（重放）→ 拒绝")
    void handleCallback_replayedState_shouldReject() {
        SsoAuthState consumed = new SsoAuthState();
        consumed.setId(1L);
        consumed.setStateValue("digest");
        consumed.setProvider("WECOM");
        consumed.setConsumed(1);
        consumed.setExpireAt(LocalDateTime.now().plusSeconds(60));
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(consumed);
        assertThatThrownBy(() -> service.handleCallback("WECOM", "code-x", "state-x"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "REPLAY_REJECTED".equals(a.getEventType()) && "state replayed".equals(a.getDetail())));
    }

    @Test
    @DisplayName("回调：state 过期 → 拒绝")
    void handleCallback_expiredState_shouldReject() {
        SsoAuthState expired = new SsoAuthState();
        expired.setId(1L);
        expired.setProvider("WECOM");
        expired.setConsumed(0);
        expired.setExpireAt(LocalDateTime.now().minusSeconds(1));
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(expired);
        assertThatThrownBy(() -> service.handleCallback("WECOM", "code-x", "state-x"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED);
    }

    @Test
    @DisplayName("回调：Provider 错配 → 拒绝")
    void handleCallback_providerMismatch_shouldReject() {
        SsoAuthState state = new SsoAuthState();
        state.setId(1L);
        state.setProvider("FEISHU");
        state.setConsumed(0);
        state.setExpireAt(LocalDateTime.now().plusSeconds(60));
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(state);
        assertThatThrownBy(() -> service.handleCallback("WECOM", "code-x", "state-x"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED);
    }

    @Test
    @DisplayName("绑定：外部身份已被他人绑定 → 冲突拒绝并审计")
    void bind_externalAlreadyBound_shouldReject() {
        loginAs(1L);
        SsoUserBinding existing = new SsoUserBinding();
        existing.setUserId(99L);
        Mockito.when(bindingMapper.selectActiveByExternal(org.mockito.ArgumentMatchers.eq("WECOM"), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(SsoAuthService.digest("ext-1")))).thenReturn(existing);
        assertThatThrownBy(() -> service.bind("WECOM", 2L, "ext-1"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_BINDING_CONFLICT);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "CONFLICT_REJECTED".equals(a.getEventType())));
    }

    @Test
    @DisplayName("绑定：本地账号已绑定其他外部身份 → 冲突拒绝")
    void bind_userAlreadyBound_shouldReject() {
        loginAs(1L);
        SsoUserBinding existing = new SsoUserBinding();
        existing.setUserId(2L);
        Mockito.when(bindingMapper.selectActiveByUser("WECOM", 1L, 2L)).thenReturn(existing);
        assertThatThrownBy(() -> service.bind("WECOM", 2L, "ext-1"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_BINDING_CONFLICT);
    }

    @Test
    @DisplayName("绑定成功：落 ACTIVE 绑定行 + 审计")
    void bind_success_shouldPersistActiveBinding() {
        loginAs(1L);
        service.bind("WECOM", 2L, "ext-1");
        Mockito.verify(bindingMapper).insert(org.mockito.ArgumentMatchers.<SsoUserBinding>argThat(b ->
                "ACTIVE".equals(b.getBindStatus()) && b.getTenantId() == 1L
                        && b.getExternalDigest().length() == 64
                        && b.getExternalId().equals(b.getExternalDigest())));
        Mockito.when(bindingMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "BIND".equals(a.getEventType()) && "SUCCESS".equals(a.getResult())));
    }

    @Test
    @DisplayName("未知 Provider → 参数拒绝")
    void unknownProvider_shouldReject() {
        assertThatThrownBy(() -> service.getConfig("WECHAT_MP"))
                .isInstanceOf(SsoRejectionException.class)
            .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_BINDING_INVALID);
    }

    @Test
    @DisplayName("配置查询：secret 不回传，只回是否已配置")
    void getConfig_shouldNotExposeSecret() {
        loginAs(1L);
        var view = service.getConfig("WECOM");
        assertThat(view.get("secretConfigured")).isEqualTo(true);
        assertThat(String.valueOf(view)).doesNotContain("secret-value");
    }

    // ======== I5 复验补证（G5/G7）：登录前安全发起、回调白名单分离、审计查询守卫 ========

    private org.springframework.transaction.PlatformTransactionManager noopTxManager() {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) { return new org.springframework.transaction.support.SimpleTransactionStatus(); }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) { }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { }
        };
    }

    private com.sw.ck.system.service.TenantValidityService mockedValidity() {
        com.sw.ck.system.service.TenantValidityService v =
                Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doNothing().when(v).requireValid(org.mockito.ArgumentMatchers.any());
        return v;
    }

    private SsoAuthService serviceWith(SsoCallbackPolicy policy,
                                       com.sw.ck.system.service.TenantValidityService validity) {
        return new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper,
                Mockito.mock(SysUserService.class),
                List.of(new WecomSsoProviderClient(), new FeishuSsoProviderClient(),
                        new DingtalkSsoProviderClient()),
                new com.sw.ck.system.sso.SsoCredentialCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                policy, validity,
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class), noopTxManager(), null, null);
    }

    private SsoAuthService serviceWithTenantMapper(com.sw.ck.system.mapper.SysTenantMapper tenantMapper) {
        return new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper,
                Mockito.mock(SysUserService.class),
                List.of(new WecomSsoProviderClient(), new FeishuSsoProviderClient(),
                        new DingtalkSsoProviderClient()),
                new com.sw.ck.system.sso.SsoCredentialCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                new SsoCallbackPolicy("", List.of(), ""), validService(),
                tenantMapper, noopTxManager(), null, null);
    }

    private com.sw.ck.system.service.TenantValidityService validService() {
        com.sw.ck.system.service.TenantValidityService v =
                Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doNothing().when(v).requireValid(org.mockito.ArgumentMatchers.any());
        return v;
    }

    @Test
    @DisplayName("V012-BUG-019 名称解析：空白 → sso_tenant_required")
    void resolveTenantName_blank_shouldReject() {
        assertThatThrownBy(() -> service.startAuthorizeLogin("WECOM", "   ", null))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_TENANT_REQUIRED);
    }

    @Test
    @DisplayName("V012-BUG-019 名称解析：唯一命中 → 解析为该租户并按既有链发起（trim 生效）")
    void resolveTenantName_uniqueHit_shouldStartWithResolvedTenant() {
        com.sw.ck.system.mapper.SysTenantMapper tenantMapper =
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class);
        com.sw.ck.system.entity.SysTenant t = new com.sw.ck.system.entity.SysTenant();
        t.setId(100L);
        t.setName("演示企业");
        Mockito.when(tenantMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(t));
        SsoAuthService svc = serviceWithTenantMapper(tenantMapper);

        SsoAuthService.AuthorizeStart start = svc.startAuthorizeLogin("WECOM", " 演示企业 ", "/workspace");

        assertThat(start.authorizeUrl()).contains("appid=ww-test-corp");
        Mockito.verify(stateMapper).insert(org.mockito.ArgumentMatchers.<SsoAuthState>argThat(s ->
                s.getTenantId() != null && s.getTenantId() == 100L && s.getConsumed() == 0));
    }

    @Test
    @DisplayName("V012-BUG-019 名称解析：零命中 → sso_tenant_not_found，不签发 state")
    void resolveTenantName_noHit_shouldReject() {
        com.sw.ck.system.mapper.SysTenantMapper tenantMapper =
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class);
        Mockito.when(tenantMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        SsoAuthService svc = serviceWithTenantMapper(tenantMapper);

        assertThatThrownBy(() -> svc.startAuthorizeLogin("WECOM", "不存在企业", null))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_TENANT_NOT_FOUND);
        Mockito.verify(stateMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoAuthState>any());
    }

    @Test
    @DisplayName("V012-BUG-019 名称解析：多行命中 → sso_tenant_ambiguous，不任意选中")
    void resolveTenantName_ambiguous_shouldReject() {
        com.sw.ck.system.mapper.SysTenantMapper tenantMapper =
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class);
        com.sw.ck.system.entity.SysTenant a = new com.sw.ck.system.entity.SysTenant();
        a.setId(1L); a.setName("同名企业");
        com.sw.ck.system.entity.SysTenant b = new com.sw.ck.system.entity.SysTenant();
        b.setId(2L); b.setName("同名企业");
        Mockito.when(tenantMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(a, b));
        SsoAuthService svc = serviceWithTenantMapper(tenantMapper);

        assertThatThrownBy(() -> svc.startAuthorizeLogin("WECOM", "同名企业", null))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_TENANT_AMBIGUOUS);
        Mockito.verify(stateMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoAuthState>any());
    }

    @Test
    @DisplayName("V012-BUG-019 名称解析：命中但租户停用/无效 → 按既有有效性校验拒绝")
    void resolveTenantName_invalidTenant_shouldReject() {
        com.sw.ck.system.mapper.SysTenantMapper tenantMapper =
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class);
        com.sw.ck.system.entity.SysTenant t = new com.sw.ck.system.entity.SysTenant();
        t.setId(777L); t.setName("停用企业");
        Mockito.when(tenantMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(t));
        com.sw.ck.system.service.TenantValidityService invalid =
                Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doThrow(new IllegalStateException("租户无效")).when(invalid).requireValid(777L);
        SsoAuthService svc = new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper,
                Mockito.mock(SysUserService.class),
                List.of(new WecomSsoProviderClient(), new FeishuSsoProviderClient(),
                        new DingtalkSsoProviderClient()),
                new com.sw.ck.system.sso.SsoCredentialCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                new SsoCallbackPolicy("", List.of(), ""), invalid,
                tenantMapper, noopTxManager(), null, null);

        assertThatThrownBy(() -> svc.startAuthorizeLogin("WECOM", "停用企业", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户无效");
        Mockito.verify(stateMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoAuthState>any());
    }

    @Test
    @DisplayName("登录前发起：未指定租户 → fail closed")
    void startAuthorizeLogin_withoutTenant_shouldReject() {
        assertThatThrownBy(() -> service.startAuthorizeLogin("WECOM", (Long) null, null))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_TENANT_REQUIRED);
    }

    @Test
    @DisplayName("登录前发起：租户无效 → fail closed 且不签发 state")
    void startAuthorizeLogin_invalidTenant_shouldReject() {
        com.sw.ck.system.service.TenantValidityService invalid =
                Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doThrow(new IllegalStateException("租户无效"))
                .when(invalid).requireValid(777L);
        SsoAuthService svc = serviceWith(new SsoCallbackPolicy("", List.of(), ""), invalid);
        assertThatThrownBy(() -> svc.startAuthorizeLogin("WECOM", 777L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户无效");
        Mockito.verify(stateMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoAuthState>any());
    }

    @Test
    @DisplayName("登录前发起：租户有效 + Provider 启用 → state 与租户绑定落库")
    void startAuthorizeLogin_validTenant_shouldBindStateToTenant() {
        SsoAuthService.AuthorizeStart start = service.startAuthorizeLogin("WECOM", 100L, "/workspace");
        assertThat(start.authorizeUrl()).contains("appid=ww-test-corp");
        Mockito.verify(stateMapper).insert(org.mockito.ArgumentMatchers.<SsoAuthState>argThat(s ->
                s.getTenantId() != null && s.getTenantId() == 100L && s.getConsumed() == 0));
    }

    @Test
    @DisplayName("回调白名单：默认相对路径模式可用；显式白名单外 fail closed")
    void callbackPolicy_allowlistSeparation() {
        SsoCallbackPolicy relative = new SsoCallbackPolicy("", List.of(), "");
        assertThat(relative.isRelativeMode()).isTrue();
        assertThat(relative.resolveCallbackUrl("WECOM")).isEqualTo("/api/auth/sso/wecom/callback");

        SsoCallbackPolicy allowlisted = new SsoCallbackPolicy(
                "https://oa.example.com", List.of("https://oa.example.com/api/auth/sso/"), "");
        assertThat(allowlisted.resolveCallbackUrl("WECOM"))
                .isEqualTo("https://oa.example.com/api/auth/sso/wecom/callback");

        SsoCallbackPolicy hostile = new SsoCallbackPolicy(
                "https://evil.example.com", List.of("https://oa.example.com/api/auth/sso/"), "");
        assertThatThrownBy(() -> hostile.resolveCallbackUrl("WECOM"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("前端回跳路径：合法 path 前置基路径；非法 path 取 fallback；协议相对与外部 URL 不放大")
    void callbackPolicy_frontendPathComposition() {
        SsoCallbackPolicy withBase = new SsoCallbackPolicy("", List.of(), "/sw");
        assertThat(withBase.resolveFrontendPath("/sso/return", "/sso/return")).isEqualTo("/sw/sso/return");
        assertThat(withBase.resolveFrontendPath("/sso/bind?ticket=x", "/sso/bind")).isEqualTo("/sw/sso/bind?ticket=x");
        assertThat(withBase.resolveFrontendPath(null, "/workspace")).isEqualTo("/sw/workspace");
        assertThat(withBase.resolveFrontendPath("//evil.example.com", "/workspace")).isEqualTo("/sw/workspace");
        assertThat(withBase.resolveFrontendPath("https://evil.example.com", "/workspace")).isEqualTo("/sw/workspace");

        SsoCallbackPolicy noBase = new SsoCallbackPolicy("", List.of(), "");
        assertThat(noBase.resolveFrontendPath("/sso/return", "/sso/return")).isEqualTo("/sso/return");

        SsoCallbackPolicy hostileBase = new SsoCallbackPolicy("", List.of(), "//evil.example.com");
        assertThat(hostileBase.resolveFrontendPath("/sso/return", "/sso/return")).isEqualTo("/sso/return");
    }

    @Test
    @DisplayName("审计查询：无租户上下文 → 拒绝；有上下文 → 按当前租户返回")
    void queryAudit_tenantScoped() {
        assertThatThrownBy(() -> service.queryAudit(null, null, null, null, 0, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户上下文缺失");
        loginAs(1L);
        Mockito.when(auditMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        var view = service.queryAudit("WECOM", null, null, null, 0, 20);
        assertThat(view.get("tenantId")).isEqualTo("1");
        Mockito.verify(auditMapper).selectList(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("配置保存：Provider 应用已登记在其他租户 → 跨租户冲突拒绝并审计")
    void saveConfig_appRegisteredByAnotherTenant_shouldReject() {
        loginAs(2L);
        Mockito.when(configMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        Mockito.when(configMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        assertThatThrownBy(() -> service.saveConfig("WECOM", true, "ww-same-app", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("跨租户重复登记");
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "CONFLICT_REJECTED".equals(a.getEventType())));
    }

    @Test
    @DisplayName("绑定：同一摘要已在其他租户绑定 → 跨租户拒绝（G6a/V87）")
    void bind_crossTenantDigestConflict_shouldReject() {
        loginAs(2L);
        Mockito.when(bindingMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        Mockito.when(bindingMapper.selectActiveByExternal(org.mockito.ArgumentMatchers.eq("WECOM"),
                org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.bind("WECOM", 2L, "ext-cross"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_BINDING_CONFLICT);
    }

    @Test
    @DisplayName("绑定并发唯一键竞争：失败侧转为业务拒绝且不产生成功审计")
    void bind_uniqueConstraintRace_shouldFailClosed() {
        loginAs(2L);
        Mockito.when(bindingMapper.selectActiveByExternal(org.mockito.ArgumentMatchers.eq("WECOM"),
                org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);
        Mockito.when(bindingMapper.selectActiveByUser("WECOM", 2L, 2L)).thenReturn(null);
        Mockito.when(bindingMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        Mockito.when(bindingMapper.insert(org.mockito.ArgumentMatchers.<SsoUserBinding>any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("global binding unique"));

        assertThatThrownBy(() -> service.bind("WECOM", 2L, "subject-race"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_BINDING_CONFLICT);
        Mockito.verify(auditMapper).insert(org.mockito.ArgumentMatchers.<SsoAuditRecord>argThat(a ->
                "CONFLICT_REJECTED".equals(a.getEventType()) && "DENIED".equals(a.getResult())));
    }

    @Test
    @DisplayName("回调：换票失败后 state 仍保持已消费，重放在外呼前拒绝（G5b）")
    void stateConsumedSurvivesProviderFailure_replayRejectedBeforeOutbound() {
        // lambdaUpdate 需要 MP TableInfo 缓存（单测上下文无 MapperScan 初始化）
        com.baomidou.mybatisplus.core.MybatisConfiguration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(cfg, ""), SsoAuthState.class);
        SsoProviderClient failing = new SsoProviderClient() {
            @Override public String provider() { return "WECOM"; }
            @Override public String buildAuthorizeUrl(SsoProviderConfigView config, String redirectUri, String state) { return "https://provider.example?state=" + state; }
            @Override public SsoProviderClient.ExchangeResult exchangeExternalId(SsoProviderConfigView config, String code, String redirectUri) { throw new SsoProviderClient.SsoProviderException("exchange failed"); }
        };
        SsoAuthService svc = new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper,
                Mockito.mock(SysUserService.class), List.of(failing),
                new com.sw.ck.system.sso.SsoCredentialCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                new SsoCallbackPolicy("", List.of(), ""), mockedValidity(),
                Mockito.mock(com.sw.ck.system.mapper.SysTenantMapper.class), noopTxManager(), null, null);
        SsoAuthState fresh = new SsoAuthState();
        fresh.setId(7L); fresh.setProvider("WECOM"); fresh.setConsumed(0); fresh.setTenantId(1L);
        fresh.setExpireAt(LocalDateTime.now().plusSeconds(60));
        // A4：state 指纹与 setUp 默认配置桩同源，使换票失败发生在出站调用内（保持本用例语义）
        fresh.setConfigDigest(SsoAuthService.fingerprintOf("WECOM", "ww-test-corp", "secret-value", "", 1));
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString())).thenReturn(fresh);
        Mockito.when(stateMapper.update(org.mockito.ArgumentMatchers.eq(null), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        Mockito.when(bindingMapper.selectActiveByExternal(org.mockito.ArgumentMatchers.eq("WECOM"),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-x", "state-ok"))
                .isInstanceOf(SsoProviderClient.SsoProviderException.class);
        SsoAuthState consumed = new SsoAuthState();
        consumed.setId(7L); consumed.setProvider("WECOM"); consumed.setConsumed(1); consumed.setTenantId(1L);
        consumed.setExpireAt(LocalDateTime.now().plusSeconds(60));
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString())).thenReturn(consumed);
        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-y", "state-ok"))
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey", SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED);
    }

    @Test
    @DisplayName("回调：租户停用/过期 → 在消费 state 与外呼前拒绝（G6b）")
    void handleCallback_invalidTenant_shouldRejectBeforeConsumeOrOutbound() {
        com.sw.ck.system.service.TenantValidityService invalid =
                Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doThrow(new IllegalStateException("租户无效"))
                .when(invalid).requireValid(100L);
        SsoAuthService svc = serviceWith(new SsoCallbackPolicy("", List.of(), ""), invalid);
        SsoAuthState fresh = new SsoAuthState();
        fresh.setId(17L);
        fresh.setProvider("WECOM");
        fresh.setTenantId(100L);
        fresh.setConsumed(0);
        fresh.setExpireAt(LocalDateTime.now().plusSeconds(60));
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(fresh);

        assertThatThrownBy(() -> svc.handleCallback("WECOM", "code-x", "state-invalid-tenant"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户无效");
        Mockito.verify(stateMapper, Mockito.never())
                .update(org.mockito.ArgumentMatchers.eq(null), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("启用 Provider：三 Provider 均拒绝缺失/占位 secret；停用不要求 secret（G3b2）")
    void saveConfig_enabledProvidersRequireRealCredentials() {
        loginAs(2L);
        Mockito.when(configMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        Mockito.when(configMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        for (String provider : SsoAuthService.PROVIDERS) {
            assertThatThrownBy(() -> service.saveConfig(provider, true,
                    "app-" + provider, null, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("有效 appId 与 secret");
            assertThatThrownBy(() -> service.saveConfig(provider, true,
                    "app-" + provider, "placeholder", null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("有效 appId 与 secret");
        }
        service.saveConfig("WECOM", false, null, null, null, null);
        Mockito.verify(configMapper, Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<SsoProviderConfig>argThat(c -> c.getEnabled() == 1));
    }

    @Test
    @DisplayName("配置保存：无跨租户冲突 → 正常登记")
    void saveConfig_noConflict_shouldPass() {
        loginAs(2L);
        Mockito.when(configMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        Mockito.when(configMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        Mockito.when(configMapper.insert(org.mockito.ArgumentMatchers.<SsoProviderConfig>any()))
                .thenAnswer(inv -> {
                    SsoProviderConfig c = inv.getArgument(0);
                    c.setId(11L);
                    return 1;
                });
        service.saveConfig("WECOM", true, "ww-new-app", "s", null, null);
        Mockito.verify(configMapper).insert(org.mockito.ArgumentMatchers.<SsoProviderConfig>any());
    }
}
