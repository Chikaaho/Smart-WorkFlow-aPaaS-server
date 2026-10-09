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
    @DisplayName("128MiB 执行空间真实生效：超限分配致 worker 堆耗尽→RESOURCE_LIMIT，进程受管复用且池恢复")
    void shouldCapExecutionSpaceAt128MiBAndRecover() {
        // 独立池：pid/残留断言不受本类其他用例已启 worker 干扰
        ScriptWorkerPool local = new ScriptWorkerPool();
        try {
            local.configure(1, 1, 200);
            // 持有式大数组分配（总量远超 128m worker 堆）：宿主 JVM 默认堆远大于此，仅 worker 隔离空间受限
            BpmScriptRunner.Result exhausted = local.evaluate(
                    "const a = []; while (true) { a.push('x'.repeat(1000000)); }",
                    Map.of(), 30_000L, 9L);
            assertThat(exhausted.kind()).isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
            // 真实堆上限的根因诊断：worker 内堆耗尽（Java heap space），非语句上限/宿主堆
            assertThat(exhausted.errorMessage()).contains("Java heap space");
            // 128MiB 配置核对：worker 启动握手自报本 JVM 堆上限 = 134217728 字节（-Xmx128m 实际生效）
            assertThat(local.lastSpawnMaxHeapBytes())
                    .as("worker 自报堆上限应为 128MiB")
                    .isEqualTo(128L * 1024 * 1024);
            // 清理无残留：失败判断不占许可/等候名额，worker 进程保留并被复用（不新增进程、无孤儿）
            long workerPid = local.lastSpawnPid();
            assertThat(workerPid).isPositive();
            assertThat(local.heldTenantPermits(9L)).isZero();
            assertThat(local.queuedTenantCount(9L)).isZero();
            // 池/后续判断恢复：同一 worker 继续服务（pid 不变），判断结果正常
            BpmScriptRunner.Result after = local.evaluate("return 1;", Map.of(), 5_000L, 9L);
            assertThat(after.kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
            assertThat(local.lastSpawnPid()).as("复用既有 worker，不因超限另起进程").isEqualTo(workerPid);
            assertThat(ProcessHandle.of(workerPid).map(ProcessHandle::isAlive).orElse(false))
                    .as("池内 worker pid=%d 仍在池中受管（非孤儿）", workerPid).isTrue();
        } finally {
            local.shutdown();
        }
    }

    @Test
    @DisplayName("wall clock 截止：超时判 TIMEOUT（worker 内 watcher 取消/宿主销毁兜底）、有界返回、进程受管复用")
    void shouldTimeoutAndLeaveNoLiveJudgement() {
        ScriptWorkerPool local = new ScriptWorkerPool();
        try {
            local.configure(1, 1, 200);
            // 重宿主交互循环（大快照、低分配速率、语句数极少）：wall clock 先于语句上限/内存上限，
            // 本测试实际 timeoutMs=500（默认 5s）；宿主另有 +1000ms 兜底销毁，二者都判 TIMEOUT
            String script = "let r = 0; for (let i = 0; i < 2000; i++) { r = 流程变量取值('big').length; } return r;";
            long begin = System.currentTimeMillis();
            BpmScriptRunner.Result timeout = local.evaluate(script, Map.of("big", "x".repeat(500_000)), 500L, 9L);
            long elapsed = System.currentTimeMillis() - begin;
            assertThat(timeout.kind()).as("errorMessage=%s durationMs=%d", timeout.errorMessage(), timeout.durationMs())
                    .isEqualTo(BpmScriptRunner.Result.Kind.TIMEOUT);
            assertThat(timeout.errorMessage()).contains("超时");
            // 截止有界：500ms（watcher）或 1500ms（宿主兜底）量级，远早于无界等待
            assertThat(elapsed).isBetween(400L, 6_000L);
            // 实际清理：超时判断不占许可/等候名额；worker 内 watcher 取消后进程保留受管并被复用
            long workerPid = local.lastSpawnPid();
            assertThat(workerPid).isPositive();
            assertThat(local.heldTenantPermits(9L)).isZero();
            // 无遗留：同一池对新判断恢复服务（同一 worker，pid 不变）
            BpmScriptRunner.Result after = local.evaluate("return 1;", Map.of(), 5_000L, 9L);
            assertThat(after.kind()).as("errorMessage=%s", after.errorMessage())
                    .isEqualTo(BpmScriptRunner.Result.Kind.OK);
            assertThat(local.lastSpawnPid()).as("复用既有 worker，不因超时另起进程").isEqualTo(workerPid);
        } finally {
            local.shutdown();
        }
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

    @Test
    @DisplayName("等候数量上限=0：满额立即繁忙（不等待时限），释放后同租户恢复")
    void shouldRejectImmediatelyWhenTenantQueueCapacityIsZero() throws Exception {
        // 等候数量上限 0、等候时限 5s：立即拒绝路径必须与时限无关（否则会等满 5s）
        pool.configure(1, 1, 5_000, 0, 0);
        assertThat(pool.tenantQueueCapacity()).isZero();
        AtomicReference<BpmScriptRunner.Result> holder = new AtomicReference<>();
        Thread tenant = new Thread(() -> holder.set(
                pool.evaluate(slowScript(), Map.of("slow", "x".repeat(6_000)), 10_000L, 11L)));
        tenant.start();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (pool.heldTenantPermits(11L) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pool.heldTenantPermits(11L)).as("慢判断应已占用租户许可").isEqualTo(1);
        long begin = System.currentTimeMillis();
        BpmScriptRunner.Result busy = pool.evaluate("return 1;", Map.of(), 5_000L, 11L);
        long elapsed = System.currentTimeMillis() - begin;
        assertThat(busy.kind()).as("errorMessage=%s", busy.errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
        assertThat(busy.errorMessage()).contains("等候队列已满");
        assertThat(elapsed).as("上限 0 应立即拒绝，不等待 5000ms 时限").isLessThan(1_000L);
        assertThat(pool.queuedTenantCount(11L)).as("拒绝路径不占等候名额").isZero();
        tenant.join();
        assertThat(holder.get().kind()).as("slow msg=%s", holder.get().errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(pool.heldTenantPermits(11L)).isZero();
        assertThat(pool.evaluate("return 2;", Map.of(), 5_000L, 11L).kind())
                .as("释放后同租户恢复可执行")
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
    }

    @Test
    @DisplayName("等候数量上限=1：1 个等候者准入并最终成功，第 2 个立即繁忙，计数无泄漏")
    void shouldAdmitBoundedWaiterThenRejectOverflow() throws Exception {
        pool.configure(1, 1, 8_000, 2, 1);
        assertThat(pool.tenantQueueCapacity()).isEqualTo(1);
        AtomicReference<BpmScriptRunner.Result> holder = new AtomicReference<>();
        Thread tenant = new Thread(() -> holder.set(
                pool.evaluate(slowScript(), Map.of("slow", "x".repeat(6_000)), 10_000L, 12L)));
        tenant.start();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (pool.heldTenantPermits(12L) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pool.heldTenantPermits(12L)).isEqualTo(1);
        // 第 1 个等候者：在数量上限内，准入等候
        AtomicReference<BpmScriptRunner.Result> waiter = new AtomicReference<>();
        Thread queued = new Thread(() -> waiter.set(pool.evaluate("return 42;", Map.of(), 5_000L, 12L)));
        queued.start();
        deadline = System.currentTimeMillis() + 5_000L;
        while (pool.queuedTenantCount(12L) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pool.queuedTenantCount(12L)).as("等候者应实际占用租户等候名额").isEqualTo(1);
        // 第 2 个等候者：超出数量上限，立即繁忙（不等 8s 时限）
        long begin = System.currentTimeMillis();
        BpmScriptRunner.Result overflow = pool.evaluate("return 7;", Map.of(), 5_000L, 12L);
        long elapsed = System.currentTimeMillis() - begin;
        assertThat(overflow.kind()).as("errorMessage=%s", overflow.errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
        assertThat(overflow.errorMessage()).contains("等候队列已满（上限 1");
        assertThat(elapsed).as("超限应立即拒绝").isLessThan(1_000L);
        // 释放后：等候者取得许可并成功，等候计数归零
        tenant.join();
        assertThat(holder.get().kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        queued.join();
        assertThat(waiter.get().kind()).as("waiter msg=%s", waiter.get().errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(((Number) waiter.get().value()).intValue()).isEqualTo(42);
        assertThat(pool.queuedTenantCount(12L)).isZero();
        assertThat(pool.queuedGlobalCount()).isZero();
    }

    @Test
    @DisplayName("全局等候数量上限=0：全局许可耗尽时异租户立即繁忙（不等候）")
    void shouldRejectImmediatelyWhenGlobalQueueCapacityIsZero() throws Exception {
        pool.configure(1, 2, 5_000, 0, 1);
        assertThat(pool.queueCapacity()).isZero();
        AtomicReference<BpmScriptRunner.Result> holder = new AtomicReference<>();
        Thread tenant = new Thread(() -> holder.set(
                pool.evaluate(slowScript(), Map.of("slow", "x".repeat(6_000)), 10_000L, 13L)));
        tenant.start();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (pool.heldTenantPermits(13L) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(pool.heldTenantPermits(13L)).isEqualTo(1);
        // 异租户：本租户许可可用，但全局许可（workers=1）已被占用 → 全局等候上限 0 立即繁忙
        long begin = System.currentTimeMillis();
        BpmScriptRunner.Result busy = pool.evaluate("return 1;", Map.of(), 5_000L, 14L);
        long elapsed = System.currentTimeMillis() - begin;
        assertThat(busy.kind()).as("errorMessage=%s", busy.errorMessage())
                .isEqualTo(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT);
        assertThat(busy.errorMessage()).contains("全局等候队列已满");
        assertThat(elapsed).as("全局上限 0 应立即拒绝").isLessThan(1_000L);
        assertThat(pool.queuedGlobalCount()).isZero();
        tenant.join();
        assertThat(holder.get().kind()).isEqualTo(BpmScriptRunner.Result.Kind.OK);
        assertThat(pool.evaluate("return 2;", Map.of(), 5_000L, 14L).kind())
                .as("全局许可释放后恢复")
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
    }

    /** 受控慢判断（语句数上限内、无堆压力）：占用许可窗口显著长于探测间隔。 */
    private static String slowScript() {
        return "let s = 0; for (let i = 0; i < 6000; i++) "
                + "{ s = s + 流程变量取值('slow').length; } return s;";
    }

    @Test
    @DisplayName("自身进程回收：shutdown 后池内 worker 进程实际退出（无孤儿进程）")
    void shouldReclaimOwnWorkerProcessesOnShutdown() throws Exception {
        ScriptWorkerPool local = new ScriptWorkerPool();
        local.configure(2, 1, 200);
        assertThat(local.evaluate("return 1;", Map.of(), 5_000L, 21L).kind())
                .isEqualTo(BpmScriptRunner.Result.Kind.OK);
        long pid = local.lastSpawnPid();
        assertThat(pid).isPositive();
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("池内 worker 运行中").isTrue();
        local.shutdown();
        assertThat(local.liveWorkerCount()).isZero();
        // destroyForcibly 的实际退出：轮询至进程消失（有界等待，不无限轮询）
        long deadline = System.currentTimeMillis() + 5_000L;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("shutdown 后 worker pid=%d 应已实际退出（自身进程已回收）", pid).isFalse();
    }
}
