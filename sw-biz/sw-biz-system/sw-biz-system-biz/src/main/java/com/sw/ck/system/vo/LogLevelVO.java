package com.sw.ck.system.vo;

import org.springframework.boot.logging.LoggerConfiguration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 日志等级条目（1.2.0 E2-1）：名称 + 配置等级（未显式配置为 null）+ 生效等级。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogLevelVO {

    /** logger 名称（包或类；ROOT 为根 logger）。 */
    private String name;
    /** 配置等级（未显式配置 = null，即继承上级）。 */
    private String configuredLevel;
    /** 实际生效等级。 */
    private String effectiveLevel;

    public static LogLevelVO of(LoggerConfiguration configuration) {
        return new LogLevelVO(
                configuration.getName(),
                configuration.getConfiguredLevel() == null
                        ? null : configuration.getConfiguredLevel().name(),
                configuration.getEffectiveLevel() == null
                        ? null : configuration.getEffectiveLevel().name());
    }
}
