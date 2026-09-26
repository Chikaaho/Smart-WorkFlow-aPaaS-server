package com.sw.ck.bootstrap.phase4;

import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 G1 · 持久任务生命周期的真实 PostgreSQL 行为证据。
 *
 * <p>覆盖方向 §5.B/§5.D.2 要求的七类行为，每类都给出正向结果与反向断言：</p>
 * <ol>
 *   <li>原子提交：业务事务提交时持久意图同时可见；</li>
 *   <li>业务回滚：不留下可执行孤儿任务，且同一幂等身份可重新受理；</li>
 *   <li>提交后崩溃恢复：进程在"提交后、消费前"退出，遗留 PENDING 意图可被重新领取（见 restart 用例）；</li>
 *   <li>并发领取：多线程竞争同一意图，只有一个有效领取者；</li>
 *   <li>租约回收与迟到完成隔离：回收后旧领取者的完成/失败写回被拒，不覆盖新持有者；</li>
 *   <li>重复投递：同一稳定业务身份只产生一条持久意图；</li>
 *   <li>重试耗尽：进入可审计终态 FAILED，不再被领取；退避期内不可领取、到点后可领取。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase4 G1 · 持久意图生命周期 · 真实 PostgreSQL 行为证据")
class Phase4PgLifecycleBehaviourTest extends Phase4PgSupport {

    private static final String PREFIX = "P4LIFE:";

    private BpmCommandQueue queue;

    @BeforeAll
    void bootAll() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrate();
        boot(41L);
        seedTenantAndUser(TENANT_A, USER_A, "life");
        queue = app.getBean(BpmCommandQueue.class);
        System.out.println("[P4-EV] g1.database-identity=" + databaseIdentity());
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    /** 用例间隔离：本类遗留的非终态意图就地终结，避免下一条用例的"可领取集合"被污染。 */
    @org.junit.jupiter.api.AfterEach
    void retireLeftovers() {
        int retired = jdbc.update("update sw_bpm_command set status = ?, finished_at = now()"
                + " where command_key like ? and status in (?, ?)",
                CommandStatusEnum.FAILED.getCode(), PREFIX + "%",
                CommandStatusEnum.PENDING.getCode(), CommandStatusEnum.PROCESSING.getCode());
        if (retired > 0) {
            System.out.println("[P4-EV] g1.case-cleanup retiredLeftoverCommands=" + retired);
        }
    }

    // ==================== 1 · 原子提交 ====================

    @Test
    @DisplayName("1 业务事务提交时持久意图同事务可见")
    void intentCommitsWithBusinessTransaction() {
        String key = PREFIX + "commit";
        Long commandId = inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        assertThat(commandId).isNotNull();
        assertThat(count("select count(*) from sw_bpm_command where command_key = ?", key)).isEqualTo(1L);
        assertThat(text("select status from sw_bpm_command where id = ?", commandId))
                .isEqualTo(CommandStatusEnum.PENDING.getCode());
        assertThat(count("select count(*) from sw_bpm_command where id = ? and tenant_id = ?", commandId, TENANT_A))
                .isEqualTo(1L);
        System.out.println("[P4-EV] g1.atomic-commit key=" + key + " commandId=" + commandId
                + " status=PENDING rows=1 tenant=" + TENANT_A);
    }

    // ==================== 2 · 业务回滚 ====================

    @Test
    @DisplayName("2 业务事务回滚不留可执行孤儿任务，且同一身份可重新受理")
    void businessRollbackLeavesNoExecutableOrphan() {
        String key = PREFIX + "rollback";
        inTransaction(false, () -> queue.enqueue(envelope(key, TENANT_A)));
        long afterRollback = count("select count(*) from sw_bpm_command where command_key = ?", key);
        assertThat(afterRollback).isZero();
        // 反向断言：回滚后同一幂等身份仍可受理（无残留占用）
        Long reaccepted = inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        assertThat(reaccepted).isNotNull();
        assertThat(count("select count(*) from sw_bpm_command where command_key = ?", key)).isEqualTo(1L);
        System.out.println("[P4-EV] g1.business-rollback key=" + key + " orphanRowsAfterRollback=" + afterRollback
                + " reacceptedCommandId=" + reaccepted);
    }

    // ==================== 6 · 重复投递 ====================

    @Test
    @DisplayName("6 重复受理同一稳定业务身份只产生一条持久意图")
    void duplicateEnqueueKeepsSingleDurableIntent() {
        String key = PREFIX + "duplicate";
        Long first = inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        boolean duplicateRejected = false;
        try {
            inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            duplicateRejected = true;
        }
        long rows = count("select count(*) from sw_bpm_command where command_key = ?", key);
        Optional<CommandEnvelope> existing = asSystem(() -> queue.findByKey(TENANT_A, key));
        assertThat(duplicateRejected).as("唯一索引必须拒绝第二次受理").isTrue();
        assertThat(rows).isEqualTo(1L);
        assertThat(existing).isPresent();
        assertThat(existing.orElseThrow().getCommandId()).isEqualTo(first);
        System.out.println("[P4-EV] g1.duplicate-delivery key=" + key + " duplicateRejected=" + duplicateRejected
                + " rows=" + rows + " idempotentCommandId=" + first);
    }

    // ==================== 4 · 并发领取 ====================

    @Test
    @DisplayName("4 并发领取同一意图：只有一个有效领取者")
    void concurrentClaimHasSingleWinner() throws Exception {
        String key = PREFIX + "concurrent";
        Long commandId = inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        int workers = 4;
        CyclicBarrier barrier = new CyclicBarrier(workers);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<String> winnerToken = new AtomicReference<>();
        List<Long> claimedIds = new CopyOnWriteArrayList<>();
        for (int i = 0; i < workers; i++) {
            int index = i;
            Thread worker = new Thread(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    List<CommandEnvelope> claimed = asSystem(
                            () -> queue.claimDue(List.of(CommandChannelEnum.NORMAL), 5));
                    for (CommandEnvelope envelope : claimed) {
                        claimedIds.add(envelope.getCommandId());
                        winners.incrementAndGet();
                        winnerToken.set(envelope.getClaimToken());
                    }
                } catch (Exception e) {
                    System.out.println("[P4-EV] g1.concurrent-claim worker-error=" + index
                            + " exceptionClass=" + e.getClass().getSimpleName());
                } finally {
                    com.sw.ck.security.holder.LoginUserHolder.clear();
                    done.countDown();
                }
            }, "p4-claim-" + index);
            worker.start();
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(winners.get()).as("同一意图只能被一个消费者领取").isEqualTo(1);
        assertThat(count("select count(*) from sw_bpm_command where id = ? and status = ?",
                commandId, CommandStatusEnum.PROCESSING.getCode())).isEqualTo(1L);
        assertThat(text("select claim_token from sw_bpm_command where id = ?", commandId))
                .isEqualTo(winnerToken.get());
        System.out.println("[P4-EV] g1.concurrent-claim key=" + key + " workers=" + workers
                + " winners=" + winners.get() + " claimedIds=" + claimedIds);
    }

    // ==================== 5 · 租约回收 + 迟到完成隔离 ====================

    @Test
    @DisplayName("5 租约回收后旧领取者的迟到写回被拒，不覆盖新持有者")
    void leaseReclaimIsolatesLateCompletion() {
        String key = PREFIX + "lease";
        Long commandId = inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        CommandEnvelope first = claimOne();
        String staleToken = String.valueOf(first.getClaimToken());
        int reclaimed = asSystem(() -> queue.reclaimStale(LocalDateTime.now().plusSeconds(1)));
        assertThat(reclaimed).isEqualTo(1);
        assertThat(text("select status from sw_bpm_command where id = ?", first.getCommandId()))
                .isEqualTo(CommandStatusEnum.PENDING.getCode());
        assertThat(text("select claim_token from sw_bpm_command where id = ?", first.getCommandId())).isNull();

        Optional<CommandEnvelope> second = asSystem(() ->
                queue.claimDue(List.of(CommandChannelEnum.NORMAL), 1)).stream().findFirst();
        assertThat(second).isPresent();
        String freshToken = String.valueOf(second.orElseThrow().getClaimToken());
        assertThat(freshToken).isNotEqualTo(staleToken);

        // 旧领取者迟到完成：必须被拒（状态/结果均不被覆盖）
        asSystem(() -> { queue.complete(first.getCommandId(), staleToken, "{\"status\":\"LATE\"}"); return null; });
        assertThat(text("select status from sw_bpm_command where id = ?", first.getCommandId()))
                .isEqualTo(CommandStatusEnum.PROCESSING.getCode());
        assertThat(text("select result from sw_bpm_command where id = ?", first.getCommandId())).isNull();

        // 旧领取者迟到失败改派：同样被拒，不得扰乱新持有者
        boolean staleFail = asSystem(() -> queue.failAndScheduleRetry(first.getCommandId(), staleToken,
                "迟到失败", 5, 1000L));
        assertThat(staleFail).isFalse();
        assertThat(text("select claim_token from sw_bpm_command where id = ?", first.getCommandId()))
                .isEqualTo(freshToken);

        // 新持有者写回生效
        asSystem(() -> { queue.complete(first.getCommandId(), freshToken, "{\"status\":\"STARTED\"}"); return null; });
        assertThat(text("select status from sw_bpm_command where id = ?", first.getCommandId()))
                .isEqualTo(CommandStatusEnum.COMPLETED.getCode());
        System.out.println("[P4-EV] g1.lease-reclaim key=" + key + " reclaimed=" + reclaimed
                + " staleTokenRejected=true staleFailRejected=" + !staleFail
                + " finalStatus=" + text("select status from sw_bpm_command where id = ?", first.getCommandId()));
    }

    // ==================== 7 · 重试与耗尽 ====================

    @Test
    @DisplayName("7 退避期内不可领取、到点后可领取，重试耗尽进入可审计终态")
    void retryBackoffAndExhaustion() {
        String key = PREFIX + "retry";
        Long commandId = inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        CommandEnvelope claimed = claimOne();
        boolean scheduled = asSystem(() -> queue.failAndScheduleRetry(claimed.getCommandId(),
                claimed.getClaimToken(), "瞬时失败", 3, 60_000L));
        assertThat(scheduled).isTrue();
        assertThat(text("select status from sw_bpm_command where id = ?", commandId))
                .isEqualTo(CommandStatusEnum.PENDING.getCode());
        assertThat(count("select count(*) from sw_bpm_command where id = ? and next_retry_at > now()", commandId))
                .isEqualTo(1L);
        // 退避未到点：不得被领取
        assertThat(asSystem(() -> queue.claimDue(List.of(CommandChannelEnum.NORMAL), 5))).isEmpty();

        // 模拟时间推进：把退避时间置为已过期，等价于等到退避点
        jdbc.update("update sw_bpm_command set next_retry_at = ? where id = ?",
                LocalDateTime.now().minusSeconds(1), commandId);
        CommandEnvelope second = claimOne();
        assertThat(second.getCommandId()).isEqualTo(commandId);
        assertThat(second.getRetryCount()).isEqualTo(1);
        assertThat(second.getClaimToken()).isNotEqualTo(claimed.getClaimToken());

        // 第二次尝试也失败：仍在预算内，回到可重试态
        boolean scheduledSecond = asSystem(() -> queue.failAndScheduleRetry(second.getCommandId(),
                second.getClaimToken(), "第二次失败", 3, 1L));
        assertThat(scheduledSecond).isTrue();
        assertThat(text("select retry_count from sw_bpm_command where id = ?", commandId)).isEqualTo("2");

        // 第三次失败：retryCount+1 >= maxRetries → 终态 FAILED（可审计），且不再可领取
        CommandEnvelope third = claimOne();
        boolean retryAgain = asSystem(() -> queue.failAndScheduleRetry(third.getCommandId(),
                third.getClaimToken(), "再次失败", 3, 60_000L));
        assertThat(retryAgain).isFalse();
        assertThat(text("select status from sw_bpm_command where id = ?", commandId))
                .isEqualTo(CommandStatusEnum.FAILED.getCode());
        assertThat(text("select failure_reason from sw_bpm_command where id = ?", commandId)).contains("再次失败");
        assertThat(text("select finished_at from sw_bpm_command where id = ?", commandId)).isNotNull();
        assertThat(asSystem(() -> queue.claimDue(List.of(CommandChannelEnum.NORMAL), 5))).isEmpty();
        System.out.println("[P4-EV] g1.retry-exhaustion key=" + key + " attempts=3 terminal=FAILED"
                + " retryCount=" + text("select retry_count from sw_bpm_command where id = ?", commandId)
                + " claimableAfterTerminal=0");
    }

    // ==================== 3 · 提交后崩溃的遗留意图可回收 ====================

    @Test
    @DisplayName("3 消费者崩溃留下的 PROCESSING 意图可被回收并重新领取")
    void staleProcessingIsReclaimedForRecovery() {
        String key = PREFIX + "crash";
        Long commandId = inTransaction(true, () -> queue.enqueue(envelope(key, TENANT_A)));
        CommandEnvelope crashed = claimOne();
        // 模拟领取者崩溃：不写回、不释放，仅留下 PROCESSING + 领取时间
        assertThat(text("select status from sw_bpm_command where id = ?", commandId))
                .isEqualTo(CommandStatusEnum.PROCESSING.getCode());
        int reclaimed = asSystem(() -> queue.reclaimStale(LocalDateTime.now().plusSeconds(1)));
        assertThat(reclaimed).isEqualTo(1);
        CommandEnvelope recovered = claimOne();
        assertThat(recovered.getCommandId()).isEqualTo(commandId);
        asSystem(() -> { queue.complete(recovered.getCommandId(), recovered.getClaimToken(),
                "{\"status\":\"RECOVERED\"}"); return null; });
        assertThat(text("select status from sw_bpm_command where id = ?", commandId))
                .isEqualTo(CommandStatusEnum.COMPLETED.getCode());
        System.out.println("[P4-EV] g1.stale-claim-reclaim key=" + key + " reclaimed=" + reclaimed
                + " recoveredByNewClaim=true crashedToken=" + (crashed.getClaimToken() != null));
    }

    // ==================== 助手 ====================

    private CommandEnvelope envelope(String key, Long tenantId) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.SCHEDULED_FLOW_START);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey(key);
        envelope.setTenantId(tenantId);
        envelope.setInitiatorId(USER_A);
        envelope.setPayload("{\"jobId\":1,\"fireTimeEpochMillis\":1}");
        return envelope;
    }


    /** 队列入口要求已认证租户上下文（与生产调度线程一致）。 */
    private <T> T asSystem(java.util.concurrent.Callable<T> action) {
        return asTenant(TENANT_A, USER_A, action);
    }

    /** 领取唯一待处理意图；未领到直接失败（用例前置条件）。 */
    private CommandEnvelope claimOne() {
        Optional<CommandEnvelope> claimed = asSystem(() ->
                queue.claimDue(List.of(CommandChannelEnum.NORMAL), 1)).stream().findFirst();
        assertThat(claimed).as("应能领取到待处理意图").isPresent();
        return claimed.orElseThrow();
    }
}
