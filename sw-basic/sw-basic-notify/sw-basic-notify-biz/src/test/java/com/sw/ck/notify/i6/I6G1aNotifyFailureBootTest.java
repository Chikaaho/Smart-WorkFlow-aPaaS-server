package com.sw.ck.notify.i6;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.entity.NotifyMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1 第一步（重启前）：真实业务通知对象 + 真实失败适配器。
 * <p>同一（租户/事件/业务对象/接收人/渠道）对象在首次投递失败后：
 * 消息行保持单一条目（不产生第二行），delivery_status=FAILED，
 * failure_class=RETRYABLE，存在 attempt_no=1 的失败尝试流水。</p>
 */
@SpringBootTest(classes = I6NotifyRestartSupport.class, properties = {"sw.notify.recovery.fixed-delay-ms=600000"})
class I6G1aNotifyFailureBootTest {

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private I6NotifyRestartSupport.TestLoginContext login;

    @Autowired
    private NotifyFacade notifyFacade;

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        try { jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_message (id BIGINT PRIMARY KEY, recipient_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL, content TEXT NOT NULL, biz_type VARCHAR(30) NOT NULL, biz_id VARCHAR(64), is_read BOOLEAN DEFAULT FALSE, channel VARCHAR(40) NOT NULL DEFAULT 'IN_APP', delivery_status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS', external_message_id VARCHAR(200), failure_reason VARCHAR(500), idempotency_key VARCHAR(200), event_type VARCHAR(40) DEFAULT 'SYSTEM', occurrence_no BIGINT DEFAULT 1, template_id BIGINT, template_version INT, link_type VARCHAR(32), link_id VARCHAR(64), retry_count INT DEFAULT 0, next_retry_time TIMESTAMP, failure_class VARCHAR(40), receipt_digest VARCHAR(200), tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, create_by BIGINT, update_by BIGINT)"); } catch (Exception ignored) { }
        try { jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_send_attempt (id BIGINT PRIMARY KEY, message_id BIGINT NOT NULL, attempt_no INT NOT NULL, channel VARCHAR(32), status VARCHAR(20) NOT NULL, failure_reason VARCHAR(500), external_message_id VARCHAR(200), failure_class VARCHAR(40), started_at TIMESTAMP, finished_at TIMESTAMP, create_by BIGINT, update_by BIGINT, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
    }

    @BeforeEach
    void setUp() {
        login.set(100L, 7L);
    }

    @Test
    @DisplayName("G1a 首次投递失败：审批后的业务通知失败持久化且单行在册")
    void firstDeliveryFailurePersists() {
        jdbc.update("DELETE FROM sw_notify_send_attempt");
        jdbc.update("DELETE FROM sw_notify_message");

        NotifySendResult result = notifyFacade.send(NotifySendRequest.builder()
                .channel(NotifyChannel.FEISHU)
                .recipientId(7L)
                .title("待办提醒")
                .content("您有一条新的待办任务")
                .tenantId(100L)
                .eventType("TODO_CREATED")
                .bizId("task-g1")
                .occurrenceNo(1L)
                .linkType("WF_TASK")
                .linkId("task-g1")
                .build())
                .orElseThrow();

        assertThat(result.getStatus()).isEqualTo("FAILED");

        NotifyMessage persisted = I6NotifyRestartSupport.findByIdentity(jdbc, 100L, 7L, "FEISHU");
        assertThat(persisted).isNotNull();
        assertThat(persisted.getDeliveryStatus()).isEqualTo("FAILED");
        assertThat(persisted.getFailureClass()).isEqualToIgnoringCase("RETRYABLE");
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_send_attempt WHERE message_id = ?", Integer.class, persisted.getId());
        assertThat(attempts).isEqualTo(1);
    }
}
