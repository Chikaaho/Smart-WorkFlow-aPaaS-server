package com.sw.ck.system.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录 / 刷新 token 响应 DTO。
 * 包含 JWT access token、过期时间（秒）与首登强制改密标记，前端据此计算刷新时机与路由。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse implements Serializable {

    /** JWT access token，前端内存存储 */
    private String accessToken;

    /** access token 过期时间（秒），前端据此计算刷新时机 */
    private long expiresIn;

    /** 首登强制改密标记（1.0.0 F1-4）：true 时前端路由到改密页，服务端同步拦截业务接口 */
    private boolean mustChangePassword;

    public TokenResponse(String accessToken, long expiresIn) {
        this(accessToken, expiresIn, false);
    }
}
