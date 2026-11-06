package com.sw.ck.notify.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Agent 内部工具「发送通知」聚焦测试：多渠道分开投递并分开记录结果、
 * 未知渠道显式失败、全部失败时 success=false、参数缺失拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AgentNotifySendToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private ObjectProvider<NotifyFacade> notifyFacadeProvider;
    @Mock
    private NotifyFacade notifyFacade;

    private AgentNotifySendTool tool;

    @BeforeEach
    void setUp() {
        when(notifyFacadeProvider.getIfAvailable()).thenReturn(notifyFacade);
        tool = new AgentNotifySendTool(notifyFacadeProvider);
        LoginUser loginUser = new LoginUser();
        loginUser.setTenantId(1L);
        loginUser.setUserId(9L);
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private JsonNode parse(String output) throws Exception {
        return JSON.readTree(output);
    }

    @Test
    void testMultiChannel_ResultsSeparated() throws Exception {
        when(notifyFacade.send(any(NotifySendRequest.class)))
                .thenReturn(Optional.of(NotifySendResult.builder()
                        .channel(NotifyChannel.IN_APP).status("SUCCESS").build()))
                .thenReturn(Optional.of(NotifySendResult.builder()
                        .channel(NotifyChannel.EMAIL).status("FAILED").failureReason("SMTP 未配置").build()));

        String output = tool.execute("{\"channels\":[\"IN_APP\",\"EMAIL\"],\"recipientId\":123,"
                + "\"title\":\"P0 告警\",\"content\":\"南门流量异常\",\"bizType\":\"WF_TODO\",\"bizId\":\"t-1\"}");

        JsonNode result = parse(output);
        assertTrue(result.get("success").asBoolean(), "至少一个渠道成功即整体成功");
        JsonNode results = result.get("results");
        assertEquals(2, results.size());
        assertEquals("IN_APP", results.get(0).get("channel").asText());
        assertEquals("SUCCESS", results.get(0).get("status").asText());
        assertEquals("EMAIL", results.get(1).get("channel").asText());
        assertEquals("FAILED", results.get(1).get("status").asText());
        assertEquals("SMTP 未配置", results.get(1).get("failureReason").asText());

        ArgumentCaptor<NotifySendRequest> requestCaptor =
                ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(notifyFacade, times(2)).send(requestCaptor.capture());
        List<NotifySendRequest> requests = requestCaptor.getAllValues();
        assertEquals(NotifyChannel.IN_APP, requests.get(0).getChannel());
        assertEquals(NotifyChannel.EMAIL, requests.get(1).getChannel());
        assertEquals(Long.valueOf(1L), requests.get(0).getTenantId());
        assertEquals(NotifyBizType.WF_TODO, requests.get(0).getBizType());
    }

    @Test
    void testTextualRecipientId_Accepted() throws Exception {
        when(notifyFacade.send(any(NotifySendRequest.class)))
                .thenReturn(Optional.of(NotifySendResult.builder()
                        .channel(NotifyChannel.IN_APP).status("SUCCESS").build()));

        String output = tool.execute("{\"channels\":[\"IN_APP\"],\"recipientId\":\"92001\","
                + "\"title\":\"t\",\"content\":\"c\"}");

        JsonNode result = parse(output);
        assertTrue(result.get("success").asBoolean(), "数字字符串接收人应可解析");
        ArgumentCaptor<NotifySendRequest> requestCaptor =
                ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(notifyFacade).send(requestCaptor.capture());
        assertEquals(Long.valueOf(92001L), requestCaptor.getValue().getRecipientId());
    }

    @Test
    void testUnknownChannel_ExplicitFailure() throws Exception {
        when(notifyFacade.send(any(NotifySendRequest.class)))
                .thenReturn(Optional.of(NotifySendResult.builder()
                        .channel(NotifyChannel.IN_APP).status("SUCCESS").build()));

        String output = tool.execute("{\"channels\":[\"FAX\",\"IN_APP\"],\"recipientId\":123,"
                + "\"title\":\"t\",\"content\":\"c\"}");

        JsonNode result = parse(output);
        assertTrue(result.get("success").asBoolean());
        assertEquals("FAILED", result.get("results").get(0).get("status").asText());
        assertEquals("未知渠道", result.get("results").get(0).get("failureReason").asText());
    }

    @Test
    void testMissingParams_Rejected() throws Exception {
        String output = tool.execute("{\"channels\":[\"IN_APP\"]}");
        JsonNode result = parse(output);
        assertFalse(result.get("success").asBoolean());
        assertTrue(result.get("error").asText().contains("recipientId"));

        String output2 = tool.execute("{\"recipientId\":123,\"title\":\"t\",\"content\":\"c\"}");
        JsonNode result2 = parse(output2);
        assertFalse(result2.get("success").asBoolean());
        assertTrue(result2.get("error").asText().contains("channels"));
    }

    @Test
    void testNoIdentity_Rejected() throws Exception {
        LoginUserHolder.clear();
        String output = tool.execute("{\"channels\":[\"IN_APP\"],\"recipientId\":123,"
                + "\"title\":\"t\",\"content\":\"c\"}");
        JsonNode result = parse(output);
        assertFalse(result.get("success").asBoolean());
        assertTrue(result.get("error").asText().contains("身份缺失"));
    }
}
