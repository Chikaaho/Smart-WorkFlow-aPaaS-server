package com.sw.ck.bpm.engine.script;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScriptWorkerPool} 隔离执行池测试（审查02 P1-02a/P1-02b 实现证据）。
 * <p>
 * 真实 worker 进程（-Xmx128m）验证：执行空间堆上限真实生效（worker 堆耗尽→RESOURCE_LIMIT，
 * 池自动恢复）、wall clock 截止（本测试实际使用 500ms，非默认 5s）无遗留活判断、
 * 全局/租户并发上限与满额繁忙可恢复、上下文无遗留（同一池继续正常执行）。
 * 属于隔离资源测试，耗时显著高于普通单测。
 * </p>
 */
@DisplayName("P64 判断脚本隔离执行池测试")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptWorkerPoolTest {

    private final ScriptWorkerPool pool = new ScriptWorkerPool();

    @AfterAll
    void shutdown() {
        pool.shutdown();
    }

    @Test
    @DisplayName("正常判断经 worker 进程返回正确结果")
    void shouldEvaluateNormallyInWorkerProcess() {
        pool.configure(2, 1, 200);
        BpmScriptRunner.Result result = pool.evaluate(
                "return 流程变量取值('v') === 'REWORK' ? 'REWORK' : 'NORMAL';",
                Map.of("v", "REWORK"), 5_000L, 9L);
        assertThat(result.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(result.value()).isEqualTo("REWORK");
    }

    @Test
    @DisplayName("128MiB 执行空间真实生效：超限分配致 worker 堆耗尽→RESOURCE_LIMIT，池恢复后继续正常执行")
    void shouldCapExecutionSpaceAt128MiBAndRecover() {
        pool.configure(2, 1, 200);
        // 持有式大数组分配（总量远超 128m worker 堆）：宿主 JVM 默认堆远大于此，仅 worker 隔离空间受限
        BpmScriptRunner.Result exhausted = pool.evaluate(
                "const a = []; while (true) { a.push('x'.repeat(1000000)); }",
                Map.of(), 30_000L, 9L);
        assertThat(exhausted.kind()).isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
        // 真实堆上限的根因诊断：worker 内堆耗尽（Java heap space），非语句上限/宿主堆
        assertThat(exhausted.errorMessage()).contains("Java heap space");
        // 128MiB 配置核对：worker 启动握手自报本 JVM 堆上限 = 134217728 字节（-Xmx128m 实际生效）
        assertThat(pool.lastSpawnMaxHeapBytes())
                .as("worker 自报堆上限应为 128MiB")
                .isEqualTo(128L * 1024 * 1024);
        // 池自动恢复（新 worker 顶替失效进程），后续判断零影响
        BpmScriptRunner.Result after = pool.evaluate("return 1;", Map.of(), 5_000L, 9L);
        assertThat(after.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
    }

    @Test
    @DisplayName("wall clock 截止：超时判 TIMEOUT（worker 内 watcher 或宿主销毁兜底）、有界返回、无遗留活判断")
    void shouldTimeoutAndLeaveNoLiveJudgement() {
        pool.configure(2, 1, 200);
        // 重宿主交互循环（大快照、低分配速率、语句数极少）：wall clock 先于语句上限/内存上限，
        // 本测试实际 timeoutMs=500（默认 5s）；宿主另有 +1000ms 兜底销毁，二者都判 TIMEOUT
        String script = "let r = 0; for (let i = 0; i < 2000; i++) { r = 流程变量取值('big').length; } return r;";
        long begin = System.currentTimeMillis();
        BpmScriptRunner.Result timeout = pool.evaluate(script, Map.of("big", "x".repeat(500_000)), 500L, 9L);
        long elapsed = System.currentTimeMillis() - begin;
        assertThat(timeout.kind()).as("errorMessage=%s durationMs=%d", timeout.errorMessage(), timeout.durationMs())
                .isEqualTo(BpmScriptRunner.Result.Kind.TIMEOUT);
        assertThat(timeout.errorMessage()).contains("超时");
        // 截止有界：500ms（watcher）或 1500ms（宿主兜底）量级，远早于无界等待
        assertThat(elapsed).isBetween(400L, 6_000L);
        // 无遗留：同一池对新判断恢复服务（失效 worker 已销毁并顶替）
        BpmScriptRunner.Result after = pool.evaluate("return 1;", Map.of(), 5_000L, 9L);
        assertThat(after.kind()).as("errorMessage=%s", after.errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
    }

    @Test
    @DisplayName("全局/租户并发上限：同租户并发满额判繁忙（可恢复），不同租户不受影响")
    void shouldEnforceTenantAndGlobalConcurrencyCapsWithBusyRejection() throws Exception {
        // 池上限 2、单租户 1：租户 A 的慢判断占用其唯一许可，同租户第二判断→繁忙；租户 B 正常
        pool.configure(2, 1, 20);
        // 受控慢判断（语句数上限内、无堆压力）：占用许可窗口显著长于探测间隔
        String slowScript = "let s = 0; for (let i = 0; i < 6000; i++) "
                + "{ s = s + 流程变量取值('slow').length; } return s;";
        AtomicReference<BpmScriptRunner.Result> slowResult = new AtomicReference<>();
        Thread tenantA = new Thread(() -> slowResult.set(
                pool.evaluate(slowScript, Map.of("slow", "x".repeat(6_000)), 10_000L, 1L)));
        tenantA.start();
        // 先确认租户 A 已实际占用许可，再探测：避免探测抢先取得许可的竞态
        long deadline = System.currentTimeMillis() + 10_000L;
        while (pool.heldTenantPermits(1L) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pool.heldTenantPermits(1L)).as("租户 A 慢判断应已占用其许可").isEqualTo(1);
        BpmScriptRunner.Result busy = pool.evaluate("return 1;", Map.of(), 5_000L, 1L);
        assertThat(busy.kind()).as("errorMessage=%s", busy.errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
        assertThat(busy.errorMessage()).contains("繁忙");
        // 不同租户不受该租户限额影响，且池上限 2 仍有余量
        BpmScriptRunner.Result otherTenant = pool.evaluate("return 2;", Map.of(), 5_000L, 2L);
        assertThat(otherTenant.kind()).as("errorMessage=%s", otherTenant.errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(((Number) otherTenant.value()).intValue()).isEqualTo(2);
        // 慢判断自身正常完成（许可非泄漏），结束后同租户恢复可执行
        tenantA.join();
        assertThat(slowResult.get().kind()).as("slow msg=%s", slowResult.get().errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(pool.heldTenantPermits(1L)).isZero();
        assertThat(pool.evaluate("return 3;", Map.of(), 5_000L, 1L).kind())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
    }
}
