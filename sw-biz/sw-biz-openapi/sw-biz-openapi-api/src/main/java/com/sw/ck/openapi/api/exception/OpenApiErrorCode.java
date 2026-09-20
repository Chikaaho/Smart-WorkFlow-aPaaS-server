package com.sw.ck.openapi.api.exception;

import com.sw.ck.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 开放接口错误码（3000-3099 区间，I4 §3.4）。
 * <p>
 * 面向开放 API 集成开发者：数值码继续服务既有消费者，{@link #getErrorKey()} 是
 * 新增稳定契约。协议状态与合法参数名可披露，服务端栈/密钥/内部路径与未筛选的第三方原文不可。
 */
@Getter
public enum OpenApiErrorCode implements ErrorCode {

    APP_NOT_FOUND(3000, "openapi.app_not_found", "开放应用不存在"),
    APP_DISABLED(3001, "openapi.app_disabled", "开放应用已停用"),
    SIGN_INVALID(3002, "openapi.signature_invalid", "签名校验失败"),
    TIMESTAMP_EXPIRED(3003, "openapi.timestamp_out_of_window", "请求时间戳超出允许窗口"),
    NONCE_REUSED(3004, "openapi.nonce_reused", "随机串已使用（防重放拒绝）"),
    SCOPE_DENIED(3005, "openapi.scope_denied", "应用未被授权该操作范围"),
    IDEMPOTENCY_CONFLICT(3006, "openapi.idempotency_conflict", "幂等键已绑定其他业务对象"),
    PROCESS_NOT_VISIBLE(3007, "openapi.process_not_visible", "流程实例不存在或不属于应用授权范围"),
    CALLBACK_FAILED(3008, "openapi.callback_failed", "回调投递失败"),
    TENANT_INVALID(3009, "openapi.tenant_invalid", "应用所属租户不可用"),
    ;

    private final int code;
    private final String errorKey;
    private final String message;

    OpenApiErrorCode(int code, String errorKey, String message) {
        this.code = code;
        this.errorKey = errorKey;
        this.message = message;
    }
}
