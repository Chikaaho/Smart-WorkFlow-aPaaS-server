package com.sw.ck.common.trace;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 事件引用（event reference）：把用户看到的失败与服务端诊断记录关联起来的稳定标识。
 *
 * <p><b>P61 R5（规划审查 01）：事件引用由服务端权威生成</b>。客户端的
 * {@code X-Request-Id} 仍会被记录（见 {@code AccessLoggingFilter}）用于客户端侧关联，
 * 但<b>不再作为事件引用取值</b>——否则同一调用方可以在多次请求间复用同一值，
 * 造成「一个引用对应多个事件」的歧义，也能伪造与服务端日志的关联。</p>
 *
 * <p><b>R6 真实容器验证补充</b>：内嵌 Tomcat 的 {@code request.getRequestId()} 返回
 * 每连接自增的小整数（如 {@code "1"}），跨请求必然重复，因此<b>不可用作事件引用</b>。
 * 本类恒定生成 {@code req-} 前缀加 128 位随机数，同一请求内缓存复用，跨请求必然不同。</p>
 *
 * <p>与 {@code AccessLoggingFilter} 的 ACCESS 日志使用同一来源，因此
 * 「用户看到的事件引用 ↔ 访问日志 ↔ 诊断日志」仍可三方关联，只是关联键由服务端决定。</p>
 */
public final class EventRef {

    /** 客户端关联头：仅用于访问日志对照，不作为事件引用取值。 */
    public static final String HEADER = "X-Request-Id";

    /** 事件引用在请求内的缓存属性名。 */
    private static final String ATTRIBUTE = EventRef.class.getName() + ".ref";

    /** 事件引用长度上限（防御性，供调用方校验展示宽度）。 */
    public static final int MAX_LENGTH = 64;

    private static final SecureRandom RANDOM = new SecureRandom();

    private EventRef() {
    }

    /**
     * 解析本次请求的事件引用（服务端权威，每请求唯一）。
     *
     * @return 事件引用；无请求上下文时返回 {@code null}（后台/异步线程不伪造标识）
     */
    public static String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof String value && !value.isBlank()) {
            return value;
        }
        String generated = "req-" + HexFormat.of().formatHex(random128());
        request.setAttribute(ATTRIBUTE, generated);
        return generated;
    }

    private static byte[] random128() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
