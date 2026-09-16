package com.sw.ck.common.trace;

/**
 * 持久化诊断文本的脱敏出口（P61 §3.2）。
 *
 * <p>若干业务列（通知失败原因、IoT 连接健康、消息解析错误、脚本执行错误等）由管理页面
 * 回读，既需要保留可运维信息，又不能把服务端环境细节带给查看者。本类是这些列的
 * <b>唯一</b>清洗入口，避免各模块各写一套规则。</p>
 *
 * <p>清洗内容：</p>
 * <ul>
 *   <li>去除 Java 栈帧行（{@code at ...}）与异常类名前缀；</li>
 *   <li>把绝对路径（Windows 盘符与 POSIX）替换为 {@code <path>}；</li>
 *   <li>折叠空白并截断到上限。</li>
 * </ul>
 *
 * <p>需要完整原文时，调用方应写结构化日志并把 {@link EventRef} 一并落库，
 * 由授权运维按引用还原，而不是扩大本方法的放行范围。</p>
 */
public final class DiagnosticText {

    /** 诊断摘要默认上限，避免超长文本污染管理列表。 */
    public static final int DEFAULT_MAX_LENGTH = 500;

    private DiagnosticText() {
    }

    /**
     * 清洗诊断文本。
     *
     * @param raw 原始诊断文本，可为 {@code null}
     * @return 清洗后的摘要；输入为 {@code null} 或清洗后为空时返回 {@code null}
     */
    public static String sanitize(String raw) {
        return sanitize(raw, DEFAULT_MAX_LENGTH);
    }

    /**
     * 清洗并截断诊断文本。
     *
     * @param raw 原始诊断文本，可为 {@code null}
     * @param maxLength 结果长度上限
     * @return 清洗后的摘要；输入为 {@code null} 或清洗后为空时返回 {@code null}
     */
    public static String sanitize(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String withoutStack = raw.lines()
                .filter(line -> !line.stripLeading().startsWith("at "))
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        String sanitized = withoutStack
                // 异常类名前缀（含包名）不携带运维价值，且会暴露实现细节
                .replaceAll("(?:[a-z][a-zA-Z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(?:Exception|Error):\\s*", "")
                // 绝对路径：Windows 盘符与 POSIX 两级以上
                .replaceAll("[A-Za-z]:\\\\[^\\s:;]*", "<path>")
                .replaceAll("(/[\\w.\\-]+){2,}", "<path>")
                .replaceAll("\\s+", " ")
                .trim();
        // 中文之间不需要空格：折叠空白后把两汉字之间的间隔去掉，避免摘要出现断字。
        // 用前瞻匹配，避免连续间隔因非重叠扫描而漏替。
        sanitized = sanitized.replaceAll("([\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])", "$1");
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }
}
