package com.sw.ck.notify.i6;

import com.sw.ck.notify.entity.NotifyMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * G1c（第三 JVM）：同一文件库继续恢复——同一业务消息按退避策略连续尝试，
 * attempt 序号连续、业务行恒为 1 行，直至明确终态 RETRY_EXHAUSTED 且停止继续投递。
 */
@SpringBootTest(classes = I6NotifyRestartSupport.class, properties = "sw.notify.recovery.fixed-delay-ms=200")
class I6G1cRecoveryExhaustedBootTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void renewRetryWindow(@Autowired JdbcTemplate jt) {
        NotifyMessage persisted = I6NotifyRestartSupport.findByIdentity(jt, 100L, 7L, "FEISHU");
        org.junit.jupiter.api.Assertions.assertNotNull(persisted, "前两轮 JVM 应已持久化同一业务消息");
        jt.update("UPDATE sw_notify_message SET next_retry_time = CURRENT_TIMESTAMP WHERE id = ? AND delivery_status IN ('FAILED','TIMEOUT','PENDING')",
                persisted.getId());
    }

    @Test
    @DisplayName("G1c 连续重试至明确终态：attempt 连续、行数 1、终态 RETRY_EXHAUSTED 且停止投递")
    void retriesReachDefiniteTerminalState() {
        NotifyMessage before = I6NotifyRestartSupport.findByIdentity(jdbc, 100L, 7L, "FEISHU");
        final Long rowId = before.getId();

        await().atMost(java.time.Duration.ofSeconds(90)).untilAsserted(() -> {
            // 每轮把下一次退避到期时间压回当前时刻（等价于等待指数退避到期），
            // 实际投递与终态写回由调度器自主完成，直至 failure_class 进入明确终态。
            jdbc.update("UPDATE sw_notify_message SET next_retry_time = CURRENT_TIMESTAMP WHERE id = ? AND failure_class = 'RETRYABLE'",
                    rowId);
            Integer exhausted = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_notify_message WHERE id = ? AND failure_class = 'RETRY_EXHAUSTED'",
                    Integer.class, rowId);
            assertThat(exhausted).as("重试耗尽后应进入明确终态 RETRY_EXHAUSTED").isEqualTo(1);
        });

        // attempt 连续性：从 1 起无跳号地递增
        List<Integer> attemptNos = jdbc.query(
                "SELECT attempt_no FROM sw_notify_send_attempt WHERE message_id = ? ORDER BY attempt_no",
                (rs, i) -> rs.getInt("attempt_no"), rowId);
        for (int i = 0; i < attemptNos.size(); i++) {
            assertThat(attemptNos.get(i)).isEqualTo(i + 1);
        }

        // 终态后停止投递：记录最大 attempt 快照brief，等待一个调度周期后不再新增
        Integer finalAttemptBefore = jdbc.queryForObject(
                "SELECT MAX(attempt_no) FROM sw_notify_send_attempt WHERE message_id = ?", Integer.class, rowId);
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Integer finalAttemptAfter = jdbc.queryForObject(
                "SELECT MAX(attempt_no) FROM sw_notify_send_attempt WHERE message_id = ?", Integer.class, rowId);
        assertThat(finalAttemptAfter).isEqualTo(finalAttemptBefore);

        // 业务行恒为 1
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_message WHERE tenant_id = 100 AND recipient_id = 7 AND event_type='TODO_CREATED' AND biz_id='task-g1'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
