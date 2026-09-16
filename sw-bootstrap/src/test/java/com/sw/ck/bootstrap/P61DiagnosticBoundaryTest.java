package com.sw.ck.bootstrap;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sw.ck.common.exception.GlobalExceptionHandler;
import com.sw.ck.common.response.R;
import com.sw.ck.common.trace.EventRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P61 阶段 B：用户消息与诊断分层的真实行为证据。
 *
 * <p>本测试用<b>可识别的受控标记</b>注入到各类失败原因中，同时断言两端：</p>
 * <ol>
 *   <li><b>公共响应零暴露</b>：响应体不得出现任何标记（Jackson 原文、Java 字段名、
 *       类名、栈帧、SQL/依赖细节、租户号）；</li>
 *   <li><b>诊断可定位</b>：同一失败在服务端日志中保留完整原因，且携带与响应一致的
 *       {@code eventRef}，使运维可用用户报出的引用还原真实原因。</li>
 * </ol>
 *
 * <p>事件引用本身来自客户端（{@code X-Request-Id}），因此另有一组用例证明它不被
 * 用于响应注入。本测试不启动 Spring 上下文，直接驱动统一异常出口与过滤器。</p>
 */
class P61DiagnosticBoundaryTest {

    /** 受控注入标记：只应出现在日志，绝不应出现在响应体。 */
    private static final String MARKER_CLASS = "com.example.internal.MarkerService";
    private static final String MARKER_FIELD = "internalFieldName";
    private static final String MARKER_SQL = "SELECT secret FROM sw_internal_marker_table";
    private static final String MARKER_TENANT = "tenantId=987654";
    private static final String MARKER_PROVIDER = "errcode=40029";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(null);

    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
        bindRequest("web-client-header-untrusted");
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequest(String clientHeaderValue) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        // 客户端可携带任意头（含注入内容），事件引用一律由服务端生成，不取该头的值
        when(request.getHeader(EventRef.HEADER)).thenReturn(clientHeaderValue);
        // 让 mock 具备真实请求属性行为：EventRef 依赖属性在请求内缓存引用
        java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        when(request.getAttribute(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> attributes.get((String) inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            attributes.put((String) inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 本次请求的事件引用（由 EventRef 生成并缓存在请求属性中）。 */
    private String currentEventRef() {
        return EventRef.current();
    }

    private String loggedDiagnostics() {
        return appender.list.stream()
                .map(this::render)
                .collect(Collectors.joining("\n"));
    }

    private String render(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(event.getFormattedMessage());
        ch.qos.logback.classic.spi.IThrowableProxy proxy = event.getThrowableProxy();
        while (proxy != null) {
            sb.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
            proxy = proxy.getCause();
        }
        return sb.toString();
    }

    private static void assertNoMarkerInBody(String body) {
        assertThat(body)
                .as("公共响应不得暴露任何受控注入标记：" + body)
                .doesNotContain(MARKER_CLASS)
                .doesNotContain("MarkerService")
                .doesNotContain(MARKER_FIELD)
                .doesNotContain(MARKER_SQL)
                .doesNotContain("sw_internal_marker_table")
                .doesNotContain(MARKER_TENANT)
                .doesNotContain(MARKER_PROVIDER)
                .doesNotContain("at com.")
                .doesNotContain("Exception");
    }

    @Test
    @DisplayName("请求体不可读：Jackson 原文只进日志，响应只给可行动结论")
    void messageNotReadable_shouldNotEchoJacksonDetail() {
        String jacksonDetail = "Cannot deserialize value of type `" + MARKER_CLASS
                + "` from String (" + MARKER_SQL + ")";
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                jacksonDetail, new IllegalStateException(jacksonDetail),
                new MockHttpInputMessage(new byte[0]));

        R<Void> result = handler.handleMessageNotReadable(ex);
        String body = result.getCode() + "|" + result.getErrorKey() + "|" + result.getMsg();

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getErrorKey()).isEqualTo("common.request_body_unreadable");
        assertThat(result.getEventRef())
                .as("事件引用必须是服务端权威值，不采用客户端头")
                .startsWith("req-");
        assertNoMarkerInBody(body);

        assertThat(loggedDiagnostics())
                .as("Jackson 原文必须保留在日志中供定位")
                .contains(MARKER_CLASS)
                .contains(result.getEventRef());
    }

    @Test
    @DisplayName("参数类型不匹配：Java 参数名只进日志，响应只给可行动结论")
    void typeMismatch_shouldNotEchoJavaParameterName() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                MARKER_SQL, Long.class, MARKER_FIELD, null,
                new IllegalArgumentException(MARKER_CLASS + " conversion failed"));

        R<Void> result = handler.handleTypeMismatch(ex);
        String body = result.getCode() + "|" + result.getErrorKey() + "|" + result.getMsg();

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getErrorKey()).isEqualTo("common.param_type_mismatch");
        assertNoMarkerInBody(body);
        assertThat(loggedDiagnostics())
                .as("原始参数名与取值必须保留在日志中供定位")
                .contains(MARKER_FIELD)
                .contains(result.getEventRef());
    }

    @Test
    @DisplayName("Bean 校验失败：Java 字段名与多条失败只进日志，响应给业务化提示")
    void validation_shouldNotEchoJavaFieldName() {
        Object target = new Object();
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, "request");
        // Java 字段名带受控标记（必须只进日志）；默认消息是面向用户的作者文案（应原样展示）
        binding.addError(new FieldError("request", MARKER_FIELD, "必填项不能为空"));
        binding.addError(new FieldError("request", MARKER_CLASS, "长度超出上限"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);

        R<Void> result = handler.handleValidation(ex);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getErrorKey()).isEqualTo("common.validation_failed");
        assertThat(result.getMsg())
                .startsWith("表单填写有误")
                .contains("必填项不能为空")
                .doesNotContain(MARKER_FIELD)
                .doesNotContain("internalFieldName")
                .doesNotContain(MARKER_CLASS)
                .doesNotContain("Exception");
        assertThat(loggedDiagnostics())
                .as("全部字段级失败必须保留在日志中（含 Java 字段名）")
                .contains(MARKER_FIELD)
                .contains(result.getEventRef());
    }

    @Test
    @DisplayName("系统故障：栈只进日志，响应给安全结论与同一事件引用")
    void unexpectedException_shouldKeepStackOutOfResponse() {
        RuntimeException ex = new RuntimeException(MARKER_CLASS + " 崩溃：" + MARKER_SQL,
                new IllegalStateException(MARKER_TENANT));

        R<Void> result = handler.handleException(ex);
        String body = result.getCode() + "|" + result.getErrorKey() + "|" + result.getMsg();

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getErrorKey()).isEqualTo("common.system_error");
        assertThat(result.getEventRef())
                .as("事件引用必须是服务端权威值，不采用客户端头")
                .startsWith("req-");
        assertNoMarkerInBody(body);
        assertThat(loggedDiagnostics())
                .as("完整栈与根因必须保留在日志中供定位")
                .contains(MARKER_CLASS)
                .contains(MARKER_SQL)
                .contains(MARKER_TENANT)
                .contains(result.getEventRef());
    }

    @Test
    @DisplayName("业务异常：errorKey 与 eventRef 同时外显，文案原样保留")
    void baseException_shouldExposeErrorKeyAndEventRef() {
        com.sw.ck.common.exception.BaseException ex = new com.sw.ck.common.exception.BaseException(
                com.sw.ck.form.api.exception.FormErrorCode.VERSION_CONFLICT);

        R<Void> result = handler.handleBaseException(ex);

        assertThat(result.getCode()).isEqualTo(1508);
        assertThat(result.getErrorKey()).isEqualTo("form.version_conflict");
        assertThat(result.getEventRef())
                .as("事件引用必须是服务端权威值，不采用客户端头")
                .startsWith("req-");
        assertThat(result.getMsg()).isEqualTo(com.sw.ck.form.api.exception.FormErrorCode
                .VERSION_CONFLICT.getMessage());
    }

    @Test
    @DisplayName("已认证无权限：403 结论可行动且不披露所需权限点")
    void authorizationDenied_shouldNotRevealRequiredPermission() {
        R<Void> result = handler.handleAuthorizationDenied(
                new AuthorizationDeniedException("Access Denied for " + MARKER_CLASS));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getErrorKey()).isEqualTo("common.forbidden");
        assertThat(result.getMsg()).contains("请联系管理员");
        assertNoMarkerInBody(result.getMsg());
    }

    @Test
    @DisplayName("事件引用不参与响应注入：非法 X-Request-Id 被丢弃")
    void eventRef_shouldRejectInjectedValue() {
        bindRequest("evil\"}\n" + MARKER_SQL);

        R<Void> result = handler.handleException(new IllegalStateException("boom"));

        assertThat(result.getEventRef())
                .as("非法请求头不得成为事件引用")
                .startsWith("req-");
        assertNoMarkerInBody(String.valueOf(result.getEventRef()));
    }

    @Test
    @DisplayName("缺少必需参数：不落入 500，且不暴露参数名以外的内部信息")
    void missingParameter_shouldNotLeakInternals() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException(
                MARKER_FIELD, "String");

        R<Void> result = handler.handleException(ex);

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getErrorKey()).isEqualTo("common.system_error");
        assertNoMarkerInBody(result.getMsg());
        assertThat(loggedDiagnostics())
                .contains(MARKER_FIELD)
                .contains(result.getEventRef());
    }
}
