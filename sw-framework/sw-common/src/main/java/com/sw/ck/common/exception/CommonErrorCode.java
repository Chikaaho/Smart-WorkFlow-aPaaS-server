package com.sw.ck.common.exception;

import lombok.Getter;

/**
 * 通用错误码：统一出口自身的失败语义。
 * <p>文案按 {@link FailureCategory} 的语气与恢复动作规则书写：可重试类给出重试时机，
 * 权限类指出应联系管理员，不存在类指出应返回列表刷新。</p>
 */
@Getter
public enum CommonErrorCode implements ErrorCode {

    SYSTEM_ERROR(500, "common.system_error",
            "系统暂时无法处理该请求，请稍后重试；若持续出现请提供事件引用联系管理员",
            FailureCategory.SYSTEM_FAULT),
    PARAM_ERROR(400, "common.param_error", "请求参数有误，请检查后重试",
            FailureCategory.INPUT_CORRECTABLE),
    UNAUTHORIZED(401, "common.unauthenticated", "登录状态已失效，请重新登录",
            FailureCategory.AUTHENTICATION_REQUIRED),
    FORBIDDEN(403, "common.forbidden", "您没有执行该操作的权限，请联系管理员分配相应权限",
            FailureCategory.PERMISSION_DENIED),
    NOT_FOUND(404, "common.not_found", "请求的资源不存在或已被删除，请返回列表刷新后重试",
            FailureCategory.OBJECT_NOT_FOUND),
    ;

    private final int code;
    private final String errorKey;
    private final String message;
    private final FailureCategory category;

    CommonErrorCode(int code, String errorKey, String message, FailureCategory category) {
        this.code = code;
        this.errorKey = errorKey;
        this.message = message;
        this.category = category;
    }
}
