package com.sw.ck.common.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P61 阶段 D：持久化诊断文本脱敏出口的常驻回归测试。
 *
 * <p>通知失败原因、IoT 连接健康、脚本执行错误等列由管理页面回读。本测试钉死：
 * 栈帧、异常类名与服务端绝对路径不得出现在这些列里，同时保留可运维的语义。</p>
 */
class DiagnosticTextTest {

    @Test
    @DisplayName("栈帧行被去除，只保留结论")
    void sanitize_shouldDropStackFrames() {
        String raw = "java.lang.IllegalStateException: 连接失败\n"
                + "\tat com.sw.ck.iot.MqttBrokerManager.connect(MqttBrokerManager.java:126)\n"
                + "\tat com.sw.ck.iot.IotConnectionService.connect(IotConnectionService.java:184)";

        String sanitized = DiagnosticText.sanitize(raw);

        assertFalse(sanitized.contains("at com.sw.ck"), sanitized);
        assertFalse(sanitized.contains("MqttBrokerManager.java:126"), sanitized);
        assertTrue(sanitized.contains("连接失败"), sanitized);
    }

    @Test
    @DisplayName("异常类名前缀被去除（不暴露实现细节）")
    void sanitize_shouldDropExceptionClassPrefix() {
        String sanitized = DiagnosticText.sanitize(
                "org.springframework.dao.DuplicateKeyException: 唯一键冲突");
        assertFalse(sanitized.contains("DuplicateKeyException"), sanitized);
        assertTrue(sanitized.contains("唯一键冲突"), sanitized);
    }

    @Test
    @DisplayName("POSIX 与 Windows 绝对路径被替换，不暴露部署布局")
    void sanitize_shouldMaskAbsolutePaths() {
        String posix = DiagnosticText.sanitize("编译失败 /opt/app/scripts/tmp/Script123.java:12 错误");
        assertFalse(posix.contains("/opt/app/scripts"), posix);
        assertTrue(posix.contains("<path>"), posix);

        String windows = DiagnosticText.sanitize("编译失败 D:\\build\\jobs\\tmp\\Script123.java:12");
        assertFalse(windows.contains("D:\\build"), windows);
        assertTrue(windows.contains("<path>"), windows);
    }

    @Test
    @DisplayName("空白折叠并截断到上限；中文之间的多余空格被去除")
    void sanitize_shouldNormalizeAndBound() {
        assertEquals("失败原因是超时", DiagnosticText.sanitize("失败\n\n  原因   是 超时", 200));
        assertEquals("连接失败 timeout", DiagnosticText.sanitize("连接失败   timeout", 200));

        String longText = "x".repeat(600);
        assertEquals(100, DiagnosticText.sanitize(longText, 100).length());
    }

    @Test
    @DisplayName("null 与空白输入返回 null，不产生空串噪音")
    void sanitize_shouldReturnNullForBlankInput() {
        assertNull(DiagnosticText.sanitize(null));
        assertNull(DiagnosticText.sanitize("   "));
        assertNull(DiagnosticText.sanitize("\n\t"));
    }

    @Test
    @DisplayName("纯栈文本清洗后为空时返回 null，而不是残留帧片段")
    void sanitize_shouldReturnNullWhenOnlyStackRemains() {
        String raw = "异常\n\tat a.b.C.d(C.java:1)\n\tat e.f.G.h(G.java:2)";
        String sanitized = DiagnosticText.sanitize(raw);
        assertEquals("异常", sanitized);
        assertFalse(sanitized.contains("at "), sanitized);
    }
}
