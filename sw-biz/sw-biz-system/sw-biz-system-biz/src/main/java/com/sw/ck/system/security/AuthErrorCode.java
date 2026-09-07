package com.sw.ck.system.security;

import com.sw.ck.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 认证登录链专用错误码（P45）。
 * <p>
 * 区间 2100-2149 为认证登录专用，与 form 模块 1000-1505 区间互不重叠。
 * 每个外显提示必须同时携带稳定机器码，前端 error-code-map 据此映射。
 */
@Getter
public enum AuthErrorCode implements ErrorCode {

    /** 验证码 UUID 缺失/挑战不存在/验证码内容不匹配（含挑战已消费或并发落败） */
    CAPTCHA_ERROR(2101, "验证码错误"),
    /** 验证码内容匹配但挑战生成时间超过有效期 */
    CAPTCHA_EXPIRED(2102, "验证码已过期"),
    /** 客户端 timestamp 缺失、格式非法或与服务器时间差超过容忍窗口 */
    CLIENT_TIME_ABNORMAL(2103, "机器时间异常"),
    /** 密文非法、解密失败、账号不存在或密码不匹配的统一外显语义 */
    PASSWORD_ERROR(2104, "密码错误"),
    ;

    private final int code;
    private final String message;

    AuthErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
