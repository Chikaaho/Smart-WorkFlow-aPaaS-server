package com.sw.ck.system.sso;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SsoProviderConfig;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.mapper.SsoProviderConfigMapper;
import com.sw.ck.system.vo.SsoConfigCheckVO;
import com.sw.ck.system.vo.SsoConfigItemVO;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 租户级 SSO Provider 配置管理：加载/保存/启停/凭据更新/检查、配置指纹与解密
 * 视图。自 SsoAuthService 拆出（行为不变）；事务边界仍由门面
 * {@code SsoAuthService} 的 {@code @Transactional} 方法承担。
 */
@Service
class SsoProviderConfigService {

    @Autowired
    private SsoProviderConfigMapper configMapper;
    @Autowired
    private SsoCredentialCipher cipher;
    @Autowired
    private SsoCallbackPolicy callbackPolicy;
    @Autowired
    private SsoAuditRecorder auditRecorder;

    /**
     * 进程启动时复核已启用 Provider 的配置完整性。
     * <p>
     * 配置保存入口的校验只能保护当前写入请求；如果数据库中残留空值、占位值或
     * 无法解密的密文，必须在 prod 进程启动阶段 fail-fast，不能等到第一次登录
     * 才把错误暴露为 Provider 外呼失败。停用 Provider 明确跳过该门禁，确保可
     * 通过先停用再修复凭据的安全下线路径。
     * </p>
     */
    @PostConstruct
    void validateEnabledProviderConfigsAtStartup() {
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            List<SsoProviderConfig> enabledConfigs = configMapper.selectList(
                    Wrappers.<SsoProviderConfig>lambdaQuery()
                            .eq(SsoProviderConfig::getEnabled, 1));
            for (SsoProviderConfig config : enabledConfigs) {
                SsoAuthService.requireProvider(config.getProvider());
                validateEnabledConfig(config.getAppId(), null, config);
            }
        }
    }

    // ==================== 配置管理 ====================

    /**
     * 保存（或更新）租户级 Provider 配置；secret 明文仅在本方法内加密，不落日志。
     */
    void saveConfig(String provider, boolean enabled, String appId,
                    String appSecret, String extraConfig, String redirectPath) {
        SsoAuthService.requireProvider(provider);
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失，不能保存 SSO 配置");
        }
        SsoProviderConfig config = configMapper.selectOne(
                Wrappers.<SsoProviderConfig>lambdaQuery()
                        .eq(SsoProviderConfig::getProvider, provider));
        // 应用归属唯一（I5 复验 G6）：同一 (provider, appId) 只允许一个租户登记，
        // 否则同一 Provider 应用/组织中的稳定外部主体可被两个租户各绑定一次
        boolean updatingSameRow = config != null && config.getTenantId() != null
                && config.getTenantId().equals(current.getTenantId());
        if (appId != null && !appId.isBlank()) {
            try (Suspended ignored =
                         TenantLineSuspension.suspended()) {
                Long conflicts = configMapper.selectCount(
                        Wrappers.<SsoProviderConfig>lambdaQuery()
                                .eq(SsoProviderConfig::getProvider, provider)
                                .eq(SsoProviderConfig::getAppId, appId)
                                .ne(updatingSameRow, SsoProviderConfig::getTenantId, current.getTenantId()));
                if (conflicts != null && conflicts > 0) {
                    auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFLICT_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), current.getUserId(), null, null,
                            "app already registered by another tenant", current.getTenantId());
                    throw new IllegalStateException("该 Provider 应用已登记在其他租户，不能跨租户重复登记");
                }
            }
        }
        if (enabled) {
            validateEnabledConfig(appId, appSecret, config);
        }
        if (config == null) {
            config = new SsoProviderConfig();
            config.setProvider(provider);
            config.setTenantId(current.getTenantId());
        }
        config.setEnabled(enabled ? 1 : 0);
        config.setAppId(appId);
        if (appSecret != null && !appSecret.isBlank()) {
            config.setAppSecretEnc(cipher.encrypt(appSecret));
        }
        config.setExtraConfig(extraConfig);
        config.setRedirectPath(redirectPath);
        if (config.getId() == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        auditRecorder.audit(provider, "CONFIG_CHANGE", SysAuditLog.RESULT_SUCCESS, current.getUserId(), null, null,
                "enabled=" + enabled);
    }

    /**
     * 查询租户配置（secret 不回传，只回是否已配置）。
     */
    Map<String, Object> getConfig(String provider) {
        SsoAuthService.requireProvider(provider);
        SsoProviderConfig config = loadEnabledConfig(provider);
        return Map.of(
                "provider", provider,
                "enabled", config != null && config.getEnabled() == 1,
                "appId", config == null ? "" : config.getAppId(),
                "secretConfigured", config != null && config.getAppSecretEnc() != null
                        && !config.getAppSecretEnc().isBlank());
    }

    // ==================== 后台配置管理（sso-admin-config；粒度权限由控制器守卫） ====================

    /** 企业微信 Owner 延期（sso-admin-config §二）：保留既有配置记录只读，不提供变更入口。 */
    private void requireMutableProvider(String provider) {
        if (SsoProviderEnum.WECOM.name().equals(provider)) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFIG_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), currentUserId(), null, null,
                    "wecom deferred read-only", currentTenantId());
            throw new IllegalStateException("企业微信配置维护已延期，当前只读");
        }
    }

    /**
     * 租户级配置列表（管理端）：平台/启用/应用标识/身份模式/允许企业标识/凭据是否
     * 已配置/服务端推导只读回调地址/更新时间。secret 原文与密文不回传。
     */
    List<SsoConfigItemVO> listConfigs() {
        Long tenantId = requireTenantContext();
        List<SsoConfigItemVO> rows = new ArrayList<>();
        for (String provider : SsoAuthService.PROVIDERS.stream().sorted().toList()) {
            SsoProviderConfig config = loadEnabledConfigGlobal(provider, tenantId);
            String enterpriseId = config == null ? null : configuredEnterpriseId(config);
            SsoConfigItemVO row = new SsoConfigItemVO();
            row.setProvider(provider);
            row.setEnabled(config != null && config.getEnabled() == 1);
            row.setAppId(config == null ? "" : nullToEmpty(config.getAppId()));
            row.setSecretConfigured(config != null && config.getAppSecretEnc() != null
                    && !config.getAppSecretEnc().isBlank());
            row.setIdentityMode(enterpriseId == null || enterpriseId.isBlank()
                    ? "personal" : "enterprise");
            row.setConfiguredEnterpriseId(enterpriseId == null ? "" : enterpriseId);
            row.setCallbackUrl(callbackPolicy.resolveCallbackUrl(provider));
            row.setUpdateTime(config == null ? "" : String.valueOf(config.getUpdateTime()));
            row.setDeferred(SsoProviderEnum.WECOM.name().equals(provider));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 更新基本信息（应用标识＋身份模式/允许企业标识）；启用状态下校验凭据完整性。
     * secret 不在本入口（凭据更新单独权限）。
     */
    void updateConfigBasic(String provider, String appId, String extraConfig) {
        SsoAuthService.requireProvider(provider);
        requireMutableProvider(provider);
        LoginUser current = requireLoginContext();
        Long tenantId = current.getTenantId();
        SsoProviderConfig config = loadEnabledConfigGlobal(provider, tenantId);
        checkAppIdConflict(provider, appId, tenantId,
                config != null && tenantId.equals(config.getTenantId()));
        // 已启用行的基本信息变更必须保持凭据完整（防止改坏 appId 后重启 fail-fast）；
        // 新建/停用行允许先落基本信息，凭据经独立入口更新、启用时再校验
        if (config != null && config.getEnabled() == 1) {
            validateEnabledConfig(appId, null, config);
        }
        validateEnterpriseConfig(extraConfig);
        if (config == null) {
            config = new SsoProviderConfig();
            config.setProvider(provider);
            config.setTenantId(tenantId);
            config.setEnabled(0);
        }
        config.setAppId(appId);
        config.setExtraConfig(extraConfig);
        if (config.getId() == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        auditRecorder.audit(provider, "CONFIG_SAVE", SysAuditLog.RESULT_SUCCESS, current.getUserId(), null, null,
                "basic fields updated", tenantId);
    }

    /**
     * 启停：保存后对后续授权生效（配置每次直查库）；启用复用启动期完整性校验，
     * 避免保存成功但重启 fail-fast。企业微信不允许启用（延期只读），停用允许。
     */
    void updateEnabled(String provider, boolean enabled) {
        SsoAuthService.requireProvider(provider);
        if (enabled) {
            requireMutableProvider(provider);
        }
        LoginUser current = requireLoginContext();
        Long tenantId = current.getTenantId();
        SsoProviderConfig config = loadEnabledConfigGlobal(provider, tenantId);
        if (config == null) {
            auditRecorder.auditDenial(provider, enabled ? SsoAuditActionEnum.CONFIG_ENABLE.getCode() : SsoAuditActionEnum.CONFIG_DISABLE.getCode(), SsoAuditResultEnum.DENIED.getCode(),
                    current.getUserId(), null, null, "config not found", tenantId);
            throw new IllegalStateException("该平台尚未登记配置");
        }
        if (enabled) {
            validateEnabledConfig(config.getAppId(), null, config);
        }
        config.setEnabled(enabled ? 1 : 0);
        configMapper.updateById(config);
        auditRecorder.audit(provider, enabled ? "CONFIG_ENABLE" : "CONFIG_DISABLE", SysAuditLog.RESULT_SUCCESS,
                current.getUserId(), null, null, null, tenantId);
    }

    /**
     * 应用凭据更新（secret 只写）：仅显式提供的新 secret；明文只在当前调用栈内
     * 加密，不落日志/审计/响应；掩码或占位值拒绝。与系统加密主密钥轮换无关。
     */
    void updateSecret(String provider, String appSecret) {
        SsoAuthService.requireProvider(provider);
        requireMutableProvider(provider);
        LoginUser current = requireLoginContext();
        Long tenantId = current.getTenantId();
        if (appSecret == null || appSecret.isBlank() || isPlaceholder(appSecret)) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFIG_SECRET_UPDATE.getCode(), SsoAuditResultEnum.DENIED.getCode(), current.getUserId(), null, null,
                    appSecret == null || appSecret.isBlank() ? "empty secret rejected" : "placeholder secret rejected",
                    tenantId);
            throw new IllegalStateException("请输入有效的新应用凭据（留空表示不更新）");
        }
        SsoProviderConfig config = loadEnabledConfigGlobal(provider, tenantId);
        if (config == null) {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFIG_SECRET_UPDATE.getCode(), SsoAuditResultEnum.DENIED.getCode(), current.getUserId(), null, null,
                    "config not found", tenantId);
            throw new IllegalStateException("该平台尚未登记配置");
        }
        config.setAppSecretEnc(cipher.encrypt(appSecret));
        configMapper.updateById(config);
        auditRecorder.audit(provider, "CONFIG_SECRET_UPDATE", SysAuditLog.RESULT_SUCCESS, current.getUserId(), null, null,
                "secret rotated", tenantId);
    }

    /**
     * 配置检查（只读）：完整性（appId 非占位）、凭据可解密且非占位、企业标识格式、
     * 启用状态；不发起用户授权、不修改配置或绑定、不宣称真实登录通过。
     * 检查失败项名称进审计与响应，secret 值不出现。
     */
    SsoConfigCheckVO checkConfig(String provider) {
        SsoAuthService.requireProvider(provider);
        LoginUser current = requireLoginContext();
        Long tenantId = current.getTenantId();
        SsoProviderConfig config = loadEnabledConfigGlobal(provider, tenantId);
        SsoConfigCheckVO checks = new SsoConfigCheckVO();
        checks.setConfigExists(config != null);
        boolean appIdOk = config != null && config.getAppId() != null && !isPlaceholder(config.getAppId());
        checks.setAppIdValid(appIdOk);
        boolean secretOk = false;
        if (config != null && config.getAppSecretEnc() != null && !config.getAppSecretEnc().isBlank()) {
            secretOk = cipher.decryptable(config.getAppSecretEnc())
                    && !isPlaceholder(tryDecrypt(config.getAppSecretEnc()));
        }
        checks.setSecretUsable(secretOk);
        String enterpriseId = config == null ? null : configuredEnterpriseId(config);
        checks.setEnterpriseIdFormat(enterpriseId == null || !enterpriseId.isBlank());
        boolean enabled = config != null && config.getEnabled() == 1;
        checks.setEnabled(enabled);
        boolean overall = config != null && appIdOk && secretOk && enabled;
        checks.setOverall(overall);
        checks.setSummary(overall
                ? "配置完整且已启用（不等于真实登录已通过）"
                : "配置不完整或未启用，请核对检查项");
        List<String> failed = new ArrayList<>();
        if (!checks.isConfigExists()) failed.add("configExists");
        if (!checks.isAppIdValid()) failed.add("appIdValid");
        if (!checks.isSecretUsable()) failed.add("secretUsable");
        if (!checks.isEnterpriseIdFormat()) failed.add("enterpriseIdFormat");
        if (!checks.isEnabled()) failed.add("enabled");
        if (overall) {
            auditRecorder.audit(provider, "CONFIG_CHECK", SysAuditLog.RESULT_SUCCESS, current.getUserId(), null, null, null, tenantId);
        } else {
            auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFIG_CHECK.getCode(), SsoAuditResultEnum.FAILED.getCode(), current.getUserId(), null, null,
                    "failed items: " + String.join(",", failed), tenantId);
        }
        return checks;
    }

    private void checkAppIdConflict(String provider, String appId, Long tenantId, boolean updatingSameRow) {
        if (appId == null || appId.isBlank()) {
            return;
        }
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            Long conflicts = configMapper.selectCount(
                    Wrappers.<SsoProviderConfig>lambdaQuery()
                            .eq(SsoProviderConfig::getProvider, provider)
                            .eq(SsoProviderConfig::getAppId, appId)
                            .ne(updatingSameRow, SsoProviderConfig::getTenantId, tenantId));
            if (conflicts != null && conflicts > 0) {
                auditRecorder.auditDenial(provider, SsoAuditActionEnum.CONFLICT_REJECTED.getCode(), SsoAuditResultEnum.DENIED.getCode(), currentUserId(), null, null,
                        "app already registered by another tenant", tenantId);
                throw new IllegalStateException("该 Provider 应用已登记在其他租户，不能跨租户重复登记");
            }
        }
    }

    /** 企业模式必填允许企业标识（fail closed）；个人模式 extra 允许为空对象。 */
    private void validateEnterpriseConfig(String extraConfig) {
        // 身份模式由 extra_config.enterpriseId 承载：非空即企业模式（值不能为空白）
        String enterpriseId = parseExtraEnterpriseId(extraConfig);
        if (enterpriseId != null && enterpriseId.isBlank()) {
            throw new IllegalStateException("企业成员模式必须填写允许企业标识");
        }
    }

    private String parseExtraEnterpriseId(String extraConfig) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper()
                    .readTree(extraConfig).path("enterpriseId").asText(null);
        } catch (Exception e) {
            throw new IllegalStateException("扩展配置必须是合法 JSON");
        }
    }

    private String tryDecrypt(String ciphertext) {
        try {
            return cipher.decrypt(ciphertext);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private Long requireTenantContext() {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失");
        }
        return current.getTenantId();
    }

    private LoginUser requireLoginContext() {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("登录状态已失效，请重新登录");
        }
        return current;
    }

    private Long currentUserId() {
        LoginUser current = LoginUserHolder.get();
        return current == null ? null : current.getUserId();
    }

    private Long currentTenantId() {
        LoginUser current = LoginUserHolder.get();
        return current == null ? null : current.getTenantId();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ==================== 配置读取 / 指纹 / 解密视图 ====================

    SsoProviderConfig loadEnabledConfig(String provider) {
        LoginUser current = LoginUserHolder.get();
        Long tenantId = current != null ? current.getTenantId() : null;
        return loadEnabledConfigGlobal(provider, tenantId);
    }

    SsoProviderConfig loadEnabledConfigGlobal(String provider, Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        // 认证前路径：显式按租户列过滤（不走拦截器兜底语义）
        return configMapper.selectOne(
                Wrappers.<SsoProviderConfig>lambdaQuery()
                        .eq(SsoProviderConfig::getProvider, provider)
                        .eq(SsoProviderConfig::getTenantId, tenantId));
    }

    /**
     * A4 配置期间授权语义：未兑换票据的新会话签发受签发时刻配置指纹约束——
     * 停用或 appId/secret/身份模式/企业标识变化后，票据兑换一律拒绝（新会话
     * 不得以旧配置建立；重新发起授权即可恢复）。返回当前配置指纹；配置缺失、
     * 停用或解密失败返回 null，由调用方拒绝并审计。
     */
    String currentConfigDigestFor(String provider, Long tenantId) {
        SsoAuthService.requireProvider(provider);
        SsoProviderConfig config;
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            config = loadEnabledConfigGlobal(provider, tenantId);
        }
        if (config == null || config.getEnabled() != 1) {
            return null;
        }
        return configFingerprint(provider, config);
    }

    /**
     * A4 配置生命周期绑定：当前配置指纹（SHA-256）。材料=provider|appId|解密
     * secret|企业标识（个人模式空串）|启停；secret 以解密值参与摘要（密文含随机
     * IV，不可作指纹材料）。解密失败按占位标记参与摘要——与换票必然失败同口径
     * fail closed（旧指纹必不匹配）。摘要不进日志/审计/响应。
     */
    String configFingerprint(String provider, SsoProviderConfig config) {
        String secret;
        try {
            secret = cipher.decrypt(config.getAppSecretEnc());
        } catch (RuntimeException e) {
            secret = "<undecryptable>";
        }
        return SsoAuthService.fingerprintOf(provider, config.getAppId(), secret,
                configuredEnterpriseId(config), config.getEnabled());
    }

    SsoProviderClient.SsoProviderConfigView decryptConfig(SsoProviderConfig config) {
        String secret = cipher.decrypt(config.getAppSecretEnc());
        return new SsoProviderClient.SsoProviderConfigView(
                config.getAppId(), secret,
                WecomSsoProviderClient.parseExtra(config.getExtraConfig()));
    }

    /**
     * 启用 Provider 时拒绝空值和常见占位凭据；停用配置不要求凭据，便于安全下线。
     * 已有有效密文可在更新非凭据字段时保留，明文只在当前调用栈内短暂出现。
     */
    private void validateEnabledConfig(String appId, String appSecret, SsoProviderConfig existing) {
        if (isPlaceholder(appId)) {
            throw new IllegalStateException("启用 Provider 必须配置有效 appId 与 secret");
        }
        if (appSecret != null && !appSecret.isBlank()) {
            if (isPlaceholder(appSecret)) {
                throw new IllegalStateException("启用 Provider 必须配置有效 appId 与 secret");
            }
            return;
        }
        if (existing == null || existing.getAppSecretEnc() == null
                || existing.getAppSecretEnc().isBlank()) {
            throw new IllegalStateException("启用 Provider 必须配置有效 appId 与 secret");
        }
        try {
            if (isPlaceholder(cipher.decrypt(existing.getAppSecretEnc()))) {
                throw new IllegalStateException("启用 Provider 必须配置有效 appId 与 secret");
            }
        } catch (IllegalStateException e) {
            throw new IllegalStateException("启用 Provider 必须配置有效 appId 与 secret");
        }
    }

    private boolean isPlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "placeholder".equals(normalized)
                || "changeme".equals(normalized)
                || "change-me".equals(normalized)
                || "example".equals(normalized)
                || "secret".equals(normalized)
                || "dummy".equals(normalized)
                || "test".equals(normalized)
                || normalized.startsWith("your-")
                || normalized.startsWith("your_")
                || normalized.startsWith("replace-me")
                || normalized.startsWith("replace_with")
                || normalized.startsWith("replacewith")
                || normalized.startsWith("example-")
                || normalized.startsWith("test-")
                || normalized.startsWith("test_")
                || (normalized.contains("${") && normalized.contains("}"))
                || (normalized.startsWith("<") && normalized.endsWith(">"));
    }

    /** G3b：extra_config JSON 的 enterpriseId=租户允许的企业标识；缺省=个人模式。 */
    String configuredEnterpriseId(SsoProviderConfig config) {
        String extra = config.getExtraConfig();
        if (extra == null || extra.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper()
                    .readTree(extra).path("enterpriseId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
