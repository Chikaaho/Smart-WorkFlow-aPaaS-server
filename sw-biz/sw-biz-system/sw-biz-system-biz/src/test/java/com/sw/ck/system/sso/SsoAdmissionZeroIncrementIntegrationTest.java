package com.sw.ck.system.sso;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SsoAuthState;
import com.sw.ck.system.mapper.SsoAuditRecordMapper;
import com.sw.ck.system.mapper.SsoAuthStateMapper;
import com.sw.ck.system.mapper.SsoProviderConfigMapper;
import com.sw.ck.system.mapper.SsoUserBindingMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserPostMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.TenantValidityService;
import com.sw.ck.system.service.impl.SysUserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B 端手机号准入零增量与租户隔离集成（sso-admin-config 审查 02 账本 A2）。
 * <p>
 * 真实 DB（H2 + V84/V87/V104 SSO 表结构）、真实 Mapper/租户拦截器/事务链、
 * 受控厂商桩（不强制厂商扫码）；与已锁定的真实链审计证据
 * （a2-batch7-matrix-audit.json）组合覆盖：
 * <ol>
 *   <li>可信手机号无本地用户 → 统一拒绝且 sys_user/sys_sso_user_binding 零增量；</li>
 *   <li>租户内重复手机号 → 拒绝且零增量（重复拒绝不等价于唯一约束拒绝）；</li>
 *   <li>仅其他租户（tenant 1）存在同号用户 → 当前租户（tenant 100）仍拒绝，
 *       其他租户对象零增量（不跨租户搜索）；</li>
 *   <li>正验对照：唯一命中 → 自动绑定且不新建用户（证明夹具与计数口径有效）。</li>
 * </ol>
 * 会话零增量：拒绝发生在准入链内，票据签发/兑换（会话建立点）不可达，
 * 以审计无 LOGIN_SUCCESS 事件断言。
 * </p>
 */
@SpringBootTest(
        classes = SsoAdmissionZeroIncrementIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_ssoadmission;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql,"
                        + "classpath:db/migration/system/h2/V84__i5_sso_identity.sql,"
                        + "classpath:db/migration/system/h2/V87__i5_binding_digest_global_unique.sql,"
                        + "classpath:db/migration/system/h2/V104__sso_state_config_digest.sql",
                // 压过 test profile 的 data-h2.sql（其 seed 需 dict 表）：空脚本占位
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("B 端准入零增量与租户隔离集成（真实 DB + 受控厂商桩）")
class SsoAdmissionZeroIncrementIntegrationTest {

    private static final long TENANT_ID = 100L;
    private static final long OTHER_TENANT_ID = 1L;
    private static final long CONFIG_ID = 91001L;

    /** 受控厂商桩当前返回的可信手机号（各用例切换） */
    private static final AtomicReference<String> STUB_PHONE = new AtomicReference<>("17800009001");

    @Autowired
    private SsoAuthService ssoAuthService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedFixtures() {
        STUB_PHONE.set("17800009001");
        // Provider 配置行（可解密测试密文：发起/回调两侧 decryptConfig 均须成功）
        jdbc.update("""
                        MERGE INTO sys_sso_provider_config (id, create_time, update_time, deleted, tenant_id,
                        version, provider, enabled, app_id, app_secret_enc, extra_config, redirect_path)
                        KEY (id) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 'WECOM', 1,
                        'a2-fixture', 'x', '{}', '/workspace')
                        """,
                CONFIG_ID, TENANT_ID);
        jdbc.update("UPDATE sys_sso_provider_config SET app_secret_enc=? WHERE id=?",
                new SsoCredentialCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]))
                        .encrypt("a2-fixture-secret"), CONFIG_ID);
    }

    @AfterEach
    void clearHolder() {
        LoginUserHolder.clear();
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("A2：可信手机号无本地用户 → 拒绝且用户/绑定零增量、无 LOGIN_SUCCESS")
    void admissionNoLocalUser_rejected_zeroIncrement() {
        long usersBefore = users();
        long bindingsBefore = bindings();
        long loginSuccessBefore = auditRows("LOGIN_SUCCESS");
        long auditBefore = auditRowsDetail("no local user with trusted phone");

        STUB_PHONE.set("17800009999");
        assertThatThrownBy(this::runAdmission)
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey",
                        com.sw.ck.system.security.SystemErrorKeys.SSO_ADMISSION_REJECTED);

        assertThat(users()).as("sys_user 零增量").isEqualTo(usersBefore);
        assertThat(bindings()).as("sys_sso_user_binding 零增量").isEqualTo(bindingsBefore);
        assertThat(auditRows("LOGIN_SUCCESS")).as("无会话建立事件").isEqualTo(loginSuccessBefore);
        assertThat(auditRowsDetail("no local user with trusted phone"))
                .as("拒绝审计精确一条").isEqualTo(auditBefore + 1);
    }

    @Test
    @DisplayName("A2：租户内重复手机号 → 拒绝且零增量（重复拒绝≠唯一约束拒绝）")
    void admissionAmbiguousPhoneInTenant_rejected_zeroIncrement() {
        seedUser(9521L, TENANT_ID, "a2dup1", "17800009998");
        seedUser(9522L, TENANT_ID, "a2dup2", "17800009998");
        long usersBefore = users();
        long bindingsBefore = bindings();
        long auditBefore = auditRowsDetail("ambiguous phone in tenant");

        STUB_PHONE.set("17800009998");
        assertThatThrownBy(this::runAdmission)
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey",
                        com.sw.ck.system.security.SystemErrorKeys.SSO_ADMISSION_REJECTED);

        assertThat(users()).as("sys_user 零增量").isEqualTo(usersBefore);
        assertThat(bindings()).as("sys_sso_user_binding 零增量").isEqualTo(bindingsBefore);
        assertThat(auditRowsDetail("ambiguous phone in tenant"))
                .as("重复手机号拒绝审计（非唯一约束拒绝）精确一条").isEqualTo(auditBefore + 1);
        Integer dupRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE tenant_id=? AND phone IN ('17800009998','+8617800009998') AND deleted=0",
                Integer.class, TENANT_ID);
        assertThat(dupRows).as("两条重复夹具仍在（不做批量合并删除）").isEqualTo(2);
    }

    @Test
    @DisplayName("A2：仅其他租户有同号用户 → 当前租户仍拒绝且其他租户零增量")
    void admissionOnlyOtherTenantHasPhone_stillRejected_otherTenantUntouched() {
        // tenant 1 存在同号用户；tenant 100 无匹配
        seedUser(9531L, OTHER_TENANT_ID, "a2other", "17800009997");
        long usersBefore = users();
        long bindingsBefore = bindings();
        long otherBindingsBefore = bindingsFor(OTHER_TENANT_ID);
        long auditBefore = auditRowsDetail("no local user with trusted phone");

        STUB_PHONE.set("17800009997");
        assertThatThrownBy(this::runAdmission)
                .isInstanceOf(SsoRejectionException.class)
                .hasFieldOrPropertyWithValue("errorKey",
                        com.sw.ck.system.security.SystemErrorKeys.SSO_ADMISSION_REJECTED);

        assertThat(users()).as("sys_user 零增量").isEqualTo(usersBefore);
        assertThat(bindings()).as("sys_sso_user_binding 零增量").isEqualTo(bindingsBefore);
        assertThat(bindingsFor(OTHER_TENANT_ID)).as("其他租户无绑定增量").isEqualTo(otherBindingsBefore);
        assertThat(auditRowsDetail("no local user with trusted phone"))
                .as("当前租户拒绝（不跨租户搜索）").isEqualTo(auditBefore + 1);
    }

    @Test
    @DisplayName("A2 正验对照：唯一命中 → 自动绑定既有用户且不新建用户")
    void admissionUniquePhonePositiveControl_bindsWithoutUserCreation() {
        seedUser(9541L, TENANT_ID, "a2uniq", "17800009996");
        long usersBefore = users();
        long bindingsBefore = bindings();

        STUB_PHONE.set("17800009996");
        runAdmission();

        assertThat(users()).as("不自动创建用户").isEqualTo(usersBefore);
        assertThat(bindings()).as("自动绑定恰好一行").isEqualTo(bindingsBefore + 1);
        Long boundUser = jdbc.queryForObject(
                "SELECT user_id FROM sys_sso_user_binding WHERE provider='WECOM' AND tenant_id=? AND deleted=0",
                Long.class, TENANT_ID);
        assertThat(boundUser).isEqualTo(9541L);
        assertThat(auditRows("LOGIN_SUCCESS")).as("正验链路 LOGIN_SUCCESS 审计在册").isGreaterThanOrEqualTo(1);
    }

    // ==================== 工具 ====================

    private void runAdmission() {
        SsoAuthService.AuthorizeStart start = ssoAuthService.startAuthorizeLogin("WECOM", TENANT_ID, null);
        ssoAuthService.handleCallback("WECOM", "code-a2", start.state());
    }

    private long users() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE deleted=0", Long.class);
        return n == null ? -1 : n;
    }

    private long bindings() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_sso_user_binding WHERE deleted=0", Long.class);
        return n == null ? -1 : n;
    }

    private long bindingsFor(long tenantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_sso_user_binding WHERE deleted=0 AND tenant_id=?", Long.class, tenantId);
        return n == null ? -1 : n;
    }

    private long auditRows(String eventType) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_sso_audit_record WHERE deleted=0 AND event_type=? AND provider='WECOM'",
                Long.class, eventType);
        return n == null ? -1 : n;
    }

    private long auditRowsDetail(String detail) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_sso_audit_record WHERE deleted=0 AND event_type='ADMISSION_REJECTED' AND detail=?",
                Long.class, detail);
        return n == null ? -1 : n;
    }

    private void seedUser(long id, long tenantId, String username, String phone) {
        jdbc.update("""
                        MERGE INTO sys_user (id, create_time, update_time, deleted, tenant_id, version,
                        username, password, real_name, dept_id, status, is_admin, phone)
                        KEY (id) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, ?, 'x', 'A2用户', NULL, 0, 0, ?)
                        """,
                id, tenantId, username, phone);
    }

    static SsoProviderClient controlledClient() {
        return new SsoProviderClient() {
            @Override public String provider() { return "WECOM"; }
            @Override public String buildAuthorizeUrl(SsoProviderConfigView c, String r, String st) { return "u"; }
            @Override public ExchangeResult exchangeExternalId(SsoProviderConfigView c, String code, String redirectUri) {
                // 外部主体随手机号派生：各用例独立身份，避免正验用例的绑定行污染后续用例的准入分支
                return new ExchangeResult("ext-a2-" + STUB_PHONE.get(), null, STUB_PHONE.get());
            }
        };
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.system.mapper");
            return configurer;
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }

        @Bean
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
                @Override public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }
                @Override public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }
                @Override public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }
                @Override public DataScopeType getDataScopeType() {
                    LoginUser user = LoginUserHolder.get();
                    if (user == null || user.getDataScope() == null) {
                        return DataScopeType.ALL;
                    }
                    return DataScopeType.valueOf(user.getDataScope().name());
                }
                @Override public Set<Long> getCustomDeptIds() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.getCustomDeptIds() != null ? user.getCustomDeptIds() : Set.of();
                }
                @Override public boolean isSuperAdmin() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }

        @Bean
        public SysUserService sysUserService(PasswordEncoder passwordEncoder, SysUserRoleMapper userRoleMapper,
                                             SysUserPostMapper userPostMapper, SysRoleMapper roleMapper,
                                             SysPostMapper postMapper) {
            return new SysUserServiceImpl(passwordEncoder, userRoleMapper, userPostMapper, roleMapper, postMapper);
        }

        @Bean
        public TenantValidityService tenantValidityService() {
            // 回调/发起链对租户有效性的显式校验由服务内调用承担；本切片固定有效
            return org.mockito.Mockito.mock(TenantValidityService.class);
        }

        @Bean
        public SsoAuthService ssoAuthService(SsoProviderConfigMapper configMapper,
                                             SsoUserBindingMapper bindingMapper,
                                             SsoAuthStateMapper stateMapper,
                                             SsoAuditRecordMapper auditMapper,
                                             SysUserService sysUserService,
                                             TenantValidityService tenantValidityService,
                                             @org.springframework.lang.Nullable SysTenantMapper tenantMapper,
                                             PlatformTransactionManager transactionManager) {
            return new SsoAuthService(configMapper, bindingMapper, stateMapper, auditMapper, sysUserService,
                    List.of(controlledClient()),
                    new SsoCredentialCipher(java.util.Base64.getEncoder().encodeToString(new byte[32])),
                    new SsoCallbackPolicy("", List.of(), ""), tenantValidityService,
                    tenantMapper, transactionManager, null, null);
        }
    }
}
