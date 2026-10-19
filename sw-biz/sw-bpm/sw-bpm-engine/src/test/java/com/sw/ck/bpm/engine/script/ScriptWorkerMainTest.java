package com.sw.ck.bpm.engine.script;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScriptWorkerMain} worker 启动契约单测。
 * <p>
 * BF20261019-04：预热预算不再硬编码单次判断的 5s，而由宿主按
 * {@code sw.bpm.script.warmup-timeout-ms} 下发；解析失败或过小值不得让 worker 立即超时。
 * </p>
 */
@DisplayName("P64 worker 预热预算解析测试")
class ScriptWorkerMainTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("sw.bpm.script.warmup-timeout-ms");
    }

    @Test
    @DisplayName("BF20261019-04：系统属性合法值生效，非法值回退缺省，过小值钳制到 1s")
    void shouldParseWarmUpBudgetFromProperty() {
        System.setProperty("sw.bpm.script.warmup-timeout-ms", "100000");
        assertThat(ScriptWorkerMain.warmUpTimeoutMs()).isEqualTo(100_000L);

        System.setProperty("sw.bpm.script.warmup-timeout-ms", "abc");
        assertThat(ScriptWorkerMain.warmUpTimeoutMs())
                .isEqualTo(ScriptWorkerMain.DEFAULT_WARMUP_TIMEOUT_MS);

        System.setProperty("sw.bpm.script.warmup-timeout-ms", "10");
        assertThat(ScriptWorkerMain.warmUpTimeoutMs()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("BF20261019-04：未配置时使用缺省预热预算")
    void shouldFallBackToDefaultBudget() {
        assertThat(ScriptWorkerMain.warmUpTimeoutMs())
                .isEqualTo(ScriptWorkerMain.DEFAULT_WARMUP_TIMEOUT_MS);
    }
}
