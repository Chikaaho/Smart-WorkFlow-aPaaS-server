package com.sw.ck.system.config;

import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.mapper.SysMenuMapper;
import com.sw.ck.system.mapper.SysRoleDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.security.UserDetailsProviderImpl;
import com.sw.ck.system.service.SysUserService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 系统管理模块自动配置。
 * <p>
 * 注册 {@link UserDetailsProvider} 实现（激活 sw-security 的 LoginUserLoader 与 JWT 认证流程）
 * 以及 {@link PasswordEncoder}（sw-security 本身不提供，在此补齐）。
 * </p>
 */
@AutoConfiguration
public class SystemAutoConfiguration {

    /**
     * 注册 UserDetailsProvider 实现，触发 sw-security 的 LoginUserCacheService / LoginUserLoader
     * 自动装载（参见 {@link com.sw.ck.security.config.SecurityAutoConfiguration}）。
     * <p>
     * 注入 RBAC Mapper 用于组装 roles / permissions / superAdmin（替换旧有 userId==1 硬编）；
     * 注入 TenantValidityService 用于身份装载期租户有效性校验（I5）。
     * </p>
     */
    @Bean
    public UserDetailsProvider userDetailsProvider(SysUserService sysUserService,
                                                   SysUserRoleMapper sysUserRoleMapper,
                                                   SysRoleMapper sysRoleMapper,
                                                   SysRoleMenuMapper sysRoleMenuMapper,
                                                   SysMenuMapper sysMenuMapper,
                                                   SysRoleDeptMapper sysRoleDeptMapper,
                                                   SysTenantMapper sysTenantMapper) {
        return new UserDetailsProviderImpl(sysUserService, sysUserRoleMapper, sysRoleMapper,
                sysRoleMenuMapper, sysMenuMapper, sysRoleDeptMapper,
                new com.sw.ck.system.service.TenantValidityService(sysTenantMapper));
    }

    /**
     * 租户有效性契约实现（I5 复验 G2a）：供 openapi 等跨模块非浏览器入口校验。
     * <p>
     * 契约返回 {@code Optional<Boolean>} 且恒 present（fail-closed）：{@code tenantId} 为 null、
     * 租户不存在/停用/过期一律判定为 present false，不得以 empty 表达"无法判定"。
     * </p>
     */
    @Bean
    public com.sw.ck.system.api.tenant.TenantValidityFacade tenantValidityFacade(
            com.sw.ck.system.service.TenantValidityService tenantValidityService) {
        return tenantId -> java.util.Optional.of(tenantValidityService.isValid(tenantId));
    }

    /**
     * BCrypt 密码编码器（sw-security 不提供，业务方负责注册）。
     * 使用 strength=10，与其他模块一致。
     */
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder bcryptPasswordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * SSO Provider 凭据加密器（I5；sso-admin-config 显式化改造）：AES-256-GCM。
     * <p>
     * 不再注册共享 {@code AesGcmCipher} bean（历史 {@code @ConditionalOnMissingBean}
     * 与 agent 模块同型条件竞争，实际密钥源随 bean 注册顺序漂移——G4-S 运行诊断实证），
     * 改为 SSO 装配时以专属密钥 {@code sw.security.sso.cipher-key}（SW_SSO_CIPHER_KEY）
     * 显式构造 {@link SsoCredentialCipher}：密钥来源固定、缺失即启动失败；全局 agent
     * 加密器及其密钥不受影响（不更换全局密钥，其他模块密文不受影响）。
     * </p>
     */
    @Bean
    public com.sw.ck.system.sso.SsoAuthService ssoAuthService(
            com.sw.ck.system.mapper.SsoProviderConfigMapper configMapper,
            com.sw.ck.system.mapper.SsoUserBindingMapper bindingMapper,
            com.sw.ck.system.mapper.SsoAuthStateMapper stateMapper,
            com.sw.ck.system.mapper.SsoAuditRecordMapper auditMapper,
            com.sw.ck.system.service.SysUserService sysUserService,
            @org.springframework.beans.factory.annotation.Value("${sw.security.sso.cipher-key:}") String ssoCipherKey,
            com.sw.ck.system.sso.SsoCallbackPolicy ssoCallbackPolicy,
            com.sw.ck.system.service.TenantValidityService tenantValidityService,
            com.sw.ck.system.mapper.SysTenantMapper tenantMapper,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.sw.ck.security.cache.LoginUserCacheService loginUserCacheService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.sw.ck.system.service.RefreshTokenService refreshTokenService) {
        return new com.sw.ck.system.sso.SsoAuthService(configMapper, bindingMapper, stateMapper,
                auditMapper, sysUserService,
                java.util.List.of(
                        new com.sw.ck.system.sso.WecomSsoProviderClient(),
                        new com.sw.ck.system.sso.FeishuSsoProviderClient(),
                        new com.sw.ck.system.sso.DingtalkSsoProviderClient()),
                new com.sw.ck.system.sso.SsoCredentialCipher(ssoCipherKey),
                ssoCallbackPolicy, tenantValidityService, tenantMapper, transactionManager,
                loginUserCacheService, refreshTokenService);
    }
}
