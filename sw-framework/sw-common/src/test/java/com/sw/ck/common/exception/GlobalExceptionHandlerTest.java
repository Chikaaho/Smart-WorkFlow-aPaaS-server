package com.sw.ck.common.exception;

import com.sw.ck.common.response.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产全局异常处理器单元测试。
 * <p>
 * 覆盖方法级鉴权拒绝（{@code @PreAuthorize} 抛出的 {@link AuthorizationDeniedException}）
 * 必须落 HTTP 403 + body code=403，不得被通用兜底吞成 500 的契约。
 * 该契约由 sw-common 直接持有，独立于任何测试专用安全链配置。
 * </p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(null);

    @Test
    @DisplayName("AuthorizationDeniedException → R{code=403}（HTTP 403 由 @ResponseStatus 承载）")
    void handleAuthorizationDenied_shouldReturnForbidden() {
        // AuthorizationDeniedException 自身实现 AuthorizationResult，可直接作为第二参构造
        R<Void> result = handler.handleAuthorizationDenied(new AuthorizationDeniedException(
                "Access Denied", new AuthorizationDeniedException("inner")));

        assertThat(result.getCode())
                .as("方法级鉴权拒绝必须返回 code=403")
                .isEqualTo(CommonErrorCode.FORBIDDEN.getCode());
        assertThat(result.getCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("普通 Exception → R{code=500}（兜底语义不变，其他异常仍按系统异常处理）")
    void handleException_shouldKeep500Fallback() {
        R<Void> result = handler.handleException(new IllegalStateException("boom"));

        assertThat(result.getCode())
                .as("未分类异常仍按系统异常 500 处理")
                .isEqualTo(CommonErrorCode.SYSTEM_ERROR.getCode());
        assertThat(result.getCode()).isEqualTo(500);
    }
}
