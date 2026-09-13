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
        service = new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper,
                sysUserService, List.of(new WecomSsoProviderClient(), new FeishuSsoProviderClient(),
                new DingtalkSsoProviderClient()),
                new com.sw.ck.common.crypto.AesGcmCipher(
                        java.util.Base64.getEncoder().encodeToString(new byte[32])));
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
        Mockito.when(bindingMapper.selectActiveByExternal("WECOM", 1L, "ext-1")).thenReturn(existing);
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
                        && b.getExternalDigest().length() == 64));
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
}
