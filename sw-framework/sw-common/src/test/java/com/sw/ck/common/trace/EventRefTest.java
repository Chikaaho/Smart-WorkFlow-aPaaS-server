package com.sw.ck.common.trace;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P61 R5：事件引用由服务端权威生成的行为证据。
 *
 * <p>钉死规划审查 01 指出的两个风险：客户端复用同一 {@code X-Request-Id} 不得造成
 * 「一个引用对应多个事件」的歧义；客户端也不能通过该头伪造与服务端日志的关联。</p>
 */
class EventRefTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private HttpServletRequest bind(String headerValue, String serverRequestId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(EventRef.HEADER)).thenReturn(headerValue);
        when(request.getRequestId()).thenReturn(serverRequestId);
        // 让 mock 具备真实的请求属性行为：EventRef 依赖属性在请求内缓存引用
        java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        when(request.getAttribute(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> attributes.get((String) inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            attributes.put((String) inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    @DisplayName("同一请求内多次取值返回同一引用（便于一次失败多处落引用）")
    void current_shouldBeStableWithinOneRequest() {
        HttpServletRequest request = bind("web-abc", "tomcat-1");
        String first = EventRef.current();
        assertEquals(first, EventRef.current());
        assertEquals(first, EventRef.current());
        Mockito.verify(request, Mockito.atMostOnce()).getRequestId();
    }

    @Test
    @DisplayName("同值重放：两次请求携带完全相同的 X-Request-Id，事件引用必须不同（防歧义）")
    void current_shouldDifferAcrossRequestsDespiteReplayedHeader() {
        bind("web-replayed-value", "server-a");
        String first = EventRef.current();

        RequestContextHolder.resetRequestAttributes();
        bind("web-replayed-value", "server-b");
        String second = EventRef.current();

        assertNotEquals(first, second,
                "客户端复用同一请求头不得产生同一事件引用，否则一个引用对应多个事件");
    }

    @Test
    @DisplayName("事件引用由服务端生成，不取客户端头的值（防伪造关联）")
    void current_shouldUseServerValueNotClientHeader() {
        bind("web-forged-by-client", "tomcat-generated-id");
        String resolved = EventRef.current();
        assertTrue(resolved.startsWith("req-"), "引用应由服务端生成: " + resolved);
        assertNotEquals("web-forged-by-client", resolved);
        assertNotEquals("tomcat-generated-id", resolved,
                "容器 request id 跨请求可重复，不得作为事件引用（R6 真实容器发现）");
    }

    @Test
    @DisplayName("容器未提供 request id 时由服务端随机生成，仍不采用客户端头的值")
    void current_shouldGenerateWhenServerIdMissing() {
        bind("web-whatever", null);
        String resolved = EventRef.current();
        assertNotNull(resolved);
        assertTrue(resolved.startsWith("req-"), "应使用服务端生成的引用: " + resolved);
        assertEquals("web-whatever", "web-whatever");
        assertNotEquals("web-whatever", resolved);
    }

    @Test
    @DisplayName("容器 request id 超长时不采用，回退服务端生成")
    void current_shouldRejectOverlongServerId() {
        bind("web-x", "s".repeat(EventRef.MAX_LENGTH + 1));
        String resolved = EventRef.current();
        assertTrue(resolved.startsWith("req-"), "超长服务端 id 应回退为生成值: " + resolved);
    }

    @Test
    @DisplayName("无请求上下文（后台/异步线程）返回 null，不伪造标识")
    void current_shouldReturnNullWithoutRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        assertNull(EventRef.current());
    }

    @Test
    @DisplayName("服务端 request id 为空白时回退生成值")
    void current_shouldRejectBlankServerId() {
        bind(null, "   ");
        String resolved = EventRef.current();
        assertNotNull(resolved);
        assertTrue(resolved.startsWith("req-"), resolved);
    }

    @Test
    @DisplayName("R6 真实容器发现：容器 request id 是短小可重复值时仍由服务端生成唯一引用")
    void current_shouldGenerateEvenWhenContainerIdIsRepetitive() {
        // 内嵌 Tomcat 的 requestId 返回每连接自增的小整数（如 "1"），跨请求会重复
        HttpServletRequest request = bind("web-anything", "1");
        String first = EventRef.current();

        RequestContextHolder.resetRequestAttributes();
        HttpServletRequest secondRequest = bind("web-anything", "1");
        String second = EventRef.current();

        assertNotEquals(first, second, "容器短 id 跨请求重复时必须仍生成不同引用");
        assertTrue(first.startsWith("req-") && second.startsWith("req-"),
                "引用应由服务端生成: " + first + " / " + second);
        Mockito.verify(secondRequest, Mockito.never()).getHeader(EventRef.HEADER);
    }

    private void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
