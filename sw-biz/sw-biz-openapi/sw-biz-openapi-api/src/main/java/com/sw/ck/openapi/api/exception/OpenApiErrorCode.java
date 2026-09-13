package com.sw.ck.openapi.api.exception;

import com.sw.ck.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 开放接口错误码（3000-3099 区间，I4 §3.4）。
 */
@Getter
public enum OpenApiErrorCode implements ErrorCode {

    APP_NOT_FOUND(3000, "开放应用不存在"),
    APP_DISABLED(3001, "开放应用已停用"),
    SIGN_INVALID(3002, "签名校验失败"),
    TIMESTAMP_EXPIRED(3003, "请求时间戳超出允许窗口"),
    NONCE_REUSED(3004, "随机串已使用（防重放拒绝）"),
    SCOPE_DENIED(3005, "应用未被授权该操作范围"),
    IDEMPOTENCY_CONFLICT(3006, "幂等键已绑定其他业务对象"),
    PROCESS_NOT_VISIBLE(3007, "流程实例不存在或不属于应用授权范围"),
    CALLBACK_FAILED(3008, "回调投递失败"),
    TENANT_INVALID(3009, "应用所属租户不可用"),
    ;

    private final int code;
    private final String message;

    OpenApiErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
