package com.sw.ck.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 已认证但无权限（含 {@code @PreAuthorize} 鉴权失败）统一返回 403 + {@link R} 结构。
 */
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        // 已认证无权限：给出可行动的恢复动作，不披露所需权限点或对象存在性。
        // 语言由请求 Accept-Language 决定（P61 §3.3，过滤器层经共享消息源解析）。
        JsonResponseWriter.write(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                R.fail(CommonErrorCode.FORBIDDEN.getCode(),
                        CommonErrorCode.FORBIDDEN.getErrorKey(),
                        com.sw.ck.common.i18n.LocalizedMessages.text(
                                CommonErrorCode.FORBIDDEN.getErrorKey(),
                                CommonErrorCode.FORBIDDEN.getMessage()),
                        com.sw.ck.common.trace.EventRef.current()));
    }
}
