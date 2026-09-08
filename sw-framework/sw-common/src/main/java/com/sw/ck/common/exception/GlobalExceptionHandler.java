package com.sw.ck.common.exception;

import com.sw.ck.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务可预期异常，HTTP 状态保持 200，异常语义由 {@link R#code} + body 承载。
     * 理由：前端基于 body.code 做统一错误处理（弹 toast / 跳登录），
     * 若改 HTTP 状态则所有前端 axios 拦截器必须额外判断状态码 — 有意保持
     * "业务错误走 200+body.code" 模式。
     */
    @ExceptionHandler(BaseException.class)
    public R<Void> handleBaseException(BaseException ex) {
        log.warn("business exception: {}", ex.getMessage());
        return R.fail(ex.getCode(), ex.getMessage());
    }

    /**
     * 已认证但方法级鉴权失败（{@code @PreAuthorize("@ss.hasPermi(...)")} 拒绝）→ HTTP 403 + body code=403。
     * <p>
     * Spring Security 6 方法安全抛出的 {@link AuthorizationDeniedException} 是运行时异常，
     * 会穿透 {@code ExceptionHandlerInterceptor} 直达 {@code DispatcherServlet}，由本兜底捕获；
     * 此前被 {@link #handleException} 兜成 500，拒绝语义虽成立但契约失真——已认证无权限必须落 403。
     * 该分支作为兜底保障，与 {@code RestAccessDeniedHandler}（认证过滤器链路 403）语义一致。
     * </p>
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("access denied: {}", ex.getMessage());
        return R.fail(CommonErrorCode.FORBIDDEN.getCode(), CommonErrorCode.FORBIDDEN.getMessage());
    }

    /**
     * 请求体不可读（JSON 非法/类型不匹配）→ HTTP 400 + 受控业务码。
     * P21 G5a：畸形请求不得落入 500，须返回客户端可控错误。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("request body not readable: {}", ex.getMessage());
        return R.fail(400, "请求体非法: " + ex.getMostSpecificCause().getMessage());
    }

    /**
     * 参数类型/取值不匹配 → HTTP 400 + 受控业务码。
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.warn("argument type mismatch: {}", ex.getMessage());
        return R.fail(400, "参数非法: " + ex.getName());
    }

    /**
     * 业务参数校验类异常（对象不存在等）→ HTTP 400 受控错误，不得落入 500。
     */
    @ExceptionHandler(java.lang.IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(java.lang.IllegalArgumentException ex) {
        log.warn("illegal argument: {}", ex.getMessage());
        return R.fail(400, ex.getMessage());
    }

    /**
     * 未分类 / 基础设施故障 → HTTP 500 + body 500。
     * system.md §8：基础设施故障必须落 5xx，不得伪装为 200。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception ex) {
        log.error("unexpected exception", ex);
        return R.fail(CommonErrorCode.SYSTEM_ERROR.getCode(), CommonErrorCode.SYSTEM_ERROR.getMessage());
    }
}
