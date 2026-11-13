package com.sw.ck.agent.config;

import com.sw.ck.agent.service.AgentGraphExecutionLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 人工节点超时扫描调度（G3-3）。
 * <p>
 * 单节点应用内轻量扫描：按 {@code sw.agent.human-task.timeout-scan-ms}（默认 30s）检查
 * PENDING 人工任务的超时配置，超时后以 TIMEOUT 决策恢复执行（无超时边由解释器失败收尾）。
 * 可通过 {@code sw.agent.human-task.scheduler-enabled=false} 关闭（测试/多实例部署时）。
 * </p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sw.agent", name = "enabled", havingValue = "true")
@EnableScheduling
public class AgentHumanTaskSchedulingConfig {

    @Bean
    @ConditionalOnProperty(prefix = "sw.agent.human-task", name = "scheduler-enabled",
            havingValue = "true", matchIfMissing = true)
    public HumanTaskTimeoutScanner humanTaskTimeoutScanner(
            AgentGraphExecutionLifecycleService lifecycleService) {
        return new HumanTaskTimeoutScanner(lifecycleService);
    }

    /** 超时扫描器（独立 Bean，便于测试替换与关闭）。 */
    public static class HumanTaskTimeoutScanner {
        private static final Logger log = LoggerFactory.getLogger(HumanTaskTimeoutScanner.class);

        private final AgentGraphExecutionLifecycleService lifecycleService;

        public HumanTaskTimeoutScanner(AgentGraphExecutionLifecycleService lifecycleService) {
            this.lifecycleService = lifecycleService;
        }

        @Scheduled(fixedDelayString = "${sw.agent.human-task.timeout-scan-ms:30000}",
                initialDelayString = "${sw.agent.human-task.timeout-initial-delay-ms:15000}")
        public void scan() {
            try {
                lifecycleService.scanHumanTaskTimeouts();
            } catch (RuntimeException scanFailure) {
                log.warn("人工任务超时扫描失败: {}", scanFailure.getMessage());
            }
        }
    }
}
