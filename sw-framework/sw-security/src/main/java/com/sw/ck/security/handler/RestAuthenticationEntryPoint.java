package com.sw.ck.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 未认证（缺 token / token 失效）统一返回 401 + {@link R} 结构，替代 Spring Security
 * 默认的跳转登录页行为。
 */
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        // 未认证一律给同一结论：不区分缺失/过期/被撤销，也不回显 token 或解析细节。
        // 语言由请求 Accept-Language 决定（P61 §3.3，过滤器层经共享消息源解析）。
        JsonResponseWriter.write(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                R.fail(CommonErrorCode.UNAUTHORIZED.getCode(),
                        CommonErrorCode.UNAUTHORIZED.getErrorKey(),
                        com.sw.ck.common.i18n.LocalizedMessages.text(
                                CommonErrorCode.UNAUTHORIZED.getErrorKey(),
                                CommonErrorCode.UNAUTHORIZED.getMessage()),
                        com.sw.ck.common.trace.EventRef.current()));
    }
}
