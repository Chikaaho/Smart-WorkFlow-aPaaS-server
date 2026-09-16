package com.sw.ck.system.security;

import com.sw.ck.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 认证登录链专用错误码（P45）。
 * <p>
 * 区间 2100-2149 原声明为「认证登录专用」，但流程模块 {@code BpmErrorCode} 亦占用了
 * 2101-2104（同值不同义）。P61 不重编号既有数值码：冲突由 {@link #getErrorKey()} 消歧，
 * 该数值歧义已在 {@code docs/governance/error-code-catalog.md} 登记为已弃用；
 * 新增错误不得继续复用 2101-2104。
 * <p>
 * 每个外显提示必须同时携带稳定机器码，前端据此映射。
 */
@Getter
public enum AuthErrorCode implements ErrorCode {

    /** 验证码 UUID 缺失/挑战不存在/验证码内容不匹配（含挑战已消费或并发落败） */
    CAPTCHA_ERROR(2101, "auth.captcha_mismatch", "验证码错误"),
    /** 验证码内容匹配但挑战生成时间超过有效期 */
    CAPTCHA_EXPIRED(2102, "auth.captcha_expired", "验证码已过期"),
    /** 客户端 timestamp 缺失、格式非法或与服务器时间差超过容忍窗口 */
    CLIENT_TIME_ABNORMAL(2103, "auth.client_time_abnormal", "机器时间异常"),
    /** 密文非法、解密失败、账号不存在或密码不匹配的统一外显语义 */
    PASSWORD_ERROR(2104, "auth.credential_invalid", "密码错误"),
    ;

    private final int code;
    private final String errorKey;
    private final String message;

    AuthErrorCode(int code, String errorKey, String message) {
        this.code = code;
        this.errorKey = errorKey;
        this.message = message;
    }
}
