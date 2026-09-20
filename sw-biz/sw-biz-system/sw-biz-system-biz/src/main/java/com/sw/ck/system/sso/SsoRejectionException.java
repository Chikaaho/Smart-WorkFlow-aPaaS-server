package com.sw.ck.system.sso;

import lombok.Getter;

/**
 * SSO 用户可见拒绝（P61 阶段 B）。
 *
 * <p>只承载两样东西：对用户<b>安全</b>的结论（{@link #getMessage()}）与稳定语义键
 * （{@link #getErrorKey()}）。Provider 配置、租户标识、授权状态原文、回调白名单与
 * 第三方响应一律不进入本异常——它们通过结构化日志与 SSO 审计定位。</p>
 *
 * <p>控制器据此渲染响应，因此不得用异常文案判别分支。</p>
 */
@Getter
public class SsoRejectionException extends RuntimeException {

    private final String errorKey;

    public SsoRejectionException(String errorKey, String safeMessage) {
        super(safeMessage);
        this.errorKey = errorKey;
    }

    public SsoRejectionException(String errorKey, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorKey = errorKey;
    }
}
