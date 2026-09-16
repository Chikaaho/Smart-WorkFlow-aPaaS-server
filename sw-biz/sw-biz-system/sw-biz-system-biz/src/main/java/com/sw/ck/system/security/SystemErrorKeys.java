package com.sw.ck.system.security;

/**
 * 认证会话、租户与 SSO 语义键（P61 阶段 B）。
 *
 * <p><b>为什么不是枚举错误码</b>：这些语义在 0.1.0 已以
 * {@code CommonErrorCode.PARAM_ERROR(400)} / {@code UNAUTHORIZED(401)} /
 * {@code FORBIDDEN(403)} 的通用数值码外显。P61 的方向裁决是「既有数值 code 保持兼容、
 * 本轮不重编号」，因此这里保留原数值不动，只登记稳定 {@code errorKey}，
 * 使调用方可按语义分流而不再依赖数值或文案。</p>
 *
 * <p><b>命名空间</b>：{@code system.*}。与 {@code auth.*}（{@link AuthErrorCode}，
 * 首方登录验证码/凭据语义）区分：本类覆盖 SSO 流程、会话续期与租户有效性。</p>
 *
 * <p><b>安全边界</b>：本类的键只标识「对用户安全的结论」，不得携带 Provider 配置、
 * 租户标识、绑定对象身份或第三方原文。精确原因进受保护诊断层。</p>
 *
 * <p>登记见 {@code docs/governance/error-code-catalog.md} §4。</p>
 */
public final class SystemErrorKeys {

    private SystemErrorKeys() {
    }

    // ==================== SSO 流程 ====================

    /** 授权发起/回调整体失败：不向用户区分 Provider 配置、白名单或三方细节。 */
    public static final String SSO_LOGIN_NOT_COMPLETED = "system.sso_login_not_completed";

    /** 登录前发起未显式指定租户（请求参数问题，可自行修正）。 */
    public static final String SSO_TENANT_REQUIRED = "system.sso_tenant_required";

    /** 登录/绑定票据无效、已过期或已被消费。 */
    public static final String SSO_TICKET_INVALID = "system.sso_ticket_invalid";

    /** 票据所属租户当前不可用。 */
    public static final String SSO_TENANT_INVALID = "system.sso_tenant_invalid";

    /** 票据对应账号已停用或不可用。 */
    public static final String SSO_ACCOUNT_UNAVAILABLE = "system.sso_account_unavailable";

    /** 绑定候选与当前登录租户不匹配。 */
    public static final String SSO_BINDING_TENANT_MISMATCH = "system.sso_binding_tenant_mismatch";

    /** 绑定冲突：该外部身份或该本地账号已存在其他绑定。 */
    public static final String SSO_BINDING_CONFLICT = "system.sso_binding_conflict";

    /** 绑定请求不合法（参数形态问题，可自行修正）。 */
    public static final String SSO_BINDING_INVALID = "system.sso_binding_invalid";

    /** 未找到有效绑定（解绑场景）。 */
    public static final String SSO_BINDING_NOT_FOUND = "system.sso_binding_not_found";

    // ==================== 会话与身份 ====================

    /** 未登录或登录态已失效，需要重新登录。 */
    public static final String SESSION_REQUIRED = "system.session_required";

    /** 刷新令牌无效。 */
    public static final String REFRESH_TOKEN_INVALID = "system.refresh_token_invalid";

    /** 刷新令牌已被使用过，全部会话已失效。 */
    public static final String SESSION_REVOKED = "system.session_revoked";

    /** 刷新令牌已过期。 */
    public static final String REFRESH_TOKEN_EXPIRED = "system.refresh_token_expired";

    // ==================== 账号状态 ====================

    /** 账号已锁定（登录链外显，防枚举范围内）。 */
    public static final String ACCOUNT_LOCKED = "system.account_locked";

    /** 账号已停用。 */
    public static final String ACCOUNT_DISABLED = "system.account_disabled";

    /** 所属租户不可用，会话已终止。 */
    public static final String TENANT_UNAVAILABLE = "system.tenant_unavailable";

    /** 当前凭据不满足该操作的身份要求（如旧密码错误）。 */
    public static final String CREDENTIAL_MISMATCH = "system.credential_mismatch";

    /** 请求体校验失败（通用输入可修正）。 */
    public static final String REQUEST_INVALID = "system.request_invalid";
}
