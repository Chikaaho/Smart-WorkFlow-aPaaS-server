package com.sw.ck.common.exception;

import lombok.Getter;

/**
 * 业务可预期异常。
 *
 * <p>P61：除数值码外同时携带 {@link ErrorCode#getErrorKey()}，供调用方在不依赖数值码
 * （存在跨模块重复）与不依赖文案的前提下分流。用原始整数码构造时 {@code errorKey} 为
 * {@code null}，此时调用方只能按数值码处理——新增代码应优先使用 {@link ErrorCode} 常量。</p>
 */
@Getter
public class BaseException extends RuntimeException {

    private final int code;

    private final String errorKey;

    /** 目录文案参数（P61 R3）：非空时按 {0}/{1} 填充目录条目，业务细节不被无参文案覆盖。 */
    private final Object[] messageArgs;

    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.errorKey = errorCode.getErrorKey();
        this.messageArgs = null;
    }

    /**
     * 带目录参数的业务异常：{@code fallbackMessage} 为目录缺失时的缺省文案，
     * {@code messageArgs} 依次填充目录条目中的 {0}/{1}。
     */
    public BaseException(ErrorCode errorCode, Object[] messageArgs, String fallbackMessage) {
        super(fallbackMessage);
        this.code = errorCode.getCode();
        this.errorKey = errorCode.getErrorKey();
        this.messageArgs = messageArgs;
    }

    public BaseException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.errorKey = errorCode.getErrorKey();
        this.messageArgs = null;
    }

    public BaseException(int code, String message) {
        super(message);
        this.code = code;
        this.errorKey = null;
        this.messageArgs = null;
    }

    public BaseException(int code, String errorKey, String message) {
        super(message);
        this.code = code;
        this.errorKey = errorKey;
        this.messageArgs = null;
    }
}
