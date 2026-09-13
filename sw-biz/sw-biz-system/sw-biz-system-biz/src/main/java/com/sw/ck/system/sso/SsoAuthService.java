package com.sw.ck.system.sso;

import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SsoAuditRecord;
import com.sw.ck.system.entity.SsoAuthState;
import com.sw.ck.system.entity.SsoProviderConfig;
import com.sw.ck.system.entity.SsoUserBinding;
import com.sw.ck.system.mapper.SsoAuditRecordMapper;
import com.sw.ck.system.mapper.SsoAuthStateMapper;
import com.sw.ck.system.mapper.SsoProviderConfigMapper;
import com.sw.ck.system.mapper.SsoUserBindingMapper;
import com.sw.ck.system.service.SysUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第三方 SSO 核心服务（I5）。
 * <p>
 * 职责：租户级 Provider 配置管理（凭据 AES-GCM 加密落库）、授权发起（一次性
 * state 签发）、回调换票（state 原子消费 + code 换外部身份）、身份绑定/解绑
 * （唯一约束 + 冲突拒绝）、已绑定登录（定位本地账号后由既有服务端权威装载）。
 * </p>
 * <p>
 * 红线：外部登录只负责定位本地账号，最终 LoginUser/角色/权限/数据范围由
 * {@code UserDetailsProvider} 服务端装载；绑定不写 sys_user_role、不直接授权；
 * code/token/secret 不进日志/审计正文/响应。
 * </p>
 */
@Service
public class SsoAuthService {

    private static final Logger log = LoggerFactory.getLogger(SsoAuthService.class);

    /** 合法 Provider 集合 */
    public static final Set<String> PROVIDERS = Set.of("WECOM", "FEISHU", "DINGTALK");

    /** state 有效期（秒）：一次性、限时 */
    static final long STATE_TTL_SECONDS = 300;

    private final SsoProviderConfigMapper configMapper;
    private final SsoUserBindingMapper bindingMapper;
    private final SsoAuthStateMapper stateMapper;
    private final SsoAuditRecordMapper auditMapper;
    private final SysUserService sysUserService;
    private final Map<String, SsoProviderClient> clients;
    private final AesGcmCipher cipher;
    private final SsoCallbackPolicy callbackPolicy;
    private final com.sw.ck.system.service.TenantValidityService tenantValidityService;
    private final SecureRandom secureRandom = new SecureRandom();

    public SsoAuthService(SsoProviderConfigMapper configMapper,
                          SsoUserBindingMapper bindingMapper,
                          SsoAuthStateMapper stateMapper,
                          SsoAuditRecordMapper auditMapper,
                          SysUserService sysUserService,
                          List<SsoProviderClient> clientList,
                          AesGcmCipher cipher,
                          SsoCallbackPolicy callbackPolicy,
                          com.sw.ck.system.service.TenantValidityService tenantValidityService) {
        this.configMapper = configMapper;
        this.bindingMapper = bindingMapper;
        this.stateMapper = stateMapper;
        this.auditMapper = auditMapper;
        this.sysUserService = sysUserService;
        this.clients = new ConcurrentHashMap<>();
        for (SsoProviderClient client : clientList) {
            this.clients.put(client.provider(), client);
        }
        this.cipher = cipher;
        this.callbackPolicy = callbackPolicy;
        this.tenantValidityService = tenantValidityService;
    }

    // ==================== 配置管理 ====================

    /**
     * 保存（或更新）租户级 Provider 配置；secret 明文仅在本方法内加密，不落日志。
     */
    @Transactional
    public void saveConfig(String provider, boolean enabled, String appId,
                           String appSecret, String extraConfig, String redirectPath) {
        requireProvider(provider);
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失，不能保存 SSO 配置");
        }
        SsoProviderConfig config = configMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SsoProviderConfig>lambdaQuery()
                        .eq(SsoProviderConfig::getProvider, provider));
        // 应用归属唯一（I5 复验 G6）：同一 (provider, appId) 只允许一个租户登记，
        // 否则同一 Provider 应用/组织中的稳定外部主体可被两个租户各绑定一次
        boolean updatingSameRow = config != null && config.getTenantId() != null
                && config.getTenantId().equals(current.getTenantId());
        if (appId != null && !appId.isBlank()) {
            try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                         com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
                Long conflicts = configMapper.selectCount(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<SsoProviderConfig>lambdaQuery()
                                .eq(SsoProviderConfig::getProvider, provider)
                                .eq(SsoProviderConfig::getAppId, appId)
                                .ne(updatingSameRow, SsoProviderConfig::getTenantId, current.getTenantId()));
                if (conflicts != null && conflicts > 0) {
                    audit(provider, "CONFLICT_REJECTED", "DENIED", current.getUserId(), null, null,
                            "app already registered by another tenant", current.getTenantId());
                    throw new IllegalStateException("该 Provider 应用已登记在其他租户，不能跨租户重复登记");
                }
            }
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
        audit(provider, "CONFIG_CHANGE", "SUCCESS", current.getUserId(), null, null,
                "enabled=" + enabled);
    }

    /**
     * 查询租户配置（secret 不回传，只回是否已配置）。
     */
    public Map<String, Object> getConfig(String provider) {
        requireProvider(provider);
        SsoProviderConfig config = loadEnabledConfig(provider);
        return Map.of(
                "provider", provider,
                "enabled", config != null && config.getEnabled() == 1,
                "appId", config == null ? "" : config.getAppId(),
                "secretConfigured", config != null && config.getAppSecretEnc() != null
                        && !config.getAppSecretEnc().isBlank());
    }

    /**
     * 查询本地账号的全部有效绑定（个人中心展示；不回传外部原文，只回 Provider 与摘要前 8 位）。
     */
    public Map<String, Object> listBindings(Long userId) {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失");
        }
        var bindings = bindingMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SsoUserBinding>lambdaQuery()
                        .eq(SsoUserBinding::getUserId, userId)
                        .eq(SsoUserBinding::getBindStatus, "ACTIVE"));
        var view = bindings.stream()
                .map(b -> Map.of(
                        "provider", b.getProvider(),
                        "externalDigestPrefix", b.getExternalDigest().substring(0, 8)))
                .toList();
        return Map.of("bindings", view);
    }

    // ==================== 授权发起 ====================

    /**
     * 服务端授权发起：签发一次性 state 并构造 Provider 授权 URL。
     * state 与租户/Provider/发起会话绑定（当前登录用户可为 null——登录页发起）。
     */
    @Transactional
    public AuthorizeStart startAuthorize(String provider, String redirectPath) {
        requireProvider(provider);
        SsoProviderConfig config = loadEnabledConfig(provider);
        if (config == null || config.getEnabled() != 1) {
            throw new IllegalStateException("该 Provider 未启用: " + provider);
        }
        LoginUser current = LoginUserHolder.get();
        Long tenantId = current != null ? current.getTenantId() : null;
        if (tenantId == null) {
            // 登录前发起必须带租户上下文（租户级配置决定 Provider 凭据与绑定域）
            throw new IllegalStateException("租户上下文缺失，不能发起 SSO 授权");
        }
        String state = randomToken(32);
        SsoAuthState stateRow = new SsoAuthState();
        stateRow.setStateValue(sha256(state));
        stateRow.setProvider(provider);
        stateRow.setRedirectPath(sanitizeRedirect(redirectPath));
        stateRow.setConsumed(0);
        stateRow.setExpireAt(LocalDateTime.now().plusSeconds(STATE_TTL_SECONDS));
        stateRow.setTenantId(tenantId);
        stateMapper.insert(stateRow);

        SsoProviderClient client = clients.get(provider);
        String authorizeUrl = client.buildAuthorizeUrl(
                decryptConfig(config), callbackPolicy.resolveCallbackUrl(provider), state);
        audit(provider, "AUTH_START", "SUCCESS", current == null ? null : current.getUserId(),
                null, null, null);
        return new AuthorizeStart(authorizeUrl, state);
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
    @Transactional
    public AuthorizeStart startAuthorizeLogin(String provider, Long tenantId, String redirectPath) {
        requireProvider(provider);
        if (tenantId == null) {
            throw new IllegalStateException("登录前发起必须显式指定租户");
        }
        // 免认证路径无登录态：租户语义全部由显式谓词承担（state.tenantId、配置行
        // tenant_id、绑定行 tenant_id），挂起拦截器避免 fail-closed 误伤
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            tenantValidityService.requireValid(tenantId);
            SsoProviderConfig config = loadEnabledConfigGlobal(provider, tenantId);
            if (config == null || config.getEnabled() != 1) {
                throw new IllegalStateException("该 Provider 未启用: " + provider);
            }
            String state = randomToken(32);
            SsoAuthState stateRow = new SsoAuthState();
            stateRow.setStateValue(sha256(state));
            stateRow.setProvider(provider);
            stateRow.setRedirectPath(sanitizeRedirect(redirectPath));
            stateRow.setConsumed(0);
            stateRow.setExpireAt(LocalDateTime.now().plusSeconds(STATE_TTL_SECONDS));
            stateRow.setTenantId(tenantId);
            stateMapper.insert(stateRow);

            SsoProviderClient client = clients.get(provider);
            String authorizeUrl = client.buildAuthorizeUrl(
                    decryptConfig(config), callbackPolicy.resolveCallbackUrl(provider), state);
            audit(provider, "AUTH_START", "SUCCESS", null, null, null, "pre-login tenant=" + tenantId, tenantId);
            return new AuthorizeStart(authorizeUrl, state);
        }
    }

    /**
     * 回调换票 + 已绑定登录定位。
     * <p>
     * state 校验：存在、未过期、未消费、Provider/租户匹配；原子消费后用一次性
     * code 换外部稳定标识；已绑定 → 返回本地 userId 进入既有登录链；未绑定 →
     * 返回绑定候选（挂起会话由调用方在受控事务内完成绑定）。
     * </p>
     */
    @Transactional
    public CallbackResult handleCallback(String provider, String code, String state) {
        requireProvider(provider);
        // 免认证回调：与 startAuthorizeLogin 同口径挂起租户拦截器（显式谓词承担租户语义）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return doHandleCallback(provider, code, state);
        }
    }

    private CallbackResult doHandleCallback(String provider, String code, String state) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            audit(provider, "LOGIN_FAILED", "DENIED", null, null, null, "missing code/state");
            throw new IllegalStateException("回调参数缺失");
        }
        String stateDigest = sha256(state);
        SsoAuthState stateRow = stateMapper.selectGlobalByState(stateDigest);
        if (stateRow == null) {
            audit(provider, "REPLAY_REJECTED", "DENIED", null, null, null, "state not found");
            throw new IllegalStateException("授权状态无效");
        }
        if (stateRow.getConsumed() != null && stateRow.getConsumed() == 1) {
            audit(provider, "REPLAY_REJECTED", "DENIED", null, null, null, "state replayed");
            throw new IllegalStateException("授权状态已消费");
        }
        if (stateRow.getExpireAt().isBefore(LocalDateTime.now())) {
            audit(provider, "LOGIN_FAILED", "DENIED", null, null, null, "state expired");
            throw new IllegalStateException("授权状态已过期");
        }
        if (!provider.equals(stateRow.getProvider())) {
            audit(provider, "LOGIN_FAILED", "DENIED", null, null, null, "provider mismatch");
            throw new IllegalStateException("Provider 与授权状态不匹配");
        }
        // 原子消费：仅未消费状态可置 1（并发重放只有一个成功）
        int consumed = stateMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SsoAuthState>lambdaUpdate()
                        .eq(SsoAuthState::getId, stateRow.getId())
                        .eq(SsoAuthState::getConsumed, 0)
                        .set(SsoAuthState::getConsumed, 1));
        if (consumed != 1) {
            audit(provider, "REPLAY_REJECTED", "DENIED", null, null, null, "state concurrent replay");
            throw new IllegalStateException("授权状态已消费");
        }

        SsoProviderConfig config = loadEnabledConfigGlobal(provider, stateRow.getTenantId());
        if (config == null || config.getEnabled() != 1) {
            audit(provider, "LOGIN_FAILED", "DENIED", null, null, null, "provider disabled");
            throw new IllegalStateException("该 Provider 未启用");
        }
        String externalId;
        try {
            externalId = clients.get(provider).exchangeExternalId(decryptConfig(config), code);
        } catch (SsoProviderClient.SsoProviderException e) {
            audit(provider, "LOGIN_FAILED", "FAILED", null, null, sha256(externalFingerprint(e)),
                    "provider exchange failed");
            throw e;
        }
        String externalDigest = sha256(externalId);
        SsoUserBinding binding = bindingMapper.selectActiveByExternal(provider, stateRow.getTenantId(), externalId);
        if (binding != null) {
            audit(provider, "LOGIN_SUCCESS", "SUCCESS", null, binding.getUserId(), externalDigest,
                    null, stateRow.getTenantId());
            return new CallbackResult(provider, stateRow.getTenantId(), externalId, externalDigest,
                    binding.getUserId(), stateRow.getRedirectPath(), true);
        }
        audit(provider, "LOGIN_FAILED", "DENIED", null, null, externalDigest,
                "not bound", stateRow.getTenantId());
        return new CallbackResult(provider, stateRow.getTenantId(), externalId, externalDigest,
                null, stateRow.getRedirectPath(), false);
    }

    // ==================== 绑定 / 解绑 ====================

    /**
     * 绑定外部身份到本地账号（必须已认证；同租户内冲突/重复绑定显式拒绝）。
     */
    @Transactional
    public void bind(String provider, Long userId, String externalId) {
        requireProvider(provider);
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失，不能绑定");
        }
        Long tenantId = current.getTenantId();
        String externalDigest = sha256(externalId);
        SsoUserBinding existingExternal = bindingMapper.selectActiveByExternal(provider, tenantId, externalId);
        if (existingExternal != null) {
            audit(provider, "CONFLICT_REJECTED", "DENIED", current.getUserId(), existingExternal.getUserId(),
                    externalDigest, "external id already bound");
            throw new IllegalStateException("该外部身份已绑定其他本地账号");
        }
        SsoUserBinding existingUser = bindingMapper.selectActiveByUser(provider, tenantId, userId);
        if (existingUser != null) {
            audit(provider, "CONFLICT_REJECTED", "DENIED", current.getUserId(), userId,
                    externalDigest, "user already bound to another external id");
            throw new IllegalStateException("该本地账号已绑定其他外部身份");
        }
        SsoUserBinding binding = new SsoUserBinding();
        binding.setProvider(provider);
        binding.setTenantId(tenantId);
        binding.setExternalId(externalId);
        binding.setExternalDigest(externalDigest);
        binding.setUserId(userId);
        binding.setBindStatus("ACTIVE");
        bindingMapper.insert(binding);
        audit(provider, "BIND", "SUCCESS", current.getUserId(), userId, externalDigest, null);
    }

    /**
     * 解绑：本地账号在当前租户内对指定 Provider 的有效绑定置 UNBOUND。
     */
    @Transactional
    public void unbind(String provider, Long userId) {
        requireProvider(provider);
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失，不能解绑");
        }
        SsoUserBinding binding = bindingMapper.selectActiveByUser(provider, current.getTenantId(), userId);
        if (binding == null) {
            throw new IllegalStateException("未找到有效绑定");
        }
        binding.setBindStatus("UNBOUND");
        bindingMapper.updateById(binding);
        audit(provider, "UNBIND", "SUCCESS", current.getUserId(), userId, binding.getExternalDigest(), null);
    }

    // ==================== 内部 ====================

    private SsoProviderConfig loadEnabledConfig(String provider) {
        LoginUser current = LoginUserHolder.get();
        Long tenantId = current != null ? current.getTenantId() : null;
        return loadEnabledConfigGlobal(provider, tenantId);
    }

    private SsoProviderConfig loadEnabledConfigGlobal(String provider, Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        // 认证前路径：显式按租户列过滤（不走拦截器兜底语义）
        return configMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SsoProviderConfig>lambdaQuery()
                        .eq(SsoProviderConfig::getProvider, provider)
                        .eq(SsoProviderConfig::getTenantId, tenantId));
    }

    private SsoProviderClient.SsoProviderConfigView decryptConfig(SsoProviderConfig config) {
        String secret = cipher.decrypt(config.getAppSecretEnc());
        return new SsoProviderClient.SsoProviderConfigView(
                config.getAppId(), secret,
                com.sw.ck.system.sso.WecomSsoProviderClient.parseExtra(config.getExtraConfig()));
    }

    private void audit(String provider, String eventType, String result, Long actorId,
                       Long localUserId, String externalDigest, String detail) {
        audit(provider, eventType, result, actorId, localUserId, externalDigest, detail, null);
    }

    /**
     * 审计写入。显式 tenantId 优先（回调/登录前发起路径无登录态，租户拦截器为
     * fail-closed，必须挂起过滤并显式携带 state 所属租户），否则取当前登录态租户。
     */
    private void audit(String provider, String eventType, String result, Long actorId,
                       Long localUserId, String externalDigest, String detail, Long explicitTenantId) {
        try {
            SsoAuditRecord record = new SsoAuditRecord();
            LoginUser current = LoginUserHolder.get();
            record.setTenantId(explicitTenantId != null ? explicitTenantId
                    : (current != null && current.getTenantId() != null ? current.getTenantId() : 0L));
            record.setProvider(provider);
            record.setEventType(eventType);
            record.setResult(result);
            record.setActorId(actorId != null ? actorId : (current != null ? current.getUserId() : null));
            record.setLocalUserId(localUserId);
            record.setExternalDigest(externalDigest);
            record.setDetail(detail);
            if (current == null) {
                try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                             com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
                    auditMapper.insert(record);
                }
            } else {
                auditMapper.insert(record);
            }
        } catch (Exception e) {
            log.warn("SSO 审计写入失败: provider={}, event={}", provider, eventType, e);
        }
    }

    private void requireProvider(String provider) {
        if (provider == null || !PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("未知 Provider: " + provider);
        }
    }

    private String serverCallbackUrl(String provider) {
        return callbackPolicy.resolveCallbackUrl(provider);
    }

    // ==================== 审计查询（I5 复验 G7） ====================

    /**
     * 按租户隔离查询 SSO 审计（调用方须经 {@code system:sso:audit:query} 权限守卫；
     * 无权/跨租户查询被拒绝由权限链与本查询的显式租户条件共同保证）。
     */
    public Map<String, Object> queryAudit(String provider, String eventType, String result,
                                          Long localUserId, int page, int size) {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.getTenantId() == null) {
            throw new IllegalStateException("租户上下文缺失，不能查询 SSO 审计");
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        var wrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.<SsoAuditRecord>lambdaQuery()
                .eq(SsoAuditRecord::getTenantId, current.getTenantId())
                .eq(provider != null && !provider.isBlank(), SsoAuditRecord::getProvider, provider)
                .eq(eventType != null && !eventType.isBlank(), SsoAuditRecord::getEventType, eventType)
                .eq(result != null && !result.isBlank(), SsoAuditRecord::getResult, result)
                .eq(localUserId != null, SsoAuditRecord::getLocalUserId, localUserId)
                .orderByDesc(SsoAuditRecord::getId)
                .last("LIMIT " + safeSize + " OFFSET " + (long) Math.max(page, 0) * safeSize);
        List<SsoAuditRecord> records = auditMapper.selectList(wrapper);
        var view = records.stream().map(r -> Map.of(
                "id", String.valueOf(r.getId()),
                "provider", r.getProvider(),
                "eventType", r.getEventType(),
                "result", r.getResult(),
                "actorId", String.valueOf(r.getActorId()),
                "localUserId", String.valueOf(r.getLocalUserId()),
                "externalDigestPrefix", r.getExternalDigest() == null ? "" : r.getExternalDigest().substring(0, Math.min(8, r.getExternalDigest().length())),
                "detail", r.getDetail() == null ? "" : r.getDetail(),
                "createTime", String.valueOf(r.getCreateTime())
        )).toList();
        return Map.of("records", view, "tenantId", String.valueOf(current.getTenantId()));
    }

    private String sanitizeRedirect(String redirectPath) {
        if (redirectPath == null || redirectPath.isBlank()) {
            return "/workspace";
        }
        // 仅站内相对路径；拒绝协议相对与外域（open-redirect 防护）
        if (!redirectPath.startsWith("/") || redirectPath.startsWith("//")) {
            return "/workspace";
        }
        return redirectPath;
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** 外部标识摘要前缀（审计/展示用；不回传原文）。 */
    public static String digestPrefix(String externalId) {
        return sha256Static(externalId).substring(0, 8);
    }

    private static String sha256Static(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String externalFingerprint(Exception e) {
        return e.getClass().getSimpleName() + ":" + e.getMessage();
    }

    /** 授权发起结果 */
    public record AuthorizeStart(String authorizeUrl, String state) {
    }

    /** 回调处理结果：bound=true 时 userId 为本地账号 */
    public record CallbackResult(String provider, Long tenantId, String externalId,
                                 String externalDigest, Long userId, String redirectPath,
                                 boolean bound) {
    }
}
