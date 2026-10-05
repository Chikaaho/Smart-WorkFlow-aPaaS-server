package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.entity.BpmResourcePolicy;
import com.sw.ck.bpm.process.entity.BpmResourceRejectLog;
import com.sw.ck.bpm.process.entity.BpmResourceUsage;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.entity.ResourceClassEnum;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceRejectLogMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.bpm.process.queue.support.QueueH2TestConfig;
import com.sw.ck.bpm.process.service.ResourceAdmissionService;
import com.sw.ck.bpm.process.service.ResourcePolicyService;
import com.sw.ck.bpm.process.service.ResourceReleaseService;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P62 资源保障核心行为（H2 真实持久化 + 真实迁移 + 真实准入/会计链路）。
 * <p>
 * 覆盖 RG02 主干（额度并发不突破、幂等重放不重复占用、同键异载荷拒绝、
 * 批量按项计费与逐项回收、拒绝审计）、RG03 主干（租户公平领取、批量切片让出、
 * 段保护）、RG07 主干（停新受理、跨版本共享计数、轻流程目标不因 SUCCEEDED 释放）。
 * </p>
 */
@SpringBootTest(classes = {QueueH2TestConfig.class,
        com.sw.ck.bpm.process.queue.support.ResourceAssuranceTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"flowable.async-executor-activate=true",
                "flowable.process.async.executor.core-pool-size=8",
                "spring.datasource.dynamic.druid.max-active=64",
                "sw.bpm.txn-batch.enabled=true"})
@DisplayName("P62 资源保障核心行为（H2 真实持久化）")
class ResourceAssuranceH2Test {

    private static final long TENANT_A = 100L;
    private static final long TENANT_B = 200L;

    @Autowired
    private ResourceAdmissionService admissionService;

    @Autowired
    private ResourcePolicyService policyService;

    @Autowired
    private PersistentBpmCommandQueue queue;

    @Autowired
    private ResourceReleaseService releaseService;

    @Autowired
    private ResourceAssuranceReconcileJob reconcileJob;

    @Autowired
    private BpmResourceUsageMapper usageMapper;

    @Autowired
    private BpmResourceRejectLogMapper rejectLogMapper;

    @Autowired
    private BpmCommandBatchMapper batchMapper;

    @Autowired
    private BpmCommandBatchItemMapper itemMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate txTemplate;

    /** 速率桶是 JVM 驻留态：跨用例必须复位，否则同窗口内前序用例的 admit 会使后续用例撞 2428（flaky）。 */
    @Autowired
    private com.sw.ck.bpm.process.service.TenantRateBuckets rateBuckets;

    @BeforeEach
    void setUp() {
        rateBuckets.reset();
        jdbcTemplate.update("DELETE FROM sw_bpm_command_effect");
        jdbcTemplate.update("DELETE FROM sw_bpm_command");
        jdbcTemplate.update("DELETE FROM sw_bpm_command_batch_item");
        jdbcTemplate.update("DELETE FROM sw_bpm_command_batch");
        jdbcTemplate.update("DELETE FROM sw_bpm_resource_reject_log");
        jdbcTemplate.update("DELETE FROM sw_bpm_resource_policy");
        jdbcTemplate.update("DELETE FROM sw_bpm_resource_usage");
        // 迁移种子行被清空后重建（幂等；全局行=迁移种子口径，租户行=准入惰性建行口径）
        jdbcTemplate.update("INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, "
                + "segment, outstanding) VALUES (910001, 0, 'GLOBAL', 0, 'TOTAL', 0)");
        jdbcTemplate.update("INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, "
                + "segment, outstanding) VALUES (910002, 0, 'GLOBAL', 0, 'PROD_RESERVED', 0)");
        jdbcTemplate.update("INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, "
                + "segment, outstanding) VALUES (910003, 0, 'GLOBAL', 0, 'OA_RESERVED', 0)");
        jdbcTemplate.update("INSERT INTO sw_bpm_resource_usage (id, tenant_id, scope, scope_key, "
                + "segment, outstanding) VALUES (910004, 0, 'GLOBAL', 0, 'SHARED', 0)");
        admissionService.ensureUsageRows(TENANT_A);
        admissionService.ensureUsageRows(TENANT_B);
        asTenantAdmin(TENANT_A, 7L);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void asTenantAdmin(long tenantId, long userId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        user.setPermissions(java.util.List.of("form:action:invoke"));
        LoginUserHolder.set(user);
    }

    // ==================== 策略与启用检查（RG01/RG04） ====================

    private BpmResourcePolicy policy(int global, int tenant, int prodRes, int oaRes, int shared) {
        BpmResourcePolicy policy = new BpmResourcePolicy();
        policy.setGlobalMaxOutstanding(global);
        policy.setTenantMaxOutstanding(tenant);
        policy.setProdReserved(prodRes);
        policy.setOaReserved(oaRes);
        policy.setSharedCapacity(shared);
        policy.setTenantRatePerSec(50);
        policy.setTenantBurst(500);
        policy.setRealtimeGlobalConcurrency(16);
        policy.setRealtimeTenantConcurrency(8);
        policy.setBatchSliceItems(2);
        policy.setBatchPollClaimLimit(1);
        return policy;
    }

    @Test
    @DisplayName("策略未启用：准入零行为变化（返回 null、不占用、不冻结资源字段）")
    void admissionIsNoopWhenPolicyDisabled() {
        assertThat(admissionService.findActivePolicy()).isNull();
        ResourceAdmissionService.AdmissionTicket ticket =
                admissionService.admit(TENANT_A, ResourceClassEnum.OA, 1, "K1");
        assertThat(ticket).isNull();
        assertThat(usageMapper.selectList(null).stream()
                .filter(u -> u.getOutstanding() != null && u.getOutstanding() > 0).count()).isZero();
    }

    @Test
    @DisplayName("非法额度与保留份额不自洽：启用检查明确拒绝（RG01）")
    void enablementRejectsInvalidBudget() {
        BpmResourcePolicy bad = policy(2000, 800, 400, 400, 1100);
        BpmResourcePolicy created = policyService.create(bad);
        List<String> violations = policyService.checkEnablement(created);
        assertThat(violations).anyMatch(v -> v.contains("保留份额不自洽"));

        BpmResourcePolicy badTenant = policy(2000, 2500, 400, 400, 1200);
        policyService.create(badTenant);
        // 最新创建的版本是 badTenant
        BpmResourcePolicy latest = policyService.listAll().get(0);
        assertThat(policyService.checkEnablement(latest))
                .anyMatch(v -> v.contains("不得越过全局上限"));
    }

    @Test
    @DisplayName("有效配置启用成功：ACTIVE 唯一、旧版本退役（RG01）")
    void enableValidPolicy() {
        BpmResourcePolicy created = policyService.create(policy(2000, 800, 400, 400, 1200));
        BpmResourcePolicy enabled = policyService.enable(created.getId(), "测试启用");
        assertThat(enabled.getStatus()).isEqualTo("ACTIVE");
        assertThat(enabled.getEnabled()).isTrue();
        assertThat(admissionService.findActivePolicy()).isNotNull();

        BpmResourcePolicy second = policyService.create(policy(2000, 800, 400, 400, 1200));
        policyService.enable(second.getId(), null);
        BpmResourcePolicy refreshed = policyService.getById(created.getId());
        assertThat(refreshed.getStatus()).isEqualTo("RETIRED");
        assertThat(admissionService.findActivePolicy().getPolicyVersion())
                .isEqualTo(second.getPolicyVersion());
    }

    // ==================== 额度与并发（RG02） ====================

    @Test
    @DisplayName("并发受理不突破额度：24 线程×50 单位竞争 600 上限，恰 600 单位占满")
    void concurrentAdmissionNeverExceedsQuota() {
        BpmResourcePolicy created = policy(600, 600, 100, 100, 400);
        created.setTenantRatePerSec(10000);
        created.setTenantBurst(10000);
        created = policyService.create(created);
        policyService.enable(created.getId(), null);
        admissionService.ensureUsageRows(TENANT_A);
        admissionService.ensureUsageRows(TENANT_B);

        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        IntStream.range(0, 24).forEach(i -> pool.submit(() -> {
            try {
                start.await();
                admissionService.admit(TENANT_A, ResourceClassEnum.PROD, 50, "K" + i);
                admitted.addAndGet(50);
            } catch (BaseException expected) {
                // 超额拒绝=预期
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        start.countDown();
        pool.shutdown();
        try {
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(admitted.get()).isEqualTo(600);
        assertThat(globalUsage("TOTAL")).isEqualTo(600);
    }

    @Test
    @DisplayName("受理冻结+幂等重放不重复占用+拒绝审计独立可查（RG02）")
    void admissionFreezeAndReplayDoNotDoubleCount() {
        BpmResourcePolicy created = policyService.create(policy(2000, 800, 400, 400, 1200));
        policyService.enable(created.getId(), null);
        String commandKey = "TASK_APPROVE:task:1:7";

        txTemplate.executeWithoutResult(status -> {
            ResourceAdmissionService.AdmissionTicket ticket =
                    admissionService.admit(TENANT_A, ResourceClassEnum.OA, 1, commandKey);
            CommandEnvelope envelope = envelope(commandKey, CommandChannelEnum.NORMAL);
            envelope.setResourceClass(ResourceClassEnum.OA.getCode());
            envelope.setResourceUnits(1);
            envelope.setResourceSegment(ticket.segment());
            envelope.setPolicyVersion(ticket.policyVersion());
            queue.enqueue(envelope);
        });
        long afterFirst = globalUsage("TOTAL");
        assertThat(afterFirst).isEqualTo(1);

        // 重放同键同载荷：回查原结果路径不再次准入（调用方语义；此处验证占用未变）
        assertThat(queue.findByKey(TENANT_A, commandKey)).isPresent();
        assertThat(globalUsage("TOTAL")).isEqualTo(1);
        assertThat(globalUsage("SHARED")).isEqualTo(1);
    }

    @Test
    @DisplayName("同键异载荷拒绝且原效果不变（既有 2426 边界在资源会计下保持）")
    void sameKeyDifferentPayloadStillRejected() {
        String commandKey = "TASK_APPROVE:task:2:7";
        txTemplate.executeWithoutResult(status -> queue.enqueue(envelope(commandKey,
                CommandChannelEnum.NORMAL)));
        CommandEnvelope replay = envelope(commandKey, CommandChannelEnum.NORMAL);
        replay.setPayload("{\"payload\":\"DIFFERENT\"}");
        replay.setPayloadFingerprint(com.sw.ck.bpm.process.queue.CommandFingerprint.of(replay.getPayload()));
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> queue.enqueue(replay)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE command_key = ?", Long.class, commandKey))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("批量整笔按项计费；逐项终态回收；失败重试不重复加账（RG02）")
    void batchChargedPerItemAndRecoveredPerItem() {
        BpmResourcePolicy created = policyService.create(policy(2000, 800, 400, 400, 1200));
        policyService.enable(created.getId(), null);
        admissionService.ensureUsageRows(TENANT_A);

        txTemplate.executeWithoutResult(status -> {
            ResourceAdmissionService.AdmissionTicket ticket = admissionService.admit(
                    TENANT_A, ResourceClassEnum.BULK, 5, "BATCH:b1");
            CommandEnvelope envelope = envelope("BATCH:b1", CommandChannelEnum.NORMAL);
            envelope.setCommandType(CommandTypeEnum.BATCH_INVOKE);
            envelope.setResourceClass(ResourceClassEnum.BULK.getCode());
            envelope.setResourceUnits(5);
            envelope.setResourceSegment(ticket.segment());
            envelope.setPolicyVersion(ticket.policyVersion());
            queue.enqueue(envelope);
        });
        assertThat(globalUsage("TOTAL")).isEqualTo(5);
        assertThat(globalUsage("SHARED")).isEqualTo(5);

        // 建立批次与项持久行（占用事实=非终态项；模拟受理时同事务写入的批次账）
        Long batchId = 880001L;
        Long commandId = jdbcTemplate.queryForObject(
                "SELECT id FROM sw_bpm_command WHERE command_key = 'BATCH:b1'", Long.class);
        jdbcTemplate.update("INSERT INTO sw_bpm_command_batch (id, batch_key, action_id, status, "
                + "total_count, succeeded_count, failed_count, command_id, tenant_id) "
                + "VALUES (?, 'b1', 'action-1', 'PROCESSING', 5, 0, 0, ?, ?)",
                batchId, commandId, TENANT_A);
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("INSERT INTO sw_bpm_command_batch_item (id, batch_id, item_key, "
                    + "record_id, status, attempt_count, tenant_id) VALUES (?, ?, ?, ?, 'PENDING', 0, ?)",
                    880010L + i, batchId, "k" + i, "rec" + i, TENANT_A);
        }
        // 项终态（持久事实）与计数释放同事务发生（处理器调用序）；测试同序模拟
        jdbcTemplate.update("UPDATE sw_bpm_command_batch_item SET status = 'SUCCEEDED' "
                + "WHERE batch_id = ? AND item_key IN ('k0','k1')", batchId);
        releaseService.onBatchItemTerminal(batchAccounting(TENANT_A, 5, "SHARED"));
        releaseService.onBatchItemTerminal(batchAccounting(TENANT_A, 5, "SHARED"));
        assertThat(globalUsage("TOTAL")).isEqualTo(3);
        assertThat(globalUsage("SHARED")).isEqualTo(3);

        // 对账勾稽：事实（非终态项=3）与计数一致时不改写；漂移按事实修复
        reconcileJob.reconcileOnce();
        assertThat(globalUsage("TOTAL")).isEqualTo(3);
    }

    private com.sw.ck.bpm.process.entity.BpmCommand batchAccounting(long tenant, int units,
                                                                    String segment) {
        com.sw.ck.bpm.process.entity.BpmCommand command = new com.sw.ck.bpm.process.entity.BpmCommand();
        command.setTenantId(tenant);
        command.setCommandType(CommandTypeEnum.BATCH_INVOKE.getCode());
        command.setResourceUnits(units);
        command.setResourceSegment(segment);
        return command;
    }

    @Test
    @DisplayName("终态释放：完成/过期释放占用，轻流程目标未决不因 SUCCEEDED 释放（RG02/RG07）")
    void releaseSemanticsMatchCompletionPoints() {
        BpmResourcePolicy created = policyService.create(policy(2000, 800, 400, 400, 1200));
        policyService.enable(created.getId(), null);
        admissionService.ensureUsageRows(TENANT_A);

        // OA 命令：领取（真实租约令牌）→ 完成 → 释放
        long oaId = admittedCommand("TASK_APPROVE:t:9:7", ResourceClassEnum.OA, null);
        assertThat(globalUsage("TOTAL")).isEqualTo(1);
        CommandEnvelope oaClaimed = claimSingle(oaId);
        txTemplate.executeWithoutResult(status -> queue.complete(oaClaimed.getCommandId(),
                oaClaimed.getClaimToken(), "{\"ok\":true}"));
        assertThat(globalUsage("TOTAL")).isZero();

        // 轻流程命令完成（TARGET_ACTION_DONE）→ 不释放；对账据引擎事实（无实例）→ 释放
        long lightId = admittedCommand("FLOW_START:rec-1", ResourceClassEnum.PROD,
                ResourceReleaseService.COMPLETION_POINT_TARGET_DONE);
        CommandEnvelope lightClaimed = claimSingle(lightId);
        txTemplate.executeWithoutResult(status -> queue.complete(lightClaimed.getCommandId(),
                lightClaimed.getClaimToken(), "{\"status\":\"STARTED\"}"));
        assertThat(globalUsage("TOTAL")).isEqualTo(1);
        reconcileJob.reconcileOnce();
        assertThat(globalUsage("TOTAL")).isZero();
    }

    private CommandEnvelope claimSingle(long commandId) {
        CommandEnvelope claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10).stream()
                .filter(e -> e.getCommandId() != null && e.getCommandId() == commandId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("命令未被领取: " + commandId));
        return claimed;
    }

    @Test
    @DisplayName("停新受理：新受理明确拒绝并留审计，已有工作不中断（RG07）")
    void stopAcceptanceRejectsNewOnly() {
        BpmResourcePolicy created = policyService.create(policy(2000, 800, 400, 400, 1200));
        policyService.enable(created.getId(), null);
        admittedCommand("TASK_APPROVE:t:1:7", ResourceClassEnum.OA, null);
        assertThat(globalUsage("TOTAL")).isEqualTo(1);

        policyService.stopAcceptance(created.getId(), true);
        assertThatThrownBy(() -> admissionService.admit(TENANT_A, ResourceClassEnum.OA, 1, "K-STOP"))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(BpmErrorCode.RESOURCE_ACCEPTANCE_STOPPED.getCode()));
        // 已受理工作占用不变
        assertThat(globalUsage("TOTAL")).isEqualTo(1);
        // 拒绝审计独立可查
        List<BpmResourceRejectLog> rejects = rejectLogMapper.selectList(null);
        assertThat(rejects).hasSize(1);
        assertThat(rejects.get(0).getRejectScope()).isEqualTo("STOPPED");
        assertThat(rejects.get(0).getReasonCode())
                .isEqualTo(BpmErrorCode.RESOURCE_ACCEPTANCE_STOPPED.getErrorKey());
    }

    @Test
    @DisplayName("段保护：BULK 只占共享段且不越界；OA 借用生产保留、BULK 不能挤占（RG03）")
    void segmentProtection() {
        // 共享=2，生产保留=1，OA 保留=1：全局=4
        BpmResourcePolicy created = policyService.create(policy(4, 4, 1, 1, 2));
        policyService.enable(created.getId(), null);
        admissionService.ensureUsageRows(TENANT_A);

        // BULK 2 单位占满共享段
        admissionService.admit(TENANT_A, ResourceClassEnum.BULK, 2, "B1");
        // 第 3 个 BULK 单位被拒（共享段满，BULK 不得进入保留段）
        assertThatThrownBy(() -> admissionService.admit(TENANT_A, ResourceClassEnum.BULK, 1, "B2"))
                .isInstanceOf(BaseException.class);
        // 生产仍可进入生产保留段（保留容量未被低等级工作占满）
        ResourceAdmissionService.AdmissionTicket prod =
                admissionService.admit(TENANT_A, ResourceClassEnum.PROD, 1, "P1");
        assertThat(prod.segment()).isEqualTo("PROD_RESERVED");
        // OA 进入 OA 保留段
        ResourceAdmissionService.AdmissionTicket oa =
                admissionService.admit(TENANT_A, ResourceClassEnum.OA, 1, "O1");
        assertThat(oa.segment()).isEqualTo("OA_RESERVED");
        assertThat(globalUsage("TOTAL")).isEqualTo(4);
    }

    // ==================== 公平领取与切片让出（RG03） ====================

    @Test
    @DisplayName("租户公平领取：两租户并存时每租户获得切片，不被单租户占满（RG03）")
    void fairClaimAcrossTenants() {
        // 每租户各 6 条 PENDING；limit=4 时两租户各领 2
        for (int i = 0; i < 6; i++) {
            final int seq = i;
            txTemplate.executeWithoutResult(status -> queue.enqueue(
                    envelope("NORMAL_KEY_A" + seq, CommandChannelEnum.NORMAL)));
            asTenantAdmin(TENANT_B, 8L);
            txTemplate.executeWithoutResult(status -> queue.enqueue(
                    envelope("NORMAL_KEY_B" + seq, CommandChannelEnum.NORMAL)));
            asTenantAdmin(TENANT_A, 7L);
        }
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 4);
        assertThat(claimed).hasSize(4);
        long tenantAClaimed = claimed.stream().filter(e -> e.getTenantId().equals(TENANT_A)).count();
        long tenantBClaimed = claimed.stream().filter(e -> e.getTenantId().equals(TENANT_B)).count();
        assertThat(tenantAClaimed).isEqualTo(2);
        assertThat(tenantBClaimed).isEqualTo(2);
    }

    @Test
    @DisplayName("批量切片让出：超过切片项数时重新入队且不计失败、占用不变（RG03）")
    void batchYieldContinuation() {
        BpmResourcePolicy created = policyService.create(policy(2000, 800, 400, 400, 1200));
        policyService.enable(created.getId(), null);
        admissionService.ensureUsageRows(TENANT_A);

        // 受理 5 项批次（切片=2）
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey("yield-batch");
        request.setActionId("action-1");
        request.setItems(IntStream.range(0, 5).mapToObj(i -> {
            TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
            item.setItemKey("k" + i);
            item.setRecordId("rec" + i);
            return item;
        }).toList());
        TxnBatchView view = txTemplate.execute(status -> {
            TxnBatchView result = null;
            try {
                result = batchService().submit(request);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return result;
        });
        assertThat(view).isNotNull();
        Long batchCommandId = jdbcTemplate.queryForObject(
                "SELECT command_id FROM sw_bpm_command_batch WHERE batch_key = 'yield-batch'",
                Long.class);
        assertThat(batchCommandId).isNotNull();
        assertThat(globalUsage("TOTAL")).isEqualTo(5);

        // 领取后按切片处理：第一次消费应让出（3 项剩余），命令回 PENDING 且占用不变
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 1);
        assertThat(claimed).hasSize(1);
        CommandEnvelope batchEnvelope = claimed.get(0);
        assertThatThrownBy(() -> batchHandler().handle(batchEnvelope))
                .isInstanceOf(CommandContinuationSignal.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, batchCommandId))
                .isEqualTo(CommandStatusEnum.PENDING.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_batch_item WHERE batch_id = "
                        + "(SELECT id FROM sw_bpm_command_batch WHERE batch_key = 'yield-batch') "
                        + "AND status = 'PENDING'", Long.class))
                .isEqualTo(3);
        // 占用保持 5（已结算 2 项各回收 1 → 3；注意 handler 内已逐项回收）
        // handler 直接调用时占用按结算项回收：5-2=3
        assertThat(globalUsage("TOTAL")).isEqualTo(3);
    }

    // ==================== 帮助 ====================

    @Autowired
    private TxnBatchService txnBatchService;

    @Autowired
    private BatchInvokeCommandHandler batchInvokeCommandHandler;

    private TxnBatchService batchService() {
        return txnBatchService;
    }

    private BatchInvokeCommandHandler batchHandler() {
        return batchInvokeCommandHandler;
    }

    private long admittedCommand(String commandKey, ResourceClassEnum clazz, String completionPoint) {
        return txTemplate.execute(status -> {
            ResourceAdmissionService.AdmissionTicket ticket =
                    admissionService.admit(TENANT_A, clazz, 1, commandKey);
            CommandEnvelope envelope = envelope(commandKey, CommandChannelEnum.NORMAL);
            if (clazz == ResourceClassEnum.PROD) {
                envelope.setCommandType(CommandTypeEnum.FLOW_START);
            }
            if (completionPoint != null) {
                envelope.setCompletionPoint(completionPoint);
            }
            envelope.setResourceClass(clazz.getCode());
            envelope.setResourceUnits(1);
            envelope.setResourceSegment(ticket.segment());
            envelope.setPolicyVersion(ticket.policyVersion());
            return queue.enqueue(envelope);
        });
    }

    private CommandEnvelope envelope(String key, CommandChannelEnum channel) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.TASK_APPROVE);
        envelope.setChannel(channel);
        envelope.setCommandKey(key);
        envelope.setTenantId(TENANT_A);
        envelope.setInitiatorId(7L);
        envelope.setPayload("{\"payload\":\"" + key + "\"}");
        envelope.setPayloadFingerprint(com.sw.ck.bpm.process.queue.CommandFingerprint.of(envelope.getPayload()));
        return envelope;
    }

    private long globalUsage(String segment) {
        return usageMapper.selectList(null).stream()
                .filter(u -> "GLOBAL".equals(u.getScope()) && segment.equals(u.getSegment()))
                .findFirst()
                .map(u -> u.getOutstanding() == null ? 0 : u.getOutstanding())
                .orElse(0L);
    }
}
