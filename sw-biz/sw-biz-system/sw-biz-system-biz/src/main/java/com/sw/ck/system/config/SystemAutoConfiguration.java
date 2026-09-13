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
     * BCrypt 密码编码器（sw-security 不提供，业务方负责注册）。
     * 使用 strength=10，与其他模块一致。
     */
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder bcryptPasswordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * SSO Provider 凭据加密器（I5）：AES-256-GCM，密钥经环境变量注入；
     * 缺失/空白时启动失败（fail-fast，与 RsaLoginKeyManager 同口径）。
     */
    @Bean
    @ConditionalOnMissingBean(com.sw.ck.common.crypto.AesGcmCipher.class)
    public com.sw.ck.common.crypto.AesGcmCipher ssoCipher(
            @org.springframework.beans.factory.annotation.Value("${sw.security.sso.cipher-key:}") String cipherKey) {
        if (cipherKey == null || cipherKey.isBlank()) {
            throw new IllegalStateException(
                    "SSO 凭据加密密钥未配置：必须经外部安全配置注入 sw.security.sso.cipher-key"
                            + "（如环境变量 SW_SSO_CIPHER_KEY），明文凭据不允许落库");
        }
        return new com.sw.ck.common.crypto.AesGcmCipher(cipherKey);
    }

    /**
     * SSO Provider 客户端注册（I5）：三 Provider 各自独立实现，Map 分发。
     */
    @Bean
    public com.sw.ck.system.sso.SsoAuthService ssoAuthService(
            com.sw.ck.system.mapper.SsoProviderConfigMapper configMapper,
            com.sw.ck.system.mapper.SsoUserBindingMapper bindingMapper,
            com.sw.ck.system.mapper.SsoAuthStateMapper stateMapper,
            com.sw.ck.system.mapper.SsoAuditRecordMapper auditMapper,
            com.sw.ck.system.service.SysUserService sysUserService,
            com.sw.ck.common.crypto.AesGcmCipher ssoCipher,
            com.sw.ck.system.sso.SsoCallbackPolicy ssoCallbackPolicy,
            com.sw.ck.system.service.TenantValidityService tenantValidityService) {
        return new com.sw.ck.system.sso.SsoAuthService(configMapper, bindingMapper, stateMapper,
                auditMapper, sysUserService,
                java.util.List.of(
                        new com.sw.ck.system.sso.WecomSsoProviderClient(),
                        new com.sw.ck.system.sso.FeishuSsoProviderClient(),
                        new com.sw.ck.system.sso.DingtalkSsoProviderClient()),
                ssoCipher, ssoCallbackPolicy, tenantValidityService);
    }
}
