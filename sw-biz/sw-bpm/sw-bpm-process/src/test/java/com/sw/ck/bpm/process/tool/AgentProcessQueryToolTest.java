package com.sw.ck.bpm.process.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Agent 内部工具「查询近期流程记录」聚焦测试：租户内倒序返回、失败显式报错、
 * 无身份拒绝。
 */
@ExtendWith(MockitoExtension.class)
class AgentProcessQueryToolTest {

    @Mock
    private ObjectProvider<BpmInstanceService> instanceServiceProvider;
    @Mock
    private BpmInstanceService instanceService;

    private AgentProcessQueryTool tool;

    @BeforeEach
    void setUp() {
        when(instanceServiceProvider.getIfAvailable()).thenReturn(instanceService);
        tool = new AgentProcessQueryTool(instanceServiceProvider);
        LoginUser loginUser = new LoginUser();
        loginUser.setTenantId(1L);
        loginUser.setUserId(9L);
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private BpmInstance instance(String id, String defKey, String status) {
        BpmInstance record = new BpmInstance();
        record.setProcessInstanceId(id);
        record.setProcessDefKey(defKey);
        record.setStatus(status);
        record.setInitiatorId(9L);
        record.setBusinessKey("agent-tool-" + id);
        record.setCreateTime(LocalDateTime.now().minusHours(1));
        return record;
    }

    @Test
    void testQuery_ReturnsRecentRecords() {
        when(instanceService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(
                        instance("pi-1", "campus_activity", "RUNNING"),
                        instance("pi-2", "campus_activity", "APPROVED")));

        String output = tool.execute("{\"windowMinutes\":1440,\"limit\":10}");

        JSONObject result = JSON.parseObject(output);
        assertTrue(result.getBooleanValue("success"));
        assertEquals(2, result.getIntValue("count"));
        JSONArray records = result.getJSONArray("records");
        assertEquals("pi-1", records.getJSONObject(0).getString("processInstanceId"));
        assertEquals("campus_activity", records.getJSONObject(0).getString("processDefKey"));
    }

    @Test
    void testQueryFailure_ExplicitErrorNotSilentEmpty() {
        when(instanceService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        String output = tool.execute("{}");

        JSONObject result = JSON.parseObject(output);
        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("error").contains("查询失败"));
    }

    @Test
    void testNoIdentity_Rejected() {
        LoginUserHolder.clear();
        String output = tool.execute("{}");
        JSONObject result = JSON.parseObject(output);
        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("error").contains("身份缺失"));
    }
}
