package com.sw.ck.agent.orchestration;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Agent 执行失败摘要（P61 §3.2）。
 *
 * <p>执行失败摘要会进入会话/执行记录并展示给使用者，因此不能直接透出模型服务商、
 * HTTP 客户端或运行时的原始异常文本（含主机名、鉴权响应、类名）。判别<b>按异常类型</b>，
 * 不按文案：</p>
 * <ul>
 *   <li>平台业务异常（{@link BaseException}）→ 其文案按构造已满足公共消息边界，原样使用；</li>
 *   <li>传输/服务商类异常（IO、HTTP 客户端、模型 SDK）→ 归类后的安全结论；</li>
 *   <li>其余（本编排器自身抛出的状态/校验失败）→ 保留原始文案，供图设计者定位。</li>
 * </ul>
 * <p>被替换掉的原始原因写结构化日志并带事件引用，便于运维按引用还原。</p>
 */
public final class AgentFailureSummarizer {

    private static final Logger log = LoggerFactory.getLogger(AgentFailureSummarizer.class);

    /** 传输/服务商异常类型名特征；按类型判定，不解析文案。 */
    private static final List<String> TRANSPORT_TYPE_HINTS = List.of(
            "IOException", "SocketException", "TimeoutException", "UnknownHostException",
            "RestClient", "WebClient", "ResourceAccess", "NonTransientAi", "TransientAi",
            "HttpServerError", "HttpClientError", "ConnectException");

    private AgentFailureSummarizer() {
    }

    /** 生成可对外展示的失败摘要。 */
    public static String summarize(Throwable t) {
        if (t == null) {
            return FailureCategory.SYSTEM_FAULT.getDefaultMessage();
        }
        Throwable cur = t;
        String deepestMessage = null;
        boolean hasBusiness = false;
        boolean hasTransport = false;
        while (cur != null) {
            if (cur instanceof BaseException) {
                hasBusiness = true;
            }
            if (isTransport(cur)) {
                hasTransport = true;
            }
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                deepestMessage = cur.getMessage();
            }
            cur = cur.getCause();
        }
        if (hasBusiness) {
            return deepestMessage;
        }
        if (hasTransport) {
            String eventRef = com.sw.ck.common.trace.EventRef.current();
            log.warn("Agent 执行失败（传输/服务商类）: eventRef={} type={} detail={}",
                    eventRef, t.getClass().getSimpleName(), deepestMessage, t);
            return contentWithRef(
                    "模型服务暂时不可用，请稍后重试；若持续出现请联系管理员", eventRef);
        }
        // 本编排器自身的状态/校验失败：文案面向设计者，保留以维持可定位性
        return deepestMessage != null ? deepestMessage : FailureCategory.SYSTEM_FAULT.getDefaultMessage();
    }

    private static boolean isTransport(Throwable t) {
        if (t instanceof IOException) {
            return true;
        }
        String name = t.getClass().getSimpleName();
        for (String hint : TRANSPORT_TYPE_HINTS) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String contentWithRef(String message, String eventRef) {
        return eventRef == null ? message : message + "（事件引用 " + eventRef + "）";
    }
}
