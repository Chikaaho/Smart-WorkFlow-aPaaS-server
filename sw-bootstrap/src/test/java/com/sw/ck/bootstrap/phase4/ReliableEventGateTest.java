package com.sw.ck.bootstrap.phase4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 机械守门：must-deliver 业务事件不得退回“裸内存事件 + 内存监听”作为唯一链路。
 *
 * <p>规则（全部基于 production 源码文本与可复算计数）：</p>
 * <ol>
 *   <li><b>P4-1 定时 FLOW 必须走持久受理端口</b>：{@code SwJobBean} 内不得再发布
 *       {@code ScheduledFlowTriggerEvent}，且必须包含事务边界与端口调用；</li>
 *   <li><b>P4-2 表单兜底发布退役</b>：{@code FormSubmitService} 不得再发布
 *       {@code FormSubmittedEvent}；</li>
 *   <li><b>P4-3 事务内登记器存在且同步</b>：通知 / 设备命令 / OpenAPI 回调三类 must-deliver
 *       事件各自存在一个同步 {@code @EventListener} 登记器，且该登记器不得是 {@code @Async}
 *       或 AFTER_COMMIT；</li>
 *   <li><b>P4-4 恢复调度存在</b>：IoT 触发、设备命令、OpenAPI 回调各存在恢复/补偿调度，
 *       且使用条件更新认领（多实例单一领取者）；</li>
 *   <li><b>P4-5 IoT 脚本路径必须持久化意图</b>：脚本引擎在副作用执行后落触发记录；</li>
 *   <li><b>P4-6 幂等身份不随机</b>：审批设备命令的幂等键来自业务身份（不含 UUID）。</li>
 * </ol>
 */
@DisplayName("Phase4 可靠业务事件 · 机械守门")
class ReliableEventGateTest {

    private static Path repoRoot() {
        String basedir = System.getProperty("basedir");
        Path base = (basedir == null || basedir.isBlank())
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(basedir).toAbsolutePath().normalize();
        Path root = base.getParent();
        assertTrue(root != null && Files.isDirectory(root.resolve("sw-biz")),
                "未定位到后端仓库根: " + root);
        return root;
    }

    /** 去掉注释与字符串字面量（规则应作用于代码，而不是解释性文案）。 */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        boolean line = false, block = false, str = false, ch = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') { line = false; out.append(c); } else { out.append(' '); }
                i++;
                continue;
            }
            if (block) {
                if (c == '*' && n == '/') { block = false; out.append("  "); i += 2; continue; }
                out.append(c == '\n' ? '\n' : ' ');
                i++;
                continue;
            }
            if (str || ch) {
                if (c == '\\') { out.append("  "); i += 2; continue; }
                if ((str && c == '"') || (ch && c == '\'')) { str = false; ch = false; }
                out.append(c == '\n' ? '\n' : ' ');
                i++;
                continue;
            }
            if (c == '/' && n == '/') { line = true; out.append("  "); i += 2; continue; }
            if (c == '/' && n == '*') { block = true; out.append("  "); i += 2; continue; }
            if (c == '"') { str = true; out.append(' '); i++; continue; }
            if (c == '\'') { ch = true; out.append(' '); i++; continue; }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 读取生产源码并剥离注释/字面量。 */
    private static String readCode(String repoRelative) throws IOException {
        return stripCommentsAndLiterals(read(repoRelative));
    }

    private static String read(String repoRelative) throws IOException {
        Path path = repoRoot().resolve(repoRelative);
        assertTrue(Files.isRegularFile(path), "缺少预期文件: " + repoRelative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 按 FQCN 定位生产源码，不绑定模块目录名。
     *
     * <p>Phase 5 把 IoT 跨模块契约抽到 {@code sw-basic-iot-api}，模块目录与 artifact 命名仍可能
     * 继续演进；因此受影响的 IoT 定位改为「模块/FQCN 可复核定位」：由 FQCN 推导各模块
     * {@code src/main/java} 下的相对路径后在仓库内唯一定位。检查语义与失败能力保持不变，
     * 且比原单路径更强——命中 0 个或多个都判失败，不会因模块改名而静默跳过检查。</p>
     */
    private static Path resolveByFqcn(String fqcn) throws IOException {
        String relative = fqcn.replace('.', '/') + ".java";
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(repoRoot())) {
            java.util.List<Path> matches = files
                    .filter(path -> path.toString().endsWith("/src/main/java/" + relative))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
            assertTrue(matches.size() == 1,
                    "FQCN 唯一定位失败: " + fqcn + " 命中 " + matches.size() + " 个文件 " + matches);
            return matches.get(0);
        }
    }

    private static String readByFqcn(String fqcn) throws IOException {
        return Files.readString(resolveByFqcn(fqcn), StandardCharsets.UTF_8);
    }

    private static String readCodeByFqcn(String fqcn) throws IOException {
        return stripCommentsAndLiterals(readByFqcn(fqcn));
    }

    // ==================== P4-1 ====================

    @Test
    @DisplayName("P4-1：定时 FLOW 任务经持久受理端口受理，不再发布内存事件")
    void p4_1_scheduledFlowUsesDurablePort() throws IOException {
        String job = readCode("sw-basic/sw-basic-job/sw-basic-job-biz/src/main/java/com/sw/ck/job/scheduler/SwJobBean.java");
        assertFalse(job.contains("eventPublisher.publish"),
                "SwJobBean 不得再发布内存事件作为定时 FLOW 的唯一交付链");
        assertTrue(job.contains("ScheduledFlowStartPort"), "SwJobBean 必须经 ScheduledFlowStartPort 受理");
        assertTrue(job.contains("TransactionTemplate"), "定时 FLOW 必须建立显式事务边界后再受理");
        String port = readCode("sw-basic/sw-basic-job/sw-basic-job-api/src/main/java/com/sw/ck/job/port/ScheduledFlowStartPort.java");
        assertTrue(port.contains("Optional<Long> acceptScheduledFlowStart"),
                "端口契约必须是 Optional 返回值（Phase 1 契约延续）");
        String impl = read("sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/port/ScheduledFlowStartPortImpl.java");
        assertTrue(impl.contains("commandQueue.enqueue"), "受理实现必须写入持久命令队列");
        assertTrue(impl.contains("SCHEDULED_FLOW:"), "受理实现必须使用 jobId+fireTime 稳定幂等键");
    }

    // ==================== P4-2 ====================

    @Test
    @DisplayName("P4-2：FormSubmittedEvent 进程内兜底发布已退役")
    void p4_2_formFallbackRetired() throws IOException {
        String submit = readCode("sw-biz/sw-biz-form/sw-biz-form-biz/src/main/java/com/sw/ck/form/service/FormSubmitService.java");
        assertFalse(submit.contains("eventPublisher.publish(event)"),
                "FormSubmitService 不得再发布无人消费的兜底事件");
        assertTrue(submit.contains("acceptFlowStart"), "权威路径必须是 FlowStartPort 持久受理");
    }

    // ==================== P4-3 ====================

    @Test
    @DisplayName("P4-3：三类 must-deliver 事件均有同步事务内登记器（不得 @Async/AFTER_COMMIT）")
    void p4_3_inTransactionRecordersExist() throws IOException {
        List<String> recorders = List.of(
                "sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/listener/BpmNotifyIntentRecorder.java",
                "sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/listener/BpmDeviceCommandIntentRecorder.java",
                "sw-biz/sw-biz-openapi/sw-biz-openapi-biz/src/main/java/com/sw/ck/openapi/biz/listener/OpenApiCallbackIntentRecorder.java");
        List<String> problems = new ArrayList<>();
        for (String recorder : recorders) {
            String source = readCode(recorder);
            if (!source.contains("@EventListener")) {
                problems.add(recorder + " 缺少同步 @EventListener 登记入口");
            }
            if (source.contains("@Async") || source.contains("AFTER_COMMIT")) {
                problems.add(recorder + " 不得使用 @Async / AFTER_COMMIT（必须与业务同事务）");
            }
            if (!source.contains("isActualTransactionActive")) {
                problems.add(recorder + " 缺少无事务上下文显式告警");
            }
        }
        assertTrue(problems.isEmpty(), "事务内登记器规则不满足: " + problems);
        String notifyRecorder = readCode(recorders.get(0));
        assertTrue(notifyRecorder.contains("recordIntent"),
                "通知登记器必须调用 NotifyFacade#recordIntent（仅持久化，无渠道 I/O）");
    }

    // ==================== P4-4 ====================

    @Test
    @DisplayName("P4-4：三类接缝均有恢复调度，且以条件更新原子认领")
    void p4_4_recoveryJobsExist() throws IOException {
        String iotJob = readCodeByFqcn("com.sw.ck.iot.job.ProcessTriggerRecoveryJob");
        assertTrue(iotJob.contains("@Scheduled"), "IoT 触发恢复必须有调度");
        assertTrue(iotJob.contains("in(IotProcessTrigger::getStatus, PENDING, FAILED)")
                        && iotJob.contains("affected == 1"),
                "IoT 触发恢复必须条件更新认领（单一领取者）");
        String deviceJob = readByFqcn("com.sw.ck.iot.job.CommandCompensationJob");
        assertTrue(deviceJob.contains("claimQueuedForSend") && deviceJob.contains("claimFailedForRetry"),
                "设备命令补偿必须真实领取并重试（不得保留桩实现）");
        assertFalse(deviceJob.contains("此处简化处理"), "设备命令补偿不得保留桩实现");
        String openapiJob = read("sw-biz/sw-biz-openapi/sw-biz-openapi-biz/src/main/java/com/sw/ck/openapi/biz/job/OpenApiCallbackRecoveryJob.java");
        assertTrue(openapiJob.contains("@Scheduled") && openapiJob.contains("RETRY_EXHAUSTED"),
                "回调恢复必须有调度与可审计终态");
        String dispatcher = readCode("sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/queue/CommandDispatcher.java");
        assertTrue(dispatcher.contains("reclaimStale"), "持久命令队列必须保留 stale 回收（崩溃恢复）");
    }

    // ==================== P4-5 ====================

    @Test
    @DisplayName("P4-5：IoT 脚本路径在副作用执行后持久化触发意图")
    void p4_5_scriptPathPersistsIntent() throws IOException {
        String engine = readCodeByFqcn("com.sw.ck.iot.script.ScriptEngineService");
        assertTrue(engine.contains("processTriggerMapper.insert"),
                "脚本路径必须写入 sw_iot_process_trigger（与执行同事务）");
        String host = readCodeByFqcn("com.sw.ck.iot.script.ScriptHostFunctions");
        assertTrue(host.contains("processStartRequests") && host.contains("ProcessStartRequest"),
                "脚本宿主必须记录启动意图供引擎持久化");
    }

    // ==================== P4-6 ====================

    @Test
    @DisplayName("P4-6：审批设备命令幂等键来自业务身份（不含随机 UUID）")
    void p4_6_deviceCommandIdentityStable() throws IOException {
        String recorder = read("sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/listener/BpmDeviceCommandIntentRecorder.java");
        String keyLine = null;
        for (String line : recorder.split("\n")) {
            if (line.contains("return \"APPROVAL:\"")) {
                keyLine = line;
                break;
            }
        }
        assertTrue(keyLine != null, "审批设备命令必须派生 APPROVAL 幂等键");
        assertFalse(keyLine.contains("randomUUID") || keyLine.contains("UUID"),
                "幂等键不得含随机成分: " + keyLine);
        String iotService = readCodeByFqcn("com.sw.ck.iot.service.impl.IotDeviceServiceImpl");
        assertTrue(iotService.contains("dispatchCommandIdempotent")
                        && iotService.contains("selectByIdempotentKey"),
                "IoT 侧必须支持调用方幂等键并在重复投递时复用既有命令");
    }

    // ==================== P4-7 ====================

    /**
     * 零未分类旁路（复算）：每个 must-deliver 事件都必须有事务内记录器或持久命令消费者；
     * 两个"内存兜底"事件必须保持零发布。机械扫描独立于人工矩阵（matrix 见证据包 TSV）。
     */
    @Test
    @DisplayName("P4-7：must-deliver 事件零未分类旁路（无监听者 / 无持久意图均不允许）")
    void p4_7_noUnclassifiedMustDeliverBypass() throws IOException {
        String[] publishedEvents = {"BpmNotifyEvent", "BpmDeviceCommandEvent", "IotProcessTriggerEvent"};
        for (String event : publishedEvents) {
            assertTrue(hasEventListenerFor(event),
                    "must-deliver 事件必须有事务内记录器/监听者: " + event);
        }
        String jobBean = readCode("sw-basic/sw-basic-job/sw-basic-job-biz/src/main/java/com/sw/ck/job/scheduler/SwJobBean.java");
        assertFalse(jobBean.contains("publish(new ScheduledFlowTriggerEvent")
                        || jobBean.contains("eventPublisher.publish"),
                "FLOW 定时任务不得退回裸内存事件发布（权威路径为持久命令受理）");
        String formSubmit = readCode("sw-biz/sw-biz-form/sw-biz-form-biz/src/main/java/com/sw/ck/form/service/FormSubmitService.java");
        assertFalse(formSubmit.contains("publish(new FormSubmittedEvent")
                        || formSubmit.contains("eventPublisher.publish"),
                "表单提交不得退回无监听者的内存兜底事件（权威路径为 FlowStartPort）");

        String triggerRecovery = readCodeByFqcn("com.sw.ck.iot.job.ProcessTriggerRecoveryJob");
        assertTrue(triggerRecovery.contains("reclaimStale"),
                "IoT 触发恢复必须能回收崩溃留下的租约（否则恢复链可被永久卡死）");
        String deviceJob = readCodeByFqcn("com.sw.ck.iot.job.CommandCompensationJob");
        assertTrue(deviceJob.contains("reclaimStaleSending") || deviceJob.contains("findStaleSending"),
                "设备命令补偿必须能回收崩溃留下的发送租约");
        String callbackJob = readCode("sw-biz/sw-biz-openapi/sw-biz-openapi-biz/src/main/java/com/sw/ck/openapi/biz/job/OpenApiCallbackRecoveryJob.java");
        assertTrue(callbackJob.contains("reclaimStaleLeases"),
                "回调恢复必须能回收崩溃留下的投递租约");
    }

    /** 递归扫描源码目录，判定是否存在监听/记录目标事件的类。 */
    private boolean hasEventListenerFor(String eventType) throws IOException {
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(repoRoot())) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> {
                        try {
                            String source = java.nio.file.Files.readString(path);
                            boolean isListener = source.contains("@EventListener")
                                    || source.contains("@TransactionalEventListener");
                            return isListener && source.contains(eventType);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findAny()
                    .isPresent();
        }
    }
}
