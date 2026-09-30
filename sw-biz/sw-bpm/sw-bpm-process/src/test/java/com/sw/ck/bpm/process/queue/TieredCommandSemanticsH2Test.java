package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.BpmCommandEffect;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.queue.support.QueueH2TestConfig;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P62 分级执行与统一命令：命令语义核心行为验证（真实 H2 + 真实持久化队列）。
 * <p>
 * 覆盖：统一逻辑身份（同身份重放/异载荷冲突/唯一索引）、准入截止（待执行且效果未发生才可过期、
 * 执行中仅记超期）、效果权威账本（业务已提交但完成记录未写窗口的确定收敛）、
 * 执行权防护（租约回收后旧执行者不得提交效果；效果至多一次）。
 * </p>
 */
@SpringBootTest(classes = QueueH2TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("P62 分级命令语义核心（H2 真实持久化）")
class TieredCommandSemanticsH2Test {

    @Autowired
    private PersistentBpmCommandQueue queue;

    @Autowired
    private CommandEffectRecorder effectRecorder;

    @Autowired
    private TieredCommandReconcileJob reconcileJob;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        LoginUser user = new LoginUser();
        user.setUserId(7L);
        user.setTenantId(1L);
        LoginUserHolder.set(user);
        jdbcTemplate.update("DELETE FROM sw_bpm_command_effect");
        jdbcTemplate.update("DELETE FROM sw_bpm_command");
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private CommandEnvelope envelope(String key, String logicalId, String fingerprint, int deadlineSeconds) {
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(CommandTypeEnum.TASK_APPROVE);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey(key);
        envelope.setTenantId(1L);
        envelope.setInitiatorId(7L);
        envelope.setPayload("{\"payload\":\"" + key + "\"}");
        envelope.setLogicalCommandId(logicalId);
        envelope.setPayloadFingerprint(fingerprint);
        envelope.setTier("REALTIME_ACTION");
        envelope.setCompletionPoint("LOCAL_TX_COMMIT");
        envelope.setDeadlineSeconds(deadlineSeconds);
        return envelope;
    }

    private Long accept(String key, String logicalId, String fingerprint, int deadlineSeconds) {
        return txTemplate.execute(status -> queue.enqueue(envelope(key, logicalId, fingerprint, deadlineSeconds)));
    }

    private CommandEnvelope claimOne() {
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10);
        assertThat(claimed).hasSize(1);
        return claimed.get(0);
    }

    private String statusOf(Long commandId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
    }

    // ==================== 效果权威账本：关闭"业务已提交、完成记录未写"窗口 ====================

    @Test
    @DisplayName("效果权威：业务事务提交效果后进程未写完成记录，对账任务据权威结果确定收敛且不重做业务")
    void effectLedgerClosesCompletionWindow() {
        Long commandId = accept("K-EFFECT", "LOG-CO-1", CommandFingerprint.of("p1"), 30);
        CommandEnvelope claimed = claimOne();
        assertThat(claimed.getDeadlineAt()).isNotNull();

        // 业务效果事务：效果与权威行同事务提交（此处模拟业务提交后进程未及写完成记录）
        txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, claimed.getClaimToken(), "LOG-CO-1",
                "{\"status\":\"SUBMITTED\",\"recordId\":\"R-EFFECT-1\"}", "R-EFFECT-1"));

        assertThat(statusOf(commandId)).as("完成记录尚未写入").isEqualTo(CommandStatusEnum.PROCESSING.getCode());
        BpmCommandEffect effect = effectRecorder.findEffect(commandId);
        assertThat(effect).isNotNull();
        assertThat(effect.getResultJson()).contains("R-EFFECT-1");

        TieredCommandReconcileJob.ReconcileResult result = reconcileJob.reconcileOnce(LocalDateTime.now());
        assertThat(result.completedFromEffect()).isEqualTo(1);
        assertThat(statusOf(commandId)).isEqualTo(CommandStatusEnum.COMPLETED.getCode());
        assertThat(jdbcTemplate.queryForObject("SELECT result FROM sw_bpm_command WHERE id = ?", String.class, commandId))
                .contains("R-EFFECT-1");
        // 权威行仍在（审计/回查），且不因收敛被重复写入
        assertThat(effectRecorder.findEffect(commandId).getBizRef()).isEqualTo("R-EFFECT-1");
    }

    @Test
    @DisplayName("效果至多一次：租约回收后旧执行者提交效果被拒，新领取者写入后效果仍恰一条")
    void staleExecutorCannotCommitEffect() {
        Long commandId = accept("K-STALE", "LOG-CO-2", CommandFingerprint.of("p2"), 30);
        CommandEnvelope first = claimOne();
        String staleToken = first.getClaimToken();

        // 租约过期回收：转 PENDING 并清令牌
        jdbcTemplate.update("UPDATE sw_bpm_command SET claimed_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(10), commandId);
        assertThat(queue.reclaimStale(LocalDateTime.now().minusMinutes(1))).isEqualTo(1);
        assertThat(statusOf(commandId)).isEqualTo(CommandStatusEnum.PENDING.getCode());

        CommandEnvelope second = claimOne();
        String newToken = second.getClaimToken();
        assertThat(newToken).isNotEqualTo(staleToken);

        // 旧执行者迟到：以旧令牌提交效果 → 事务内执行权校验失败，无效果行
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, staleToken, "LOG-CO-2", "{\"status\":\"SUBMITTED\"}", "OLD")))
                .isInstanceOf(BaseException.class);
        assertThat(effectRecorder.findEffect(commandId)).isNull();

        // 新领取者提交效果 → 恰一条
        txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, newToken, "LOG-CO-2", "{\"status\":\"SUBMITTED\",\"recordId\":\"R-NEW\"}", "NEW"));
        assertThat(effectRecorder.findEffect(commandId).getBizRef()).isEqualTo("NEW");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_effect", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("效果权威优先：新领取者据既有权威行跳过业务，重复记录保留首次结果")
    void firstEffectIsAuthoritative() {
        Long commandId = accept("K-AUTH", "LOG-CO-3", CommandFingerprint.of("p3"), 30);
        CommandEnvelope first = claimOne();
        txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, first.getClaimToken(), "LOG-CO-3", "{\"recordId\":\"R-FIRST\"}", "R-FIRST"));

        // 交接后新执行者再次记录同一命令：保留首次权威结果（效果至多一次）
        jdbcTemplate.update("UPDATE sw_bpm_command SET claimed_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(10), commandId);
        queue.reclaimStale(LocalDateTime.now().minusMinutes(1));
        CommandEnvelope second = claimOne();
        txTemplate.executeWithoutResult(status -> effectRecorder.record(
                commandId, second.getClaimToken(), "LOG-CO-3", "{\"recordId\":\"R-SECOND\"}", "R-SECOND"));
        assertThat(effectRecorder.findEffect(commandId).getResultJson()).contains("R-FIRST");
        assertThat(effectRecorder.findEffect(commandId).getResultJson()).doesNotContain("R-SECOND");
    }

    // ==================== 准入截止：只对未执行且效果未发生的命令 ====================

    @Test
    @DisplayName("准入截止：待执行且无效果→EXPIRED；执行中过期仅记超期不改状态；有效果行不判过期")
    void deadlineOnlyExpiresPendingWithoutEffect() {
        Long pendingId = accept("K-DL-PENDING", "LOG-DL-1", CommandFingerprint.of("d1"), 1);
        Long processingId = accept("K-DL-PROC", "LOG-DL-2", CommandFingerprint.of("d2"), 1);
        LocalDateTime future = LocalDateTime.now().plusSeconds(5);

        // 仅领取其中一条（PROCESSING），另一条保持 PENDING 验证过期分支
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 1);
        assertThat(claimed).hasSize(1);
        Long claimedId = claimed.get(0).getCommandId();
        Long otherId = claimedId.equals(pendingId) ? processingId : pendingId;

        int marked = queue.markOverdue(future);
        assertThat(marked).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT overdue_at FROM sw_bpm_command WHERE id = ?",
                LocalDateTime.class, claimedId)).isNotNull();
        assertThat(statusOf(claimedId)).as("执行中不得据此判过期").isEqualTo(CommandStatusEnum.PROCESSING.getCode());

        int expired = queue.expireDue(future);
        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(otherId)).isEqualTo(CommandStatusEnum.EXPIRED.getCode());
        assertThat(jdbcTemplate.queryForObject("SELECT failure_reason FROM sw_bpm_command WHERE id = ?",
                String.class, otherId)).contains("未执行");
        assertThat(statusOf(claimedId)).isEqualTo(CommandStatusEnum.PROCESSING.getCode());

        // 已具效果行的待执行命令不得过期（等待权威结果收敛）
        Long withEffect = accept("K-DL-EFFECT", "LOG-DL-3", CommandFingerprint.of("d3"), 1);
        assertThat(statusOf(withEffect)).isEqualTo(CommandStatusEnum.PENDING.getCode());
        jdbcTemplate.update("INSERT INTO sw_bpm_command_effect (command_id, logical_command_id, claim_token,"
                + " result_json, biz_ref, tenant_id, create_time) VALUES (?, ?, NULL, ?, ?, 1, CURRENT_TIMESTAMP)",
                withEffect, "LOG-DL-3", "{\"status\":\"SUBMITTED\"}", "R-DL");
        assertThat(queue.expireDue(future)).isZero();
        assertThat(statusOf(withEffect)).isEqualTo(CommandStatusEnum.PENDING.getCode());
    }

    // ==================== 统一逻辑身份：重放/冲突与截止校验 ====================

    @Test
    @DisplayName("逻辑身份唯一：同身份重复受理被唯一索引拒绝，可按身份回查并比对载荷指纹")
    void logicalIdentityIsUniqueAndQueryable() {
        String fingerprint = CommandFingerprint.of("payload-A");
        Long first = accept("K-LOGIC-1", "LOG-ID-1", fingerprint, 30);
        assertThat(first).isNotNull();

        assertThatThrownBy(() -> accept("K-LOGIC-2", "LOG-ID-1", CommandFingerprint.of("payload-B"), 30))
                .isInstanceOf(DuplicateKeyException.class);

        CommandEnvelope found = queue.findByLogicalId(1L, "LOG-ID-1").orElseThrow();
        assertThat(found.getCommandKey()).isEqualTo("K-LOGIC-1");
        assertThat(found.getPayloadFingerprint()).isEqualTo(fingerprint);
        assertThat(queue.findByLogicalId(1L, "LOG-ID-MISSING")).isEmpty();
    }

    @Test
    @DisplayName("准入截止参数：默认 30s，超出 1—300 受理即拒绝并冻结截止时间")
    void deadlineSecondsValidatedAndFrozen() {
        Long defaulted = accept("K-DL-DEFAULT", "LOG-DL-D", CommandFingerprint.of("dd"), 0);
        LocalDateTime deadline = jdbcTemplate.queryForObject(
                "SELECT deadline_at FROM sw_bpm_command WHERE id = ?", LocalDateTime.class, defaulted);
        assertThat(deadline).isAfter(LocalDateTime.now().plusSeconds(25));

        assertThatThrownBy(() -> accept("K-DL-BAD1", "LOG-DL-B1", CommandFingerprint.of("b1"), 0 - 1))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> accept("K-DL-BAD2", "LOG-DL-B2", CommandFingerprint.of("b2"), 301))
                .isInstanceOf(BaseException.class);
        assertThat(queue.findByLogicalId(1L, "LOG-DL-B1")).isEmpty();
    }
}
