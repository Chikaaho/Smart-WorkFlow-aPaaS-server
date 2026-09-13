package com.sw.ck.system.sso;

import com.sw.ck.system.entity.SsoAuditRecord;
import com.sw.ck.system.entity.SsoAuthState;
import com.sw.ck.system.entity.SsoProviderConfig;
import com.sw.ck.system.entity.SsoUserBinding;
import com.sw.ck.system.mapper.SsoAuditRecordMapper;
import com.sw.ck.system.mapper.SsoAuthStateMapper;
import com.sw.ck.system.mapper.SsoProviderConfigMapper;
import com.sw.ck.system.mapper.SsoUserBindingMapper;
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
                new com.sw.ck.common.crypto.AesGcmCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                new SsoCallbackPolicy("", List.of()),
                tenantValidityService, noopTxManager());
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
                    config.setAppSecretEnc(new com.sw.ck.common.crypto.AesGcmCipher(
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

    @Test
    @DisplayName("回调：state 不存在 → 拒绝且不换票")
    void handleCallback_unknownState_shouldReject() {
        Mockito.when(stateMapper.selectGlobalByState(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);
        assertThatThrownBy(() -> service.handleCallback("WECOM", "code-x", "state-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("授权状态无效");
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已消费");
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已过期");
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    @DisplayName("绑定：外部身份已被他人绑定 → 冲突拒绝并审计")
    void bind_externalAlreadyBound_shouldReject() {
        loginAs(1L);
        SsoUserBinding existing = new SsoUserBinding();
        existing.setUserId(99L);
        Mockito.when(bindingMapper.selectActiveByExternal(org.mockito.ArgumentMatchers.eq("WECOM"), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(SsoAuthService.digest("ext-1")))).thenReturn(existing);
        assertThatThrownBy(() -> service.bind("WECOM", 2L, "ext-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已绑定其他本地账号");
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已绑定其他外部身份");
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
                .isInstanceOf(IllegalArgumentException.class);
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
                new com.sw.ck.common.crypto.AesGcmCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                policy, validity, noopTxManager());
    }

    @Test
    @DisplayName("登录前发起：未指定租户 → fail closed")
    void startAuthorizeLogin_withoutTenant_shouldReject() {
        assertThatThrownBy(() -> service.startAuthorizeLogin("WECOM", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("显式指定租户");
    }

    @Test
    @DisplayName("登录前发起：租户无效 → fail closed 且不签发 state")
    void startAuthorizeLogin_invalidTenant_shouldReject() {
        com.sw.ck.system.service.TenantValidityService invalid =
                Mockito.mock(com.sw.ck.system.service.TenantValidityService.class);
        Mockito.doThrow(new IllegalStateException("租户无效"))
                .when(invalid).requireValid(777L);
        SsoAuthService svc = serviceWith(new SsoCallbackPolicy("", List.of()), invalid);
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
        SsoCallbackPolicy relative = new SsoCallbackPolicy("", List.of());
        assertThat(relative.isRelativeMode()).isTrue();
        assertThat(relative.resolveCallbackUrl("WECOM")).isEqualTo("/api/auth/sso/wecom/callback");

        SsoCallbackPolicy allowlisted = new SsoCallbackPolicy(
                "https://oa.example.com", List.of("https://oa.example.com/api/auth/sso/"));
        assertThat(allowlisted.resolveCallbackUrl("WECOM"))
                .isEqualTo("https://oa.example.com/api/auth/sso/wecom/callback");

        SsoCallbackPolicy hostile = new SsoCallbackPolicy(
                "https://evil.example.com", List.of("https://oa.example.com/api/auth/sso/"));
        assertThatThrownBy(() -> hostile.resolveCallbackUrl("WECOM"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("白名单");
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("其他租户");
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
            @Override public String exchangeExternalId(SsoProviderConfigView config, String code) { throw new SsoProviderClient.SsoProviderException("exchange failed"); }
        };
        SsoAuthService svc = new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper,
                Mockito.mock(SysUserService.class), List.of(failing),
                new com.sw.ck.common.crypto.AesGcmCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])),
                new SsoCallbackPolicy("", List.of()), mockedValidity(), noopTxManager());
        SsoAuthState fresh = new SsoAuthState();
        fresh.setId(7L); fresh.setProvider("WECOM"); fresh.setConsumed(0); fresh.setTenantId(1L);
        fresh.setExpireAt(LocalDateTime.now().plusSeconds(60));
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已消费");
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
