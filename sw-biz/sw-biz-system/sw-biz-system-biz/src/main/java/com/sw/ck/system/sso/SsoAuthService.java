package com.sw.ck.system.sso;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.vo.SsoAuditListVO;
import com.sw.ck.system.vo.SsoBindingListVO;
import com.sw.ck.system.vo.SsoConfigCheckVO;
import com.sw.ck.system.vo.SsoConfigItemVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 第三方 SSO 核心服务（I5）——门面。
 * <p>
 * 职责：租户级 Provider 配置管理（凭据 AES-GCM 加密落库）、授权发起（一次性
 * state 签发）、回调换票（state 原子消费 + code 换外部身份）、身份绑定/解绑
 * （唯一约束 + 冲突拒绝）、已绑定登录（定位本地账号后由既有服务端权威装载）。
 * </p>
 * <p>
 * 实现按职责拆分至同包协作类（行为与拆分前一致，公开契约不变）：
 * {@link SsoProviderConfigService}（配置管理/指纹/解密视图）、
 * {@link SsoTenantResolveService}（登录前租户解析）、
 * {@link SsoAuthStateService}（state 签发/校验/原子消费）、
 * {@link SsoAuthorizeStartService}（授权发起编排）、
 * {@link SsoCallbackProcessor}（回调换票编排）、
 * {@link SsoUserBindingService}（准入/绑定/解绑）、
 * {@link SsoAuditRecorder}（审计写入/查询）、{@link SsoProviderClients}
 * （Provider 客户端注册表）。{@code @Transactional} 事务边界保留在本门面的
 * 公开方法上（经 Spring 代理调用，语义与拆分前一致）。
 * </p>
 * <p>
 * 红线：外部登录只负责定位本地账号，最终 LoginUser/角色/权限/数据范围由
 * {@code UserDetailsProvider} 服务端装载；绑定不写 sys_user_role、不直接授权；
 * code/token/secret 不进日志/审计正文/响应。
 * </p>
 */
@Service
public class SsoAuthService {

    // ==================== 用户可见安全结论（P61 §3.6） ====================
    // 未认证的授权发起与回调一律使用同一结论，不区分 Provider 配置、回调白名单、
    // 授权状态原文、租户标识或第三方响应；精确原因进结构化日志与 SSO 审计。

    /** 第三方登录未完成：可重新发起或改用账号密码登录。 */
    static final String SSO_LOGIN_FAILED_MSG =
            "第三方登录未能完成，请返回登录页重新发起，或改用账号密码登录";

    /** B 端准入统一对外结论（sso-admin-config §一.4）：不区分具体原因，精确原因只进审计。 */
    static final String SSO_ADMISSION_REJECTED_MSG =
            "暂时无法使用第三方登录，请联系管理员核对账号信息";

    /** 登录前发起缺少企业标识：请求参数问题，可自行修正。 */
    static final String SSO_TENANT_REQUIRED_MSG =
            "请先选择要登录的企业，再重新发起第三方登录";

    /** 登录前发起按名称解析零命中（V012-BUG-019）。 */
    static final String SSO_TENANT_NOT_FOUND_MSG =
            "未找到名称完全一致的生效企业，请核对租户名称后重试";

    /** 登录前发起按名称解析多行命中：不任意选中，交由管理员处理。 */
    static final String SSO_TENANT_AMBIGUOUS_MSG =
            "该企业名称存在多个匹配，请联系管理员确认后重试";

    /** 登录页免填企业（B021-016）：按 Provider 反查零命中。 */
    static final String SSO_TENANT_PROVIDER_NONE_MSG =
            "尚未有企业启用该登录方式，请联系管理员或改用账号密码登录";

    /** 登录页免填企业（B021-016）：多企业启用同一 Provider，暂不自动识别。 */
    static final String SSO_TENANT_PROVIDER_AMBIGUOUS_MSG =
            "多个企业启用了该登录方式，暂时无法自动识别所属企业，请改用账号密码登录";

    /** 登录态缺失：需要重新登录。 */
    static final String SESSION_REQUIRED_MSG =
            "登录状态已失效，请重新登录";

    /** 合法 Provider 集合 */
    public static final Set<String> PROVIDERS = Set.of(SsoProviderEnum.WECOM.name(), SsoProviderEnum.FEISHU.name(), SsoProviderEnum.DINGTALK.name());

    @Autowired
    private SsoProviderConfigService configService;
    @Autowired
    private SsoTenantResolveService tenantResolveService;
    @Autowired
    private SsoAuthorizeStartService authorizeStartService;
    @Autowired
    private SsoCallbackProcessor callbackProcessor;
    @Autowired
    private SsoUserBindingService userBindingService;
    @Autowired
    private SsoAuditRecorder auditRecorder;

    // ==================== 配置管理 ====================

    /**
     * 保存（或更新）租户级 Provider 配置；secret 明文仅在本方法内加密，不落日志。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(String provider, boolean enabled, String appId,
                           String appSecret, String extraConfig, String redirectPath) {
        configService.saveConfig(provider, enabled, appId, appSecret, extraConfig, redirectPath);
    }

    /**
     * 查询租户配置（secret 不回传，只回是否已配置）。
     */
    public Map<String, Object> getConfig(String provider) {
        return configService.getConfig(provider);
    }

    // ==================== 后台配置管理（sso-admin-config；粒度权限由控制器守卫） ====================

    /**
     * 租户级配置列表（管理端）：平台/启用/应用标识/身份模式/允许企业标识/凭据是否
     * 已配置/服务端推导只读回调地址/更新时间。secret 原文与密文不回传。
     */
    public List<SsoConfigItemVO> listConfigs() {
        return configService.listConfigs();
    }

    /**
     * 更新基本信息（应用标识＋身份模式/允许企业标识）；启用状态下校验凭据完整性。
     * secret 不在本入口（凭据更新单独权限）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfigBasic(String provider, String appId, String extraConfig) {
        configService.updateConfigBasic(provider, appId, extraConfig);
    }

    /**
     * 启停：保存后对后续授权生效（配置每次直查库）；启用复用启动期完整性校验，
     * 避免保存成功但重启 fail-fast。企业微信不允许启用（延期只读），停用允许。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabled(String provider, boolean enabled) {
        configService.updateEnabled(provider, enabled);
    }

    /**
     * 应用凭据更新（secret 只写）：仅显式提供的新 secret；明文只在当前调用栈内
     * 加密，不落日志/审计/响应；掩码或占位值拒绝。与系统加密主密钥轮换无关。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSecret(String provider, String appSecret) {
        configService.updateSecret(provider, appSecret);
    }

    /**
     * 配置检查（只读）：完整性（appId 非占位）、凭据可解密且非占位、企业标识格式、
     * 启用状态；不发起用户授权、不修改配置或绑定、不宣称真实登录通过。
     * 检查失败项名称进审计与响应，secret 值不出现。
     */
    public SsoConfigCheckVO checkConfig(String provider) {
        return configService.checkConfig(provider);
    }

    /**
     * 查询本地账号的全部有效绑定（个人中心展示；不回传外部原文，只回 Provider 与摘要前 8 位）。
     */
    public SsoBindingListVO listBindings(Long userId) {
        return userBindingService.listBindings(userId);
    }

    // ==================== 授权发起 ====================

    /**
     * 服务端授权发起：签发一次性 state 并构造 Provider 授权 URL。
     * state 与租户/Provider/发起会话绑定（当前登录用户可为 null——登录页发起）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthorizeStart startAuthorize(String provider, String redirectPath) {
        return authorizeStartService.startAuthorize(provider, redirectPath);
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
    /**
     * 登录前发起按租户名称精确解析（V012-BUG-019）：名称完全一致且唯一才返回租户 ID。
     * <p>
     * 零命中 → {@code sso_tenant_not_found}；多行命中 → {@code sso_tenant_ambiguous}
     * （名称歧义不任意选中）；空白 → {@code sso_tenant_required}。停用/过期等
     * 有效性仍由 {@link #startAuthorizeLogin(String, Long, String)} 的既有校验承担。
     * 查询发生在免认证路径，挂起租户拦截器（sys_tenant 为全局表）。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Long resolveTenantIdByNameExact(String tenantName) {
        return tenantResolveService.resolveTenantIdByNameExact(tenantName);
    }

    /**
     * 登录前安全发起——租户名称入口：登录页免填企业（B021-016）且未提供名称时，
     * 按 Provider 反查唯一可用企业；提供了名称则按名称精确解析（V012-BUG-019 既有语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthorizeStart startAuthorizeLogin(String provider, String tenantName, String redirectPath) {
        return authorizeStartService.startAuthorizeLogin(provider, tenantName, redirectPath);
    }

    /**
     * 登录页免填企业（B021-016）：按 Provider 反查启用配置——恰好一个可用企业
     * 启用该登录方式即视为目标；零命中/多行命中均 fail closed（多企业歧义后续
     * 由扫码选择所属企业承接，不在此处任意选中）。停用/过期企业不参与自动识别。
     * 查询发生在免认证路径，挂起租户拦截器（配置表按 tenant_id 显式过滤语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long resolveDefaultTenantIdForProvider(String provider) {
        return tenantResolveService.resolveDefaultTenantIdForProvider(provider);
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthorizeStart startAuthorizeLogin(String provider, Long tenantId, String redirectPath) {
        return authorizeStartService.startAuthorizeLogin(provider, tenantId, redirectPath);
    }

    /**
     * 回调换票 + 已绑定登录定位。
     * <p>
     * state 校验：存在、未过期、未消费、Provider/租户匹配；原子消费后用一次性
     * code 换外部稳定标识；已绑定 → 返回本地 userId 进入既有登录链；未绑定 →
     * 返回绑定候选（挂起会话由调用方在受控事务内完成绑定）。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public CallbackResult handleCallback(String provider, String code, String state) {
        // Provider 回跳路径由 resolveCallbackUrl 小写生成（/api/auth/sso/dingtalk/callback），
        // 而 PROVIDERS 注册与 state.provider 存储为大写：回调入口必须先归一化，
        // 否则真实 Provider 回跳永远命中 provider 拒绝（真实链未执行过的断链缺陷）。
        String normalized = provider == null ? "" : provider.trim().toUpperCase();
        requireProvider(normalized);
        // 免认证回调：与 startAuthorizeLogin 同口径挂起租户拦截器（显式谓词承担租户语义）
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            return callbackProcessor.doHandleCallback(normalized, code, state);
        }
    }

    // ==================== 绑定 / 解绑 ====================

    /**
     * 绑定外部身份到本地账号（必须已认证；同租户内冲突/重复绑定显式拒绝）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void bind(String provider, Long userId, String externalId) {
        userBindingService.bind(provider, userId, externalId);
    }

    /**
     * 解绑：本地账号在当前租户内对指定 Provider 的有效绑定置 UNBOUND；
     * 并按方向 §3.2「第三方解绑触发与风险相称的会话撤销」撤销当前会话：
     * 清除登录缓存 + 按当前 access token 摘要写撤销标记（token 维度——同用户随后
     * 建立的新会话不受影响，旧 token 无法借新会话的 userId 缓存复活）+ 撤销全部
     * 既有 refresh token。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbind(String provider, Long userId) {
        unbind(provider, userId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unbind(String provider, Long userId, String currentToken) {
        userBindingService.unbind(provider, userId, currentToken);
    }

    // ==================== 内部（跨协作类共享静态） ====================

    /**
     * A4 配置期间授权语义：未兑换票据的新会话签发受签发时刻配置指纹约束——
     * 停用或 appId/secret/身份模式/企业标识变化后，票据兑换一律拒绝（新会话
     * 不得以旧配置建立；重新发起授权即可恢复）。返回当前配置指纹；配置缺失、
     * 停用或解密失败返回 null，由调用方拒绝并审计。
     */
    public String currentConfigDigestFor(String provider, Long tenantId) {
        return configService.currentConfigDigestFor(provider, tenantId);
    }

    /** 控制器层越权/候选拒绝审计入口（独立事务提交，不回随响应路径丢失）。 */
    public void auditRejection(String provider, String eventType, String detail) {
        auditRecorder.auditRejection(provider, eventType, detail);
    }

    /**
     * 按租户隔离查询 SSO 审计（调用方须经 {@code system:sso:audit:query} 权限守卫；
     * 无权/跨租户查询被拒绝由权限链与本查询的显式租户条件共同保证）。
     */
    public SsoAuditListVO queryAudit(String provider, String eventType, String result,
                                      Long localUserId, int page, int size) {
        return auditRecorder.queryAudit(provider, eventType, result, localUserId, page, size);
    }

    static void requireProvider(String provider) {
        if (provider == null || !PROVIDERS.contains(provider)) {
            // 不回显请求中的 provider 原文，避免把未校验输入带进响应
            throw new SsoRejectionException(SystemErrorKeys.SSO_BINDING_INVALID,
                    "不支持的第三方登录方式，请刷新页面后重试");
        }
    }

    /** 指纹材料单一来源（测试同源引用）；材料构成变更即等价于全量配置变更语义。 */
    static String fingerprintOf(String provider, String appId, String secret,
                                String enterpriseId, Integer enabled) {
        String material = provider + "|" + appId + "|" + secret + "|"
                + (enterpriseId == null ? "" : enterpriseId) + "|" + enabled;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 外部标识摘要（绑定权威列；明文不落 SQL/日志——I5 复验 G7b）。 */
    public static String digest(String externalId) {
        return sha256(externalId);
    }

    /** 外部标识摘要前缀（审计/展示用；不回传原文）。 */
    public static String digestPrefix(String externalId) {
        return sha256(externalId).substring(0, 8);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 授权发起结果 */
    public record AuthorizeStart(String authorizeUrl, String state) {
    }

    /** 回调处理结果：bound=true 时 userId 为本地账号；configDigest 供票据绑定配置指纹（A4） */
    public record CallbackResult(String provider, Long tenantId, String externalId,
                                 String externalDigest, Long userId, String redirectPath,
                                 boolean bound, String configDigest) {
    }
}
