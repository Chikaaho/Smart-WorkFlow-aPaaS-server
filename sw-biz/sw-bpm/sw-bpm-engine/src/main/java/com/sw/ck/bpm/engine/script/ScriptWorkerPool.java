package com.sw.ck.bpm.engine.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P64 判断脚本隔离执行池（审查02 P1-02a/P1-02b 实现修订）。
 * <p>
 * 隔离技术：每个判断在专职 worker JVM（{@code -Xmx128m}）内执行——脚本可分配内存被
 * 真实堆上限约束，耗尽即 worker 进程死亡（宿主识别 EOF 归类 RESOURCE_LIMIT 并重启），
 * wall clock 超时由宿主 destroy 兜底，无遗留活判断。并发与排队：
 * <ul>
 *   <li>全局并发上限 = 常驻 worker 池上限（{@code sw.bpm.script.workers}，默认 2）；</li>
 *   <li>单租户并发上限 = {@code sw.bpm.script.tenant-workers}（默认 1）；</li>
 *   <li>有限排队 = <b>等候数量硬上限</b>（{@code sw.bpm.script.queue-capacity} 全局默认 8、
 *       {@code sw.bpm.script.tenant-queue-capacity} 单租户默认 4；0 = 不允许等候，满额立即繁忙）
 *       叠加等候时限 {@code sw.bpm.script.queue-wait-ms}（默认 50ms）：数量上限内才准入等候，
 *       时限内未取得许可判 RESOURCE_LIMIT（"判断执行繁忙"）——可诊断、可恢复，无无界等待。</li>
 * </ul>
 * HTTP 办理、命令消费、预览共用本池，即共用同一组全局/租户上限与队列。
 * </p>
 */
@Component
public class ScriptWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(ScriptWorkerPool.class);

    public static final String HEAP_CAP = "128m";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${sw.bpm.script.workers:2}")
    private int maxWorkers;

    @Value("${sw.bpm.script.tenant-workers:1}")
    private int tenantMaxWorkers;

    @Value("${sw.bpm.script.queue-wait-ms:50}")
    private long queueWaitMs;

    /** 全局等候数量硬上限（0 = 不允许等候，满额立即繁忙）。 */
    @Value("${sw.bpm.script.queue-capacity:8}")
    private int queueCapacity;

    /** 单租户等候数量硬上限（0 = 不允许等候，满额立即繁忙）。 */
    @Value("${sw.bpm.script.tenant-queue-capacity:4}")
    private int tenantQueueCapacity;

    private final Semaphore globalPermits = new Semaphore(2);
    private final Map<Long, Semaphore> tenantPermits = new ConcurrentHashMap<>();
    private final Map<String, Worker> liveWorkers = new ConcurrentHashMap<>();

    /** 当前在等候许可的请求数（全局/单租户），受数量上限约束。 */
    private final AtomicInteger globalQueueCount = new AtomicInteger();
    private final Map<Long, AtomicInteger> tenantQueueCounts = new ConcurrentHashMap<>();

    /** 最近一次 worker 启动握手自报的 JVM 堆上限（运行观测：确认 -Xmx{@link #HEAP_CAP} 实际生效）。 */
    private volatile long lastSpawnMaxHeapBytes = -1L;

    /** 最近一次启动的 worker 进程 pid（运行观测：OOM/超时后实际退出核验）。 */
    private volatile long lastSpawnPid = -1L;

    /** 测试接缝：覆盖池参数（绕过 @Value）；等候数量上限沿用默认配置。 */
    public void configure(int workers, int tenantWorkers, long waitMs) {
        configure(workers, tenantWorkers, waitMs, queueCapacity, tenantQueueCapacity);
    }

    /** 测试接缝：覆盖池参数与等候数量上限（绕过 @Value）；全局许可重置为 workers 个。 */
    public void configure(int workers, int tenantWorkers, long waitMs, int globalQueue, int tenantQueue) {
        this.maxWorkers = workers;
        this.tenantMaxWorkers = tenantWorkers;
        this.queueWaitMs = waitMs;
        this.queueCapacity = globalQueue;
        this.tenantQueueCapacity = tenantQueue;
        this.globalPermits.drainPermits();
        this.globalPermits.release(Math.max(1, workers));
    }

    /**
     * 提交判断到隔离 worker。
     *
     * @return 引擎结果；等候队列满或时限内未取得许可为 RESOURCE_LIMIT（繁忙，可恢复）
     */
    public BpmScriptRunner.Result evaluate(String script, Map<String, Object> variables,
                                          long timeoutMs, Long tenantId) {
        long tenantKey = tenantId == null ? 0L : tenantId;
        Semaphore tenantPermit = tenantPermits.computeIfAbsent(
                tenantKey,
                key -> new Semaphore(Math.max(1, tenantMaxWorkers)));
        boolean tenantAcquired = false;
        boolean globalAcquired = false;
        Worker worker = null;
        long begin = System.currentTimeMillis();
        try {
            // 单租户并发：满额时按“租户等候数量上限”准入等候（0 = 立即繁忙）
            if (!tenantPermit.tryAcquire()) {
                if (!admitToQueue(tenantQueueCounts, tenantKey, tenantQueueCapacity)) {
                    return busy(begin, "租户等候队列已满（上限 " + tenantQueueCapacity + "，tenant=" + tenantId + "）");
                }
                try {
                    if (!tenantPermit.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS)) {
                        return busy(begin, "租户并发已达上限 " + Math.max(1, tenantMaxWorkers)
                                + "（tenant=" + tenantId + "，等候 " + queueWaitMs + "ms 未获得许可）");
                    }
                } finally {
                    releaseQueueSlot(tenantQueueCounts, tenantKey);
                }
            }
            tenantAcquired = true;
            // 全局并发：同规则（全局等候上限 0 = 立即繁忙）
            if (!globalPermits.tryAcquire()) {
                if (!admitGlobalQueue()) {
                    return busy(begin, "全局等候队列已满（上限 " + queueCapacity + "）");
                }
                try {
                    if (!globalPermits.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS)) {
                        return busy(begin, "全局判断并发已达池上限 " + Math.max(1, maxWorkers)
                                + "（等候 " + queueWaitMs + "ms 未获得许可）");
                    }
                } finally {
                    globalQueueCount.decrementAndGet();
                }
            }
            globalAcquired = true;
            worker = acquireWorker();
            return worker.evaluate(script, variables, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BpmScriptRunner.Result(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT, null, null,
                    "判断执行等待被中断", elapsed(begin));
        } finally {
            if (worker != null) {
                recycle(worker);
            }
            if (globalAcquired) {
                globalPermits.release();
            }
            if (tenantAcquired) {
                tenantPermit.release();
            }
        }
    }

    /** 等候数量准入：数量上限内 CAS 占位成功返回 true；上限 0 或已满返回 false（立即繁忙）。 */
    private boolean admitToQueue(Map<Long, AtomicInteger> counts, Long key, int capacity) {
        if (capacity <= 0) {
            return false;
        }
        AtomicInteger counter = counts.computeIfAbsent(key, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= capacity) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseQueueSlot(Map<Long, AtomicInteger> counts, Long key) {
        AtomicInteger counter = counts.get(key);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    private boolean admitGlobalQueue() {
        if (queueCapacity <= 0) {
            return false;
        }
        while (true) {
            int current = globalQueueCount.get();
            if (current >= queueCapacity) {
                return false;
            }
            if (globalQueueCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private BpmScriptRunner.Result busy(long begin, String why) {
        return new BpmScriptRunner.Result(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT, null, null,
                "判断执行繁忙（可恢复，请重试）: " + why, elapsed(begin));
    }

    private long elapsed(long begin) {
        return System.currentTimeMillis() - begin;
    }

    private synchronized Worker acquireWorker() {
        Worker worker = liveWorkers.values().stream().filter(Worker::isIdle).findFirst().orElse(null);
        if (worker != null) {
            worker.claim();
            return worker;
        }
        if (liveWorkers.size() >= Math.max(1, maxWorkers)) {
            // 池满且全部忙碌：并发许可本应已挡住；兜底等待第一个空闲者
            worker = liveWorkers.values().stream().filter(Worker::isIdle).findFirst()
                    .orElseThrow(() -> new IllegalStateException("判断 worker 池忙"));
            worker.claim();
            return worker;
        }
        Worker created;
        try {
            created = Worker.spawn(this, objectMapper);
        } catch (IOException e) {
            throw new IllegalStateException("判断 worker 进程启动失败: " + e.getMessage(), e);
        }
        created.claim();
        liveWorkers.put(created.id(), created);
        lastSpawnMaxHeapBytes = created.maxHeapBytes();
        lastSpawnPid = created.pid();
        log.info("P64 判断 worker 就绪: maxHeapBytes={}（宿主确认 -Xmx{} 生效）", lastSpawnMaxHeapBytes, HEAP_CAP);
        return created;
    }

    private synchronized void recycle(Worker worker) {
        worker.release();
        if (!worker.isAliveProcess()) {
            liveWorkers.remove(worker.id());
            worker.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        liveWorkers.values().forEach(Worker::close);
        liveWorkers.clear();
    }

    int liveWorkerCount() {
        return liveWorkers.size();
    }

    /** 运行观测：最近一次 worker 启动握手自报的堆上限（应为 {@link #HEAP_CAP} 的字节值）。 */
    long lastSpawnMaxHeapBytes() {
        return lastSpawnMaxHeapBytes;
    }

    /** 运行观测：最近一次启动的 worker 进程 pid（OOM/超时后以 ProcessHandle 核验实际退出）。 */
    long lastSpawnPid() {
        return lastSpawnPid;
    }

    /** 运行观测：指定租户当前已占用的判断许可数（并发上限验证用）。 */
    int heldTenantPermits(Long tenantId) {
        Semaphore permits = tenantPermits.get(tenantId == null ? 0L : tenantId);
        return permits == null ? 0 : Math.max(1, tenantMaxWorkers) - permits.availablePermits();
    }

    /** 运行观测：全局当前等候许可的请求数（等候数量上限验证用）。 */
    int queuedGlobalCount() {
        return globalQueueCount.get();
    }

    /** 运行观测：指定租户当前等候许可的请求数（租户等候数量上限验证用）。 */
    int queuedTenantCount(Long tenantId) {
        AtomicInteger counter = tenantQueueCounts.get(tenantId == null ? 0L : tenantId);
        return counter == null ? 0 : counter.get();
    }

    /** 运行观测：生效的全局等候数量上限（含 0=不允许等候）。 */
    int queueCapacity() {
        return queueCapacity;
    }

    /** 运行观测：生效的单租户等候数量上限（含 0=不允许等候）。 */
    int tenantQueueCapacity() {
        return tenantQueueCapacity;
    }

    /** 单个 worker 进程句柄。 */
    static final class Worker {

        /** 启动握手等待上限：JVM 冷启动远小于此，仅防病态失败挂起。 */
        private static final long STARTUP_HANDSHAKE_TIMEOUT_MS = 15_000L;

        private final String id = UUID.randomUUID().toString();
        private final Process process;
        private final BufferedReader stdout;
        private final OutputStreamWriter stdin;
        private final ObjectMapper mapper;
        private final ScriptWorkerPool pool;
        private boolean busy;
        private boolean destroyedByDeadline;
        private long maxHeapBytes = -1L;

        private Worker(ScriptWorkerPool pool, ObjectMapper mapper, Process process) {
            this.pool = pool;
            this.mapper = mapper;
            this.process = process;
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }

        static Worker spawn(ScriptWorkerPool pool, ObjectMapper mapper) throws IOException {
            String javaBin = ProcessHandle.current().info().command().orElse("java");
            String classpath = System.getProperty("java.class.path");
            List<String> command = new ArrayList<>(List.of(javaBin, "-Xms32m", "-Xmx" + HEAP_CAP,
                    "-Dfile.encoding=UTF-8", "-cp", classpath,
                    ScriptWorkerMain.class.getName()));
            log.info("P64 判断 worker 启动: {} (heap cap {})", command.get(command.size() - 1), HEAP_CAP);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            // worker 的日志（stderr）并入宿主 stderr：不占用协议流，也避免无人读取的管道阻塞
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = builder.start();
            Worker worker = new Worker(pool, mapper, process);
            worker.handshake();
            return worker;
        }

        /** 启动握手：确认 worker 已就绪并核对本进程实际堆上限（-Xmx 生效值）。 */
        private void handshake() throws IOException {
            destroyedByDeadline = false;
            String line = readProtocolLine("__ready__", STARTUP_HANDSHAKE_TIMEOUT_MS);
            if (line == null) {
                destroyAndAwait();
                throw new IOException("worker 启动握手失败（无就绪应答）");
            }
            Map<?, ?> hello;
            try {
                hello = mapper.readValue(line, Map.class);
            } catch (Exception e) {
                destroyAndAwait();
                throw new IOException("worker 启动握手失败（应答非法）: " + line);
            }
            if (!Boolean.TRUE.equals(hello.get("ready"))) {
                destroyAndAwait();
                throw new IOException("worker 启动握手失败: " + line);
            }
            this.maxHeapBytes = ((Number) Objects.requireNonNullElse(hello.get("maxHeapBytes"), -1L)).longValue();
            log.info("P64 判断 worker 就绪: pid={} maxHeapBytes={}", process.pid(), maxHeapBytes);
        }

        String id() {
            return id;
        }

        long pid() {
            return process.pid();
        }

        long maxHeapBytes() {
            return maxHeapBytes;
        }

        synchronized void claim() {
            this.busy = true;
        }

        synchronized void release() {
            this.busy = false;
        }

        synchronized boolean isIdle() {
            return !busy;
        }

        boolean isAliveProcess() {
            return process.isAlive();
        }

        BpmScriptRunner.Result evaluate(String script, Map<String, Object> variables, long timeoutMs) {
            long begin = System.currentTimeMillis();
            String requestId = UUID.randomUUID().toString();
            try {
                Map<String, Object> request = Map.of(
                        "id", requestId,
                        "script", script == null ? "" : script,
                        "variables", variables == null ? Map.of() : variables,
                        "timeoutMs", timeoutMs);
                stdin.write(mapper.writeValueAsString(request));
                stdin.write("\n");
                stdin.flush();
                // 结果行读取以 wall clock 截止兜底：超时强制销毁进程（无遗留活判断）
                destroyedByDeadline = false;
                String line = readProtocolLine(requestId, timeoutMs + 1_000L);
                if (line == null) {
                    if (destroyedByDeadline) {
                        return new BpmScriptRunner.Result(BpmScriptRunner.Result.Kind.TIMEOUT, null, null,
                                "判断执行超时（wall clock " + timeoutMs + "ms 到期，worker 进程已强制销毁）",
                                elapsed(begin));
                    }
                    return new BpmScriptRunner.Result(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT, null, null,
                            "worker 进程退出（判断执行空间耗尽或崩溃）", elapsed(begin));
                }
                Map<?, ?> response = mapper.readValue(line, Map.class);
                return new BpmScriptRunner.Result(
                        BpmScriptRunner.Result.Kind.valueOf(String.valueOf(response.get("kind"))),
                        response.get("value"),
                        (String) response.get("typeName"),
                        (String) response.get("errorMessage"),
                        ((Number) Objects.requireNonNullElse(response.get("durationMs"), 0L)).longValue());
            } catch (Exception e) {
                return new BpmScriptRunner.Result(BpmScriptRunner.Result.Kind.RESOURCE_LIMIT, null, null,
                        "worker 通信失败（进程已回收）: " + e.getMessage(), elapsed(begin));
            }
        }

        /**
         * 读取 id 匹配的协议应答行；跳过 worker 的非协议输出（如日志噪声），到期强制销毁进程。
         *
         * @return 应答行；进程退出（EOF）或 wall clock 到期（已销毁）返回 null
         */
        private String readProtocolLine(String expectedId, long deadlineMs) throws IOException {
            long deadline = System.currentTimeMillis() + deadlineMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                String line = remaining > 0 ? readLineWithDeadline(remaining) : null;
                if (line == null) {
                    if (!process.isAlive()) {
                        // 进程已退出：执行空间耗尽（OOM 死亡）或崩溃
                        return null;
                    }
                    log.warn("P64 判断 worker 未按期应答，强制销毁进程（无遗留活判断）");
                    destroyedByDeadline = true;
                    destroyAndAwait();
                    return null;
                }
                if (line.isBlank()) {
                    continue;
                }
                Object id;
                try {
                    id = mapper.readValue(line, Map.class).get("id");
                } catch (Exception e) {
                    log.warn("忽略 worker 非协议输出: {}", line);
                    continue;
                }
                if (!expectedId.equals(String.valueOf(id))) {
                    log.warn("忽略 worker 非匹配应答（id={}）", id);
                    continue;
                }
                return line;
            }
        }

        /** 带 wall clock 边界的行读取：进程退出即 null；到期返回 null（销毁决策由调用方作出）。 */
        private String readLineWithDeadline(long deadlineMs) throws IOException {
            long deadline = System.currentTimeMillis() + deadlineMs;
            while (true) {
                if (!process.isAlive()) {
                    return null;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                if (stdout.ready()) {
                    return stdout.readLine();
                }
                try {
                    Thread.sleep(Math.min(10L, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        /** 强制销毁并等待实际退出：确保后续 isAlive=false，池回收判定不留幸存进程。 */
        private void destroyAndAwait() {
            process.destroyForcibly();
            try {
                process.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("P64 判断 worker 销毁等待异常: {}", e.getMessage());
            }
        }

        void close() {
            process.destroyForcibly();
        }

        private long elapsed(long begin) {
            return System.currentTimeMillis() - begin;
        }
    }
}
