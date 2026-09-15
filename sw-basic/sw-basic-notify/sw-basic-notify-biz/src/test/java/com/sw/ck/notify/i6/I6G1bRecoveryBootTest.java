package com.sw.ck.notify.i6;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.entity.NotifyMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * G1 第二步（"重启后"恢复）：上一 JVM 停止后，本上下文对同一文件库独立启动，
 * 自主调度（fixedDelay 300ms）从持久状态恢复未完成投递：
 * 不新增第二条业务通知（行 ID 不变），追加 attempt_no=2 的尝试流水，进入明确终态。
 */
@SpringBootTest(classes = I6NotifyRestartSupport.class, properties = "sw.notify.recovery.fixed-delay-ms=300")
class I6G1bRecoveryBootTest {

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeAll
    static void prepareRecoveryState(@Autowired JdbcTemplate jt) {
        NotifyMessage persisted = I6NotifyRestartSupport.findByIdentity(jt, 100L, 7L, "FEISHU");
        if (persisted != null) {
            // 前一 JVM 已失败完成第一步；将重试到期窗口压为当前时刻以便自主调度立即接管
            jt.update("UPDATE sw_notify_message SET next_retry_time = CURRENT_TIMESTAMP, retry_count = 0 WHERE id = ?", persisted.getId());
        }
    }

    @Test
    @DisplayName("G1b 重启后：从持久状态按同一业务通知恢复投递（行不增加、尝试追加）")
    void recoversPersistedDeliveryAfterRestart() {
        NotifyMessage before = I6NotifyRestartSupport.findByIdentity(jdbc, 100L, 7L, "FEISHU");
        assertThat(before).as("上一 JVM 应已持久化失败业务通知").isNotNull();
        final Long rowId = before.getId();

        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer attempt2 = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_notify_send_attempt WHERE message_id = ? AND attempt_no >= 2",
                    Integer.class, rowId);
            assertThat(attempt2).as("恢复轮应追加第 2 次尝试流水").isEqualTo(1);
        });

        NotifyMessage after = I6NotifyRestartSupport.findByIdentity(jdbc, 100L, 7L, "FEISHU");
        assertThat(after.getId()).isEqualTo(rowId);
        assertThat(after.getRetryCount()).isGreaterThanOrEqualTo(1);
        // 明确终态：适配器仍失败 → 保持 FAILED（可重试类），或到达上限后维持 FAILED；绝不是 SUCCESS/SKIP 幌子
        assertThat(after.getDeliveryStatus()).isIn("FAILED", "RESENDING", "TIMEOUT");

        int rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_message WHERE tenant_id = 100 AND recipient_id = 7 AND event_type='TODO_CREATED' AND biz_id='task-g1'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
