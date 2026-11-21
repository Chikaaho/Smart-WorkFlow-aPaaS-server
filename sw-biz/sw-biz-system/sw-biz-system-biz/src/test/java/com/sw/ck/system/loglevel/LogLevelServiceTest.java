package com.sw.ck.system.loglevel;

import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.service.AuditLogService;
import com.sw.ck.system.service.LogLevelService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LogLevelService} 行为验证（1.2.0 E2-1/E2-2/E2-3）：
 * 真实 {@link LoggingSystem}（Log4j2）上的查看过滤、即时调整与恢复默认、
 * 非法等级拒绝、每次调整写操作审计（谁改哪个 logger、从什么等级到什么等级）。
 */
@ExtendWith(MockitoExtension.class)
class LogLevelServiceTest {

    private static final String TEST_LOGGER = "com.sw.ck.system.loglevel.it";

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private LogLevelService service;

    private LoggingSystem realSystem() {
        LoggingSystem system = LoggingSystem.get(Thread.currentThread().getContextClassLoader());
        ReflectionTestUtils.setField(service, "loggingSystem", system);
        return system;
    }

    @Test
    @DisplayName("列表非空且可按名称过滤；调整后配置/生效等级立即可见")
    void listAndAdjust() {
        LoggingSystem system = realSystem();
        assertThat(service.list(null)).isNotEmpty();

        LogLevel after = service.setLevel(TEST_LOGGER, "DEBUG");
        assertThat(after).isEqualTo(LogLevel.DEBUG);
        assertThat(service.list(TEST_LOGGER)).hasSize(1);
        assertThat(service.list(TEST_LOGGER).get(0).getConfiguredLevel()).isEqualTo(LogLevel.DEBUG);
        assertThat(service.list(TEST_LOGGER).get(0).getEffectiveLevel()).isEqualTo(LogLevel.DEBUG);
        assertThat(service.list("loglevel.it")).extracting(
                configuration -> configuration.getName()).containsExactly(TEST_LOGGER);
    }

    @Test
    @DisplayName("level 缺省恢复默认：配置等级回到 null 并写审计")
    void resetToDefault() {
        realSystem();
        service.setLevel(TEST_LOGGER, "WARN");
        LogLevel after = service.setLevel(TEST_LOGGER, null);
        assertThat(after).isNull();
        assertThat(service.list(TEST_LOGGER).get(0).getConfiguredLevel()).isNull();
    }

    @Test
    @DisplayName("非法等级拒绝；合法集合为 TRACE—OFF")
    void rejectsInvalidLevel() {
        realSystem();
        assertThatThrownBy(() -> service.setLevel(TEST_LOGGER, "VERBOSE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERBOSE");
        assertThat(service.supportedLevels()).containsExactly(
                "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");
    }

    @Test
    @DisplayName("每次调整写操作审计：logger、从→到、重启语义")
    void writesAuditOnEachAdjust() {
        realSystem();
        // 先定锚 DEBUG 再调 INFO，断言不依赖其他用例执行顺序
        service.setLevel(TEST_LOGGER, "DEBUG");
        service.setLevel(TEST_LOGGER, "INFO");
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        org.mockito.Mockito.verify(auditLogService, org.mockito.Mockito.times(2))
                .record(captor.capture());
        SysAuditLog entry = captor.getAllValues().get(1);
        assertThat(entry.getAction()).isEqualTo("LOG_LEVEL");
        assertThat(entry.getTarget()).isEqualTo(TEST_LOGGER);
        assertThat(entry.getSummary()).contains("DEBUG → INFO").contains("重启后恢复");
    }
}
