package com.sw.ck.system.service;

import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysAuditLog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

import lombok.extern.log4j.Log4j2;

/**
 * 日志等级管理（1.2.0 P41 / E2-1、E2-2、E2-3）：直接复用 Spring Boot
 * {@link LoggingSystem}（不换日志框架），查看配置等级与实际生效等级、
 * 即时调整（TRACE—OFF）与恢复默认；每次调整写操作审计（谁、哪个 logger、
 * 从什么等级改成什么等级）。调整重启后不保留——重启恢复配置文件中的等级
 * （LoggingSystem 内存态语义，E2-4），页面上已写明。
 */
@Service
@Log4j2
public class LogLevelService {

    /** 合法等级（含 OFF；恢复默认经 setLevel(null) 表达，不占枚举位）。 */
    private static final List<String> SUPPORTED_LEVELS = List.of(
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    @Autowired(required = false)
    private LoggingSystem loggingSystem;

    @Autowired
    private AuditLogService auditLogService;

    /** 应用配置文件的根日志等级（首次调用时快照；ROOT「恢复默认」显式回落到它——
     *  Log4j2 对 ROOT 清配置（null）不改变运行值，无法像普通 logger 那样继承父级）。 */
    private volatile LogLevel rootDefaultLevel;

    private LoggingSystem system() {
        if (loggingSystem != null) {
            return loggingSystem;
        }
        return LoggingSystem.get(Thread.currentThread().getContextClassLoader());
    }

    public List<String> supportedLevels() {
        return SUPPORTED_LEVELS;
    }

    /** logger 列表（可按名称模糊过滤，大小写不敏感；ROOT 在首位）。 */
    public List<LoggerConfiguration> list(String keyword) {
        List<LoggerConfiguration> loggers = system().getLoggerConfigurations();
        if (keyword == null || keyword.isBlank()) {
            return loggers;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        return loggers.stream()
                .filter(logger -> logger.getName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /**
     * 调整（或恢复默认）单个 logger 等级，立即生效并写审计。
     *
     * @param loggerName logger 名称（包或类；ROOT 为根）
     * @param level      目标等级（TRACE—OFF）；null/空 = 恢复配置文件默认
     * @return 调整后的配置等级（恢复默认时为 null）
     */
    public LogLevel setLevel(String loggerName, String level) {
        if (loggerName == null || loggerName.isBlank()) {
            throw new IllegalArgumentException("logger 名称不能为空");
        }
        LogLevel target = null;
        if (level != null && !level.isBlank()) {
            String normalized = level.trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_LEVELS.contains(normalized)) {
                throw new IllegalArgumentException("不支持的日志等级: " + level
                        + "（合法值 " + String.join("/", SUPPORTED_LEVELS) + " 或留空恢复默认）");
            }
            target = LogLevel.valueOf(normalized);
        }
        LoggingSystem system = system();
        // 首次管理动作前快照根级别（此时 ROOT 一定未被本服务调整过，避免快照被改值污染）
        if (rootDefaultLevel == null) {
            resolveRootDefaultLevel(system);
        }
        boolean isRoot = LoggingSystem.ROOT_LOGGER_NAME.equalsIgnoreCase(loggerName)
                || "ROOT".equalsIgnoreCase(loggerName);
        if (isRoot && target == null) {
            // planning-review-120-01 批内缺陷修复：ROOT 恢复默认 = 显式回落到应用配置文件根级别
            // （Log4j2 对 ROOT setLevel(null) 不改变运行值，evidence 见 execution-120-04 §5）
            target = resolveRootDefaultLevel(system);
        }
        // E2 审计与返回口径（iso120 1.2.0 实测）：Log4j2 对 ROOT 的 configuredLevel 恒为 null
        // 且部分运行环境下 getLoggerConfiguration 查询不可靠——审计/返回直接采用
        // 目标值语义：after = 显式目标（target=null 恢复默认时 = 配置根级别/继承语义）
        LogLevel before = effectiveLevelOf(system, loggerName);
        system.setLogLevel(loggerName, target);
        LogLevel after;
        if (target != null) {
            after = target;
        } else if (isRoot) {
            after = resolveRootDefaultLevel(system);
        } else {
            after = effectiveLevelOf(system, loggerName);
            if (after == target) {
                after = null; // 非 ROOT 恢复默认：配置清除，读侧按继承展示
            }
        }
        writeAudit(loggerName, before, after);
        log.info("日志等级调整: logger={}, {} -> {}",
                loggerName, before == null ? "默认" : before, after == null ? "默认" : after);
        return after;
    }

    /**
     * 应用配置文件根等级快照：首次需要时取当前 ROOT 生效等级——本服务只在管理动作中
     * 调用，首个动作前 ROOT 未被运行时调整过，effectiveLevel 即配置文件根级别。
     */
    /** 生效等级读取（ROOT 的 configuredLevel 在 Log4j2 下恒 null，审计/返回用生效值）。 */
    private LogLevel effectiveLevelOf(LoggingSystem system, String loggerName) {
        LoggerConfiguration configuration = system.getLoggerConfiguration(loggerName);
        if (configuration == null) {
            return null;
        }
        return configuration.getEffectiveLevel() == null
                ? configuration.getConfiguredLevel() : configuration.getEffectiveLevel();
    }

    private LogLevel resolveRootDefaultLevel(LoggingSystem system) {
        if (rootDefaultLevel == null) {
            LoggerConfiguration root = system.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
            rootDefaultLevel = root == null || root.getEffectiveLevel() == null
                    ? LogLevel.INFO : root.getEffectiveLevel();
        }
        return rootDefaultLevel;
    }

    /** E2-3 操作日志：谁、哪个 logger、从什么等级改成什么等级（尽力而为，不阻断调整）。 */
    private void writeAudit(String loggerName, LogLevel before, LogLevel after) {
        try {
            LoginUser operator = LoginUserHolder.get();
            SysAuditLog entry = new SysAuditLog();
            entry.setLogType("OP");
            entry.setUserId(operator == null ? null : operator.getUserId());
            entry.setUsername(operator == null || operator.getUsername() == null
                    ? "unknown" : operator.getUsername());
            entry.setResult("SUCCESS");
            entry.setModule("SYSTEM");
            entry.setAction("LOG_LEVEL");
            entry.setTarget(loggerName);
            entry.setSummary("日志等级调整: " + (before == null ? "默认" : before)
                    + " → " + (after == null ? "默认" : after)
                    + "（重启后恢复配置文件等级）");
            auditLogService.record(entry);
        } catch (Exception ignored) {
            // 审计尽力而为；等级调整本身已生效
        }
    }
}
