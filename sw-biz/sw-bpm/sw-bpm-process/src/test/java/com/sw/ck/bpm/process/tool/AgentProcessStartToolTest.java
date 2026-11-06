package com.sw.ck.bpm.process.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.ProcessThemeService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Agent 内部工具「发起流程」聚焦测试：成功发起并落实例、未发布拒绝、
 * 非法输入拒绝、发起异常以结果返回不中断。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentProcessStartToolTest {

    @Mock
    private ObjectProvider<BpmRuntimeFacade> runtimeFacadeProvider;
    @Mock
    private ObjectProvider<BpmProcessDefService> processDefServiceProvider;
    @Mock
    private ObjectProvider<BpmInstanceService> instanceServiceProvider;
    @Mock
    private ObjectProvider<ProcessThemeService> themeServiceProvider;
    @Mock
    private ObjectProvider<BpmTaskFacade> taskFacadeProvider;
    @Mock
    private ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    @Mock
    private BpmRuntimeFacade runtimeFacade;
    @Mock
    private BpmProcessDefService processDefService;
    @Mock
    private BpmInstanceService instanceService;
    @Mock
    private ProcessThemeService themeService;
    @Mock
    private BpmTaskFacade taskFacade;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private AgentProcessStartTool tool;

    @BeforeEach
    void setUp() {
        when(runtimeFacadeProvider.getIfAvailable()).thenReturn(runtimeFacade);
        when(processDefServiceProvider.getIfAvailable()).thenReturn(processDefService);
        when(instanceServiceProvider.getIfAvailable()).thenReturn(instanceService);
        when(themeServiceProvider.getIfAvailable()).thenReturn(themeService);
        when(taskFacadeProvider.getIfAvailable()).thenReturn(taskFacade);
        when(transactionManagerProvider.getIfAvailable()).thenReturn(transactionManager);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        tool = new AgentProcessStartTool(runtimeFacadeProvider, processDefServiceProvider,
                instanceServiceProvider, themeServiceProvider, taskFacadeProvider,
                transactionManagerProvider);
        LoginUser loginUser = new LoginUser();
        loginUser.setTenantId(1L);
        loginUser.setUserId(9L);
        LoginUserHolder.set(loginUser);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private BpmProcessDef publishedDef() {
        BpmProcessDef def = new BpmProcessDef();
        def.setProcessKey("p0_ticket");
        def.setStatus("PUBLISHED");
        def.setProcessDefinitionId("def-1");
        def.setFormKey("form_ticket");
        return def;
    }

    @Test
    void testStartSuccess_RecordsInstanceWithOperator() {
        when(processDefService.findByProcessKey("p0_ticket")).thenReturn(publishedDef());
        when(runtimeFacade.startProcess(eq("p0_ticket"), any(), any(), eq("1")))
                .thenReturn(Optional.of("pi-001"));
        when(taskFacade.isProcessActive("pi-001")).thenReturn(Optional.of(true));

        String output = tool.execute("{\"templateKey\":\"p0_ticket\","
                + "\"formData\":{\"level\":\"P0\",\"source\":\"gate-1\"}}");

        JSONObject result = JSON.parseObject(output);
        assertTrue(result.getBooleanValue("success"));
        assertEquals("pi-001", result.getString("processInstanceId"));
        verify(instanceService).save(argThat((BpmInstance instance) ->
                "pi-001".equals(instance.getProcessInstanceId())
                        && "p0_ticket".equals(instance.getProcessDefKey())
                        && instance.getBusinessKey() != null
                        && instance.getBusinessKey().length() == 36
                        && !instance.getBusinessKey().startsWith("agent-tool-")
                        && Long.valueOf(9L).equals(instance.getInitiatorId())));
    }

    @Test
    void testUnpublishedTemplate_FailsWithoutStart() {
        when(processDefService.findByProcessKey("p0_ticket")).thenReturn(null);

        String output = tool.execute("{\"templateKey\":\"p0_ticket\"}");

        JSONObject result = JSON.parseObject(output);
        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("error").contains("未发布"));
        verify(runtimeFacade, never()).startProcess(any(), any(), any(), any());
    }

    @Test
    void testInvalidJson_FailsGracefully() {
        String output = tool.execute("not-json");
        assertFalse(JSON.parseObject(output).getBooleanValue("success"));
        verify(runtimeFacade, never()).startProcess(any(), any(), any(), any());
    }

    @Test
    void testStartFailure_ReturnsFailureResultNotThrow() {
        when(processDefService.findByProcessKey("p0_ticket")).thenReturn(publishedDef());
        when(runtimeFacade.startProcess(eq("p0_ticket"), any(), any(), eq("1")))
                .thenReturn(Optional.empty());

        String output = tool.execute("{\"templateKey\":\"p0_ticket\"}");

        JSONObject result = JSON.parseObject(output);
        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("error").contains("发起失败"));
    }

    @Test
    void businessKeyAlwaysFitsInstanceColumn() {
        // sw_bpm_instance.business_key = VARCHAR(36)：缺省 UUID、短幂等键原样、长键确定性收敛
        String uuid = AgentProcessStartTool.businessKeyOf(null);
        assertEquals(36, uuid.length());
        assertEquals("drill-2026-q4", AgentProcessStartTool.businessKeyOf("drill-2026-q4"));

        String longKey = "iot-fail-2118599004295127042-IN_APP-some-very-long-idempotency-key";
        String derived = AgentProcessStartTool.businessKeyOf(longKey);
        assertEquals(36, derived.length());
        assertTrue(derived.startsWith("at-"));
        assertEquals(derived, AgentProcessStartTool.businessKeyOf(longKey));
    }
}
