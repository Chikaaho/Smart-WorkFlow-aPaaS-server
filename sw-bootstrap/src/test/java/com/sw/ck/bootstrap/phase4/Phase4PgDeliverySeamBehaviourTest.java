package com.sw.ck.bootstrap.phase4;

import com.sw.ck.bpm.api.event.BpmDeviceCommandEvent;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.job.CommandCompensationJob;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.openapi.biz.job.OpenApiCallbackRecoveryJob;
import com.sw.ck.openapi.biz.listener.OpenApiCallbackIntentRecorder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 G2 · 交付类可靠接缝的端到端内部行为证据（真实 PostgreSQL）。
 *
 * <p>覆盖接缝：审批设备命令（{@code BpmDeviceCommandEvent} → 持久指令 + 补偿重试）、
 * 通知意图（{@code BpmNotifyEvent} → 可投递意图 + 恢复投递 + 重试耗尽终态）、
 * OpenAPI 回调（终态事件 → 回调任务 + 恢复投递 + 去重 + 重试耗尽）。外部对端使用本机受控
 * HTTP 服务，不冒称真实厂商送达。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase4 G2 · 交付类接缝端到端行为 · 真实 PostgreSQL")
class Phase4PgDeliverySeamBehaviourTest extends Phase4PgSupport {

    private static final String APPROVAL_PI = "p4pi-1";
    private static final String DEVICE_NAME = "p4-device-1";
    private static final String COMMAND_KEY = "reboot";
    private static final String CALLBACK_APP_OK = "p4app-ok";
    private static final String CALLBACK_APP_DOWN = "p4app-down";

    private DomainEventPublisher eventPublisher;
    private CommandQueueService commandQueueService;
    private CommandCompensationJob commandCompensationJob;
    private NotifyFacade notifyFacade;
    private com.sw.ck.notify.service.NotifyDeliveryRecoveryService notifyRecoveryService;
    private OpenApiCallbackIntentRecorder callbackRecorder;
    private OpenApiCallbackRecoveryJob callbackRecoveryJob;

    private HttpServer callbackServer;
    private volatile int callbackHits;

    @BeforeAll
    void bootAll() throws Exception {
        callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        callbackServer.createContext("/callback", exchange -> {
            callbackHits++;
            byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        callbackServer.start();
        int callbackPort = callbackServer.getAddress().getPort();

        ensureEvidenceDatabase();
        cleanMigrate();
        boot(43L);
        seedTenantAndUser(TENANT_A, USER_A, "delivery");
        eventPublisher = app.getBean(DomainEventPublisher.class);
        commandQueueService = app.getBean(CommandQueueService.class);
        commandCompensationJob = app.getBean(CommandCompensationJob.class);
        notifyFacade = app.getBean(NotifyFacade.class);
        notifyRecoveryService = app.getBean(com.sw.ck.notify.service.NotifyDeliveryRecoveryService.class);
        callbackRecorder = app.getBean(OpenApiCallbackIntentRecorder.class);
        callbackRecoveryJob = app.getBean(OpenApiCallbackRecoveryJob.class);
        // 设备命令接缝要求设备存在（与生产同路径解析设备身份）
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, product_id, device_name, status, manage_status, process_access_enabled)"
                        + " values (9460, now(), now(), 0, ?, 0, ?, 'P4 设备', 'p4-product', ?, 'ONLINE', 'MANAGED', 1)"
                        + " on conflict (id) do nothing",
                TENANT_A, DEVICE_NAME, DEVICE_NAME);
        seedCallbackApp(CALLBACK_APP_OK, "http://127.0.0.1:" + callbackPort + "/callback");
        seedCallbackApp(CALLBACK_APP_DOWN, "http://127.0.0.1:9/callback");
        System.out.println("[P4-EV] g2.delivery-seam-context=ready callbackPeer=local-http-server"
                + " callbackApps=" + CALLBACK_APP_OK + "," + CALLBACK_APP_DOWN);
    }

    @AfterAll
    void stopAll() {
        shutdown();
        if (callbackServer != null) {
            callbackServer.stop(0);
        }
    }

    // ==================== 接缝 · 审批设备命令 ====================

    @Test
    @Order(1)
    @DisplayName("设备命令：审批事件在业务事务内落地稳定身份的持久指令，重复投递只产生一条")
    void deviceCommandIntentIsDurableAndIdempotent() {
        BpmDeviceCommandEvent event = new BpmDeviceCommandEvent(APPROVAL_PI, "p4-product", DEVICE_NAME,
                COMMAND_KEY, "PROPERTY", TENANT_A, USER_A);
        asTenant(TENANT_A, USER_A, () -> {
            inTransaction(true, () -> {
                eventPublisher.publish(event);
                return null;
            });
            return null;
        });
        String idempotentKey = "APPROVAL:" + APPROVAL_PI + ":" + DEVICE_NAME + ":" + COMMAND_KEY;
        assertThat(count("select count(*) from sw_iot_device_command where idempotent_key = ?", idempotentKey))
                .isEqualTo(1L);
        assertThat(text("select status from sw_iot_device_command where idempotent_key = ?", idempotentKey))
                .isEqualTo("QUEUED");
        assertThat(text("select approval_biz_id from sw_iot_device_command where idempotent_key = ?", idempotentKey))
                .isEqualTo(APPROVAL_PI);

        // 重复投递同一审批业务身份：不产生第二条指令
        asTenant(TENANT_A, USER_A, () -> {
            inTransaction(true, () -> {
                eventPublisher.publish(new BpmDeviceCommandEvent(APPROVAL_PI, "p4-product", DEVICE_NAME,
                        COMMAND_KEY, "PROPERTY", TENANT_A, USER_A));
                return null;
            });
            return null;
        });
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", APPROVAL_PI))
                .isEqualTo(1L);

        // 审批事务回滚：不留下可执行指令
        String rolledBackPi = "p4pi-rollback";
        asTenant(TENANT_A, USER_A, () -> {
            inTransaction(false, () -> {
                eventPublisher.publish(new BpmDeviceCommandEvent(rolledBackPi, "p4-product", DEVICE_NAME,
                        COMMAND_KEY, "PROPERTY", TENANT_A, USER_A));
                return null;
            });
            return null;
        });
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", rolledBackPi)).isZero();
        System.out.println("[P4-EV] g2.device-command idempotentKey=" + idempotentKey
                + " rows=1 duplicateRows=1 rollbackRows=0 status=QUEUED");
    }

    @Test
    @Order(2)
    @DisplayName("设备命令：补偿调度发起真实重试；消费者崩溃留下的 SENDING 指令被租约回收后重试")
    void deviceCommandCompensationRetriesAndReclaimsLease() {
        String commandRowId = text("select id from sw_iot_device_command where approval_biz_id = ?", APPROVAL_PI);
        assertThat(commandRowId).isNotNull();
        long rowId = Long.parseLong(commandRowId);

        // 瞬时失败：retry_count=0 且未过期 → 补偿调度应领取并真实尝试发送
        jdbc.update("update sw_iot_device_command set status = 'FAILED', retry_count = 0, last_error = '首次失败',"
                + " expiry_time = ? where id = ?", LocalDateTime.now().plusHours(2), rowId);
        commandCompensationJob.execute();
        assertThat(Integer.parseInt(text("select retry_count from sw_iot_device_command where id = ?", rowId)))
                .as("补偿调度必须真的领取并尝试重发（旧实现为打桩）").isGreaterThanOrEqualTo(1);

        // 消费者崩溃：指令停留在 SENDING 且租约超时 → 必须被回收为可重试失败
        jdbc.update("update sw_iot_device_command set status = 'SENDING', last_error = null,"
                        + " update_time = ?, expiry_time = ? where id = ?",
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusHours(2), rowId);
        commandCompensationJob.execute();
        String statusAfterReclaim = text("select status from sw_iot_device_command where id = ?", rowId);
        String reclaimError = text("select last_error from sw_iot_device_command where id = ?", rowId);
        assertThat(statusAfterReclaim)
                .as("崩溃在发送中的指令不得永久停留在 SENDING（租约必须被回收）")
                .isNotEqualTo("SENDING");
        System.out.println("[P4-EV] g2.device-command-retry rowId=" + rowId
                + " retryCount=" + text("select retry_count from sw_iot_device_command where id = ?", rowId)
                + " statusAfterStaleReclaim=" + statusAfterReclaim
                + " reclaimErrorPresent=" + (reclaimError != null && !reclaimError.isBlank()));
    }

    // ==================== 接缝 · 通知意图 ====================

    @Test
    @Order(3)
    @DisplayName("通知：外部渠道先落可投递意图；无适配器按不可重试终态收口")
    void notifyIntentIsPersistedBeforeChannelIo() {
        String bizId = "p4-notify-delivery-1";
        NotifySendRequest request = NotifySendRequest.builder()
                .channel(NotifyChannel.EMAIL).recipientId(USER_A).title("审批通过").content("已通过")
                .bizId(bizId).tenantId(TENANT_A).eventType("PROCESS_APPROVED").occurrenceNo(1L)
                .build();
        asTenant(TENANT_A, USER_A, () -> notifyFacade.recordIntent(request));
        assertThat(text("select delivery_status from sw_notify_message where biz_id = ?", bizId))
                .isEqualTo("PENDING");
        assertThat(text("select failure_class from sw_notify_message where biz_id = ?", bizId))
                .isEqualTo("RETRYABLE");
        assertThat(text("select next_retry_time from sw_notify_message where biz_id = ?", bizId)).isNotNull();

        notifyRecoveryService.recoverDue();
        assertThat(text("select delivery_status from sw_notify_message where biz_id = ?", bizId))
                .isEqualTo("FAILED");
        assertThat(text("select failure_class from sw_notify_message where biz_id = ?", bizId))
                .as("无生产适配器属不可重试结论，不得无限重试").isEqualTo("NON_RETRYABLE");
        System.out.println("[P4-EV] g2.notify-intent-persist bizId=" + bizId
                + " intentStatus=PENDING then=NON_RETRYABLE terminal");
    }

    @Test
    @Order(4)
    @DisplayName("通知：重试预算耗尽进入可审计终态，停止自动重试")
    void notifyRetryExhaustionEntersAuditableTerminalState() {
        String bizId = "p4-notify-exhausted";
        asTenant(TENANT_A, USER_A, () -> {
            notifyFacade.recordIntent(NotifySendRequest.builder()
                    .channel(NotifyChannel.EMAIL).recipientId(USER_A).title("t").content("c")
                    .bizId(bizId).tenantId(TENANT_A).eventType("PROCESS_APPROVED").occurrenceNo(1L)
                    .build());
            return null;
        });
        jdbc.update("update sw_notify_message set delivery_status = 'FAILED', failure_class = 'RETRYABLE',"
                + " retry_count = 5, next_retry_time = ? where biz_id = ?",
                LocalDateTime.now().minusMinutes(1), bizId);
        int recovered = notifyRecoveryService.recoverDue();
        assertThat(text("select failure_class from sw_notify_message where biz_id = ?", bizId))
                .isEqualTo("RETRY_EXHAUSTED");
        assertThat(text("select next_retry_time from sw_notify_message where biz_id = ?", bizId))
                .as("终态不再排出重试时间").isNull();
        System.out.println("[P4-EV] g2.notify-retry-exhausted bizId=" + bizId
                + " retryCount=5 failureClass=RETRY_EXHAUSTED nextRetryTime=null batchRecovered=" + recovered);
    }

    @Test
    @Order(5)
    @DisplayName("通知：投递中被中断的 RESENDING 意图必须被租约回收，不得永久停留")
    void notifyStuckResendingIsReclaimed() {
        String bizId = "p4-notify-stuck-resending";
        asTenant(TENANT_A, USER_A, () -> {
            notifyFacade.recordIntent(NotifySendRequest.builder()
                    .channel(NotifyChannel.EMAIL).recipientId(USER_A).title("t").content("c")
                    .bizId(bizId).tenantId(TENANT_A).eventType("PROCESS_APPROVED").occurrenceNo(1L)
                    .build());
            return null;
        });
        // 模拟进程在"已认领投递"之后崩溃：状态停在 RESENDING，租约时间已过期
        jdbc.update("update sw_notify_message set delivery_status = 'RESENDING', failure_class = 'RETRYABLE',"
                        + " retry_count = 1, next_retry_time = ?, update_time = ? where biz_id = ?",
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().minusMinutes(30), bizId);
        notifyRecoveryService.recoverDue();
        String status = text("select delivery_status from sw_notify_message where biz_id = ?", bizId);
        assertThat(status)
                .as("崩溃在投递中的意图不得永久停留在 RESENDING（恢复调度必须能发现它）")
                .isNotEqualTo("RESENDING");
        System.out.println("[P4-EV] g2.notify-stale-resending bizId=" + bizId
                + " statusAfterRecovery=" + status
                + " failureClass=" + text("select failure_class from sw_notify_message where biz_id = ?", bizId));
    }

    // ==================== 接缝 · OpenAPI 回调 ====================

    @Test
    @Order(6)
    @DisplayName("OpenAPI 回调：终态事件登记持久回调任务，恢复调度投递成功并关闭任务")
    void openApiCallbackTaskIsPersistedAndDelivered() {
        String bizRef = "p4pi-callback-1";
        int before = callbackHits;
        BpmNotifyEvent event = new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, TENANT_A, USER_A, bizRef);
        int recorded = asTenant(TENANT_A, USER_A, () -> callbackRecorder.record(event));
        assertThat(recorded).isGreaterThanOrEqualTo(1);
        assertThat(text("select status from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_OK)).isEqualTo("PENDING");
        // 重复发布同一业务事件：只保留一条任务
        int recordedAgain = asTenant(TENANT_A, USER_A, () -> callbackRecorder.record(event));
        assertThat(count("select count(*) from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_OK)).isEqualTo(1L);

        callbackRecoveryJob.recoverDue();
        assertThat(text("select status from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_OK)).isEqualTo("SUCCESS");
        assertThat(text("select delivered_at from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_OK)).isNotNull();
        assertThat(callbackHits - before).as("受控对端确实收到一次回调").isEqualTo(1);
        assertThat(count("select count(*) from sw_openapi_callback_log where biz_ref = ? and status = 'SUCCESS'",
                bizRef)).isEqualTo(1L);

        // 再次恢复：已投递任务不得重复投递
        callbackRecoveryJob.recoverDue();
        assertThat(callbackHits - before).isEqualTo(1);
        System.out.println("[P4-EV] g2.openapi-callback bizRef=" + bizRef + " recordedFirst=" + recorded
                + " recordedDuplicate=" + recordedAgain + " status=SUCCESS peerHits=1 duplicateHits=0");
    }

    @Test
    @Order(7)
    @DisplayName("OpenAPI 回调：对端不可达时按退避重试，预算耗尽进入 RETRY_EXHAUSTED")
    void openApiCallbackRetryExhaustion() {
        String bizRef = "p4pi-callback-down";
        BpmNotifyEvent event = new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, TENANT_A, USER_A, bizRef);
        asTenant(TENANT_A, USER_A, () -> callbackRecorder.record(event));
        for (int round = 1; round <= 6; round++) {
            jdbc.update("update sw_openapi_callback_task set next_retry_time = ? where biz_ref = ? and app_id = ?",
                    LocalDateTime.now().minusMinutes(1), bizRef, CALLBACK_APP_DOWN);
            callbackRecoveryJob.recoverDue();
        }
        String status = text("select status from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_DOWN);
        assertThat(status).isEqualTo("RETRY_EXHAUSTED");
        assertThat(text("select attempts from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_DOWN)).isEqualTo("5");
        assertThat(text("select last_error from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_DOWN)).isNotBlank();
        System.out.println("[P4-EV] g2.openapi-retry-exhausted bizRef=" + bizRef
                + " attempts=5 status=RETRY_EXHAUSTED lastErrorPresent=true");
    }

    @Test
    @Order(8)
    @DisplayName("OpenAPI 回调：投递中被中断的 SENDING 任务必须被租约回收，不得永久停留")
    void openApiStuckSendingIsReclaimed() {
        String bizRef = "p4-pi-callback-stuck";
        BpmNotifyEvent event = new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, TENANT_A, USER_A, bizRef);
        asTenant(TENANT_A, USER_A, () -> callbackRecorder.record(event));
        // 模拟进程在"已认领回调任务"之后崩溃
        jdbc.update("update sw_openapi_callback_task set status = 'SENDING', attempts = 1,"
                        + " next_retry_time = ?, update_time = ? where biz_ref = ? and app_id = ?",
                LocalDateTime.now().minusMinutes(30), LocalDateTime.now().minusMinutes(30), bizRef, CALLBACK_APP_OK);
        callbackRecoveryJob.recoverDue();
        String status = text("select status from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_OK);
        assertThat(status)
                .as("崩溃在投递中的回调任务不得永久停留在 SENDING（恢复调度必须能发现它）")
                .isNotEqualTo("SENDING");
        System.out.println("[P4-EV] g2.openapi-stale-sending bizRef=" + bizRef + " statusAfterRecovery=" + status
                + " attempts=" + text("select attempts from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                bizRef, CALLBACK_APP_OK));
    }

    // ==================== 场景搭建 ====================

    private void seedCallbackApp(String appId, String callbackUrl) {
        String digest;
        try {
            digest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(("p4-secret-" + appId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("insert into sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version, app_id,"
                        + " app_name, secret_hash, scopes, status, act_as_user_id, callback_url, callback_secret_hash)"
                        + " values (?, now(), now(), 0, ?, 0, ?, ?, ?, 'workflow:callback', 'ENABLED', ?, ?, ?)",
                Math.abs(appId.hashCode()) + 9450L, TENANT_A, appId, "P4 " + appId, digest, USER_A, callbackUrl,
                digest);
    }
}
