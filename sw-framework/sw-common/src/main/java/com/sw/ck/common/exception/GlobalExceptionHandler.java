package com.sw.ck.common.exception;

import com.sw.ck.common.response.R;
import com.sw.ck.common.trace.EventRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口。
 *
 * <p>P61 §3.1/§3.2/§3.3：出口同时承担三件事——向调用方给出**当前语言**下的安全结论
 * （{@code code}+{@code errorKey}+{@code msg}+{@code eventRef}），并把可供运维定位的真实原因
 * 写入结构化日志。{@code msg} 只承载面向当前受众的安全结论与恢复建议，不承载 Java 字段名、
 * 类名、SQL/JDBC、栈、租户标识或未经筛选的 exception message；原始原因经 {@code eventRef}
 * 关联的日志定位。</p>
 *
 * <p>语言由请求 {@code Accept-Language} 决定，键为 {@code error.<errorKey>}；
 * 目录缺失时回退到枚举中的 zh-CN 默认值。{@code errorKey}、数值码与事件引用不随语言变化。</p>
 *
 * <p>HTTP 状态语义（保持 0.1.0 不变）：业务可预期异常走 200 + body code；已认证无权限落 403；
 * 畸形请求落 400；未分类/基础设施故障落 5xx。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 消息目录；为空（未装配）时全部回退到枚举中的 zh-CN 默认文案。 */
    private final org.springframework.context.MessageSource messageSource;

    public GlobalExceptionHandler(org.springframework.context.MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 按当前请求语言解析文案；目录缺失或未装配时回退到缺省文案。 */
    private String text(String errorKey, String fallback) {
        return com.sw.ck.common.i18n.LocalizedMessages
                .text(messageSource, errorKey, fallback, com.sw.ck.common.i18n.LocalizedMessages.current());
    }

    /**
     * 业务可预期异常，HTTP 状态保持 200，异常语义由 {@link R#code} + body 承载。
     * 理由：前端基于 body.code 做统一错误处理（弹 toast / 跳登录），
     * 若改 HTTP 状态则所有前端 axios 拦截器必须额外判断状态码 — 有意保持
     * "业务错误走 200+body.code" 模式。
     */
    @ExceptionHandler(BaseException.class)
    public R<Void> handleBaseException(BaseException ex) {
        String eventRef = EventRef.current();
        // P61 R3：带目录参数的业务异常按参数填充，避免无参目录条目覆盖字段显示名等业务细节
        String message = ex.getMessageArgs() == null || ex.getMessageArgs().length == 0
                ? text(ex.getErrorKey(), ex.getMessage())
                : com.sw.ck.common.i18n.LocalizedMessages.textArgs(ex.getErrorKey(), ex.getMessage(), ex.getMessageArgs());
        log.warn("business exception: code={} errorKey={} eventRef={} message={}",
                ex.getCode(), ex.getErrorKey(), eventRef, message);
        // msg 已按目录+参数解析完成，必须用 failResolved，避免 R.fail 再查一次目录把细节覆盖
        return com.sw.ck.common.response.R.failResolved(
                ex.getCode(), ex.getErrorKey(), message, eventRef);
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
        String eventRef = EventRef.current();
        log.warn("access denied: eventRef={} message={}", eventRef, ex.getMessage());
        return fail(CommonErrorCode.FORBIDDEN, eventRef);
    }

    /**
     * 请求体不可读（JSON 非法/类型不匹配）→ HTTP 400 + 受控业务码。
     * P21 G5a：畸形请求不得落入 500，须返回客户端可控错误。
     * <p>
     * P61：Jackson 的原文（含目标类名、字段路径、行号）只进日志，不进 {@code msg}。
     * </p>
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String eventRef = EventRef.current();
        log.warn("request body not readable: eventRef={} cause={}", eventRef,
                ex.getMostSpecificCause().getMessage());
        return com.sw.ck.common.response.R.failResolved(CommonErrorCode.PARAM_ERROR.getCode(), "common.request_body_unreadable",
                text("common.request_body_unreadable", "请求数据格式不正确，请检查后重试"), eventRef);
    }

    /**
     * 上传体积超过容器上限 → HTTP 400 + 受控业务码。
     * <p>
     * P61 R8：关闭「文件超限真实响应」边界。此前容器层超限（6MB）无专用 handler，
     * 落入 {@link #handleException} 成为「系统异常」，上传者无法判断是文件太大。
     * 业务层 5MB 上限仍由表单导入导出以 1499 精确拒绝；两层口径见 application.yml 注释。
     * </p>
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMaxUploadSize(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        String eventRef = EventRef.current();
        log.warn("upload size exceeded: eventRef={} message={}", eventRef, ex.getMessage());
        return com.sw.ck.common.response.R.failResolved(CommonErrorCode.PARAM_ERROR.getCode(), "common.upload_too_large",
                text("common.upload_too_large", "上传的文件过大，请压缩或拆分后重试"), eventRef);
    }

    /**
     * 参数类型/取值不匹配 → HTTP 400 + 受控业务码。
     * <p>P61：Java 参数名只进日志，不进 {@code msg}。</p>
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String eventRef = EventRef.current();
        log.warn("argument type mismatch: eventRef={} name={} value={}", eventRef,
                ex.getName(), ex.getValue());
        return com.sw.ck.common.response.R.failResolved(CommonErrorCode.PARAM_ERROR.getCode(), "common.param_type_mismatch",
                text("common.param_type_mismatch", "请求参数类型不正确，请检查后重试"), eventRef);
    }

    /**
     * 业务参数校验类异常（对象不存在等）→ HTTP 400 受控错误，不得落入 500。
     */
    @ExceptionHandler(java.lang.IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(java.lang.IllegalArgumentException ex) {
        String eventRef = EventRef.current();
        log.warn("illegal argument: eventRef={} message={}", eventRef, ex.getMessage());
        // 原文只进日志；公共响应为安全分类文案，避免部分 IllegalArgumentException 携带内部细节
        return com.sw.ck.common.response.R.failResolved(CommonErrorCode.PARAM_ERROR.getCode(), "common.illegal_argument",
                text("common.illegal_argument", "请求参数不合法，请检查后重试"), eventRef);
    }

    /**
     * {@code @Valid} 参数校验失败 → HTTP 400 受控错误，不得落入 500。
     * <p>P61：除首条失败外还回传失败字段总数，便于前端定位；Java 字段名只进日志。</p>
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String eventRef = EventRef.current();
        String diagnostic = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数非法");
        log.warn("validation failed: eventRef={} fieldErrors={}", eventRef, diagnostic);
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("请检查填写内容");
        return com.sw.ck.common.response.R.failResolved(CommonErrorCode.PARAM_ERROR.getCode(), "common.validation_failed",
                text("common.validation_failed", "表单填写有误，请按提示修改后重试")
                        + (detail == null || detail.isBlank() ? "" : "：" + detail), eventRef);
    }

    /**
     * 必填请求参数缺失 → HTTP 400 受控业务码，不得落入 500（P61 R4a 探针发现：
     * {@code MissingServletRequestParameterException} 原先落 {@link #handleException}，
     * 客户端漏传参数被误报为系统异常）。参数名只进日志。
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        String eventRef = EventRef.current();
        log.warn("missing request parameter: eventRef={} name={} type={}", eventRef,
                ex.getParameterName(), ex.getParameterType());
        return fail(CommonErrorCode.PARAM_ERROR, eventRef);
    }

    /**
     * 静态资源/未映射路径 → HTTP 404 受控结果，不得落入 500（I5 复验 G2a：
     * 匿名或路径错误必须是可判定结果，"系统异常" 不得掩盖认证/路由语义）。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        String eventRef = EventRef.current();
        log.warn("no resource: eventRef={} path={}", eventRef, ex.getResourcePath());
        return fail(CommonErrorCode.NOT_FOUND, eventRef);
    }

    /**
     * 未分类 / 基础设施故障 → HTTP 500 + body 500。
     * system.md §8：基础设施故障必须落 5xx，不得伪装为 200。
     * <p>P61：完整栈与原始原因只进日志，并绑定 {@code eventRef} 供运维按同一引用定位。</p>
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception ex) {
        String eventRef = EventRef.current();
        log.error("unexpected exception: eventRef={}", eventRef, ex);
        return fail(CommonErrorCode.SYSTEM_ERROR, eventRef);
    }

    private R<Void> fail(CommonErrorCode errorCode, String eventRef) {
        // 文案已按目录解析，errorKey 亦为调用方权威：不得再经 R.fail 按数值码二次解析，
        // 否则 400 等复用数值码的分支会被 param_error 的目录文案整体覆盖（errorKey 与 msg 不一致）
        return com.sw.ck.common.response.R.failResolved(errorCode.getCode(), errorCode.getErrorKey(),
                text(errorCode.getErrorKey(), errorCode.getMessage()), eventRef);
    }
}
