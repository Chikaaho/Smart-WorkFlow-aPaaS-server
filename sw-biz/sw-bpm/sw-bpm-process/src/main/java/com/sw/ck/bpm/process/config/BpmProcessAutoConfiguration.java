package com.sw.ck.bpm.process.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

import lombok.extern.log4j.Log4j2;
/**
 * BPM 业务处理层自动配置。
 * <p>
 * 负责流程编排、待办富化、表单绑定等业务处理逻辑的装配门控。
 * 默认关闭，通过 sw.bpm.enabled=true 开启。
 * 注意：并存期本配置不写入 AutoConfiguration.imports，不参与运行时装配。
 * </p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sw.bpm", name = "enabled", havingValue = "true")
@Log4j2
public class BpmProcessAutoConfiguration {

    public BpmProcessAutoConfiguration(Environment environment) {
        log.info("BPM process auto-configuration started (business processing layer)");
    }
}
