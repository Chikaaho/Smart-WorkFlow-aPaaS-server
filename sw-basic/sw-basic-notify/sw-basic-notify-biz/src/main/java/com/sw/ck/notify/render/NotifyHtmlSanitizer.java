package com.sw.ck.notify.render;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通知 HTML/卡片净化（I6）。
 * <p>禁止脚本、事件属性与任意协议跳转：移除 {@code <script>}／内联事件属性／
 * {@code javascript:}、「data:」URI；保留其余纯文本结构。</p>
 */
public final class NotifyHtmlSanitizer {

    private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>.*?</script>|<script[^>]*/?>");
    private static final Pattern EVENT_ATTRS = Pattern.compile("(?is)\\son[a-z]+(\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?");
    private static final Pattern SCHEME = Pattern.compile("(?is)(href|src)\\s*=\\s*(\"|')\\s*(javascript|data|vbscript):[^\"']*(\"|')");

    private NotifyHtmlSanitizer() {
    }

    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String out = SCRIPT.matcher(html).replaceAll("");
        out = EVENT_ATTRS.matcher(out).replaceAll("");
        out = SCHEME.matcher(out).replaceAll("$1=\"\"");
        return out;
    }
}
