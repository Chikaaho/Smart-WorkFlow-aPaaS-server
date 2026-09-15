package com.sw.ck.openapi.biz.service;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.entity.OpenApiCallbackLog;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiCallbackLogMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I4 G5b 出站回调投递服务：签名重试路径、失败三次落账、恢复重发一次成功、
 * 已成功事件重发去重零新增。回调对端为本进程内真实 HTTP socket。
 */
@DisplayName("I4 G5b 回调投递：失败可查 + 恢复重发 + 去重零新增")
@ExtendWith(MockitoExtension.class)
class OpenApiCallbackDeliveryServiceTest {

    @Mock
    private OpenApiAppMapper appMapper;

    @Mock
    private OpenApiCallbackLogMapper logMapper;

    private OpenApiCallbackDeliveryService service;
    private OpenApiApp app;
    private HttpServer receiver;

    @BeforeEach
    void setUp() {
        service = new OpenApiCallbackDeliveryService(appMapper, logMapper);
        app = new OpenApiApp();
        app.setAppId("i4-test-app");
        app.setTenantId(0L);
        app.setStatus("ENABLED");
        app.setSecretHash("abc");
        app.setCallbackUrl("http://127.0.0.1:0/i4/callback");
    }

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.stop(0);
            receiver = null;
        }
    }

    private BpmNotifyEvent approvedEvent() {
        return new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, null, 0L, null, "pi-e");
    }

    @Test
    @DisplayName("对端不可达：同一事件重试三次 FAILED 落账，流程事件不回滚")
    void dispatch_shouldRetryThreeTimesAndRecordFailures() {
        app.setCallbackUrl("http://127.0.0.1:1/i4/callback"); // 保留端口，连接必然拒绝

        service.dispatch(app, approvedEvent());

        ArgumentCaptor<OpenApiCallbackLog> captor = ArgumentCaptor.forClass(OpenApiCallbackLog.class);
        verify(logMapper, times(3)).insert(captor.capture());
        List<OpenApiCallbackLog> rows = captor.getAllValues();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo("FAILED");
            assertThat(row.getAppId()).isEqualTo("i4-test-app");
            assertThat(row.getEvent()).isEqualTo("PROCESS_APPROVED");
            assertThat(row.getBizRef()).isEqualTo("pi-e");
        });
        assertThat(rows).extracting(OpenApiCallbackLog::getAttempt).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("恢复重发：对端就绪后一次 SUCCESS；已成功事件再发去重零新增")
    void resend_shouldDeliverOnceThenDedup() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/i4/callback", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        receiver.start();
        int port = receiver.getAddress().getPort();
        app.setCallbackUrl("http://127.0.0.1:" + port + "/i4/callback");
        when(appMapper.selectOne(any())).thenReturn(app);
        // countSuccess 调用序列：resend 前置检查 0 → dispatch 内部去重检查 0 → resend 后置确认 1
        when(logMapper.selectCount(any())).thenReturn(0L, 0L, 1L);

        Map<String, Object> first = service.resend("i4-test-app", BpmNotifyTrigger.PROCESS_APPROVED, "pi-e");

        assertThat(first).containsEntry("deduped", false).containsEntry("delivered", true);
        ArgumentCaptor<OpenApiCallbackLog> captor = ArgumentCaptor.forClass(OpenApiCallbackLog.class);
        verify(logMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");

        // 已成功：重发去重零新增（不再 insert，不再发起 HTTP）
        when(logMapper.selectCount(any())).thenReturn(1L);
        Map<String, Object> second = service.resend("i4-test-app", BpmNotifyTrigger.PROCESS_APPROVED, "pi-e");

        assertThat(second).containsEntry("deduped", true).containsEntry("delivered", false);
        verify(logMapper, times(1)).insert(any(OpenApiCallbackLog.class));
    }

    @Test
    @DisplayName("投递记录查询：只经 mapper 条件查询（appId 边界在查询条件内承载）")
    void records_shouldScopeByAppId() {
        when(logMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.records("i4-test-app", "pi-e", "PROCESS_APPROVED", "FAILED")).isEmpty();

        verify(logMapper).selectList(any());
    }
}
