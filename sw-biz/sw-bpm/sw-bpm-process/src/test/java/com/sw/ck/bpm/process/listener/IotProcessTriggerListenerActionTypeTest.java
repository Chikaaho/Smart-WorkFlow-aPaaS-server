package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.ProcessThemeService;
import com.sw.ck.common.tenant.TenantWorkAdmission;
import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.api.IotDeviceQueryFacade;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import com.sw.ck.iot.event.IotProcessTriggerEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IoT 触发监听器动作路由（0.2.4）：AGENT 事件由 Agent 桥承接，
 * 流程监听器必须忽略，不得误发起流程。
 */
@ExtendWith(MockitoExtension.class)
class IotProcessTriggerListenerActionTypeTest {

    @Mock
    private ObjectProvider<BpmRuntimeFacade> runtimeFacadeProvider;
    @Mock
    private ObjectProvider<BpmProcessDefService> processDefServiceProvider;
    @Mock
    private ObjectProvider<IotProcessTriggerFacade> triggerFacadeProvider;
    @Mock
    private ObjectProvider<IotDeviceFacade> deviceFacadeProvider;
    @Mock
    private ObjectProvider<IotDeviceQueryFacade> deviceQueryFacadeProvider;
    @Mock
    private ObjectProvider<BpmInstanceService> instanceServiceProvider;
    @Mock
    private ObjectProvider<ProcessThemeService> themeServiceProvider;
    @Mock
    private ObjectProvider<BpmTaskFacade> taskFacadeProvider;
    @Mock
    private ObjectProvider<PlatformTransactionManager>
            transactionManagerProvider;
    @Mock
    private TenantWorkAdmission tenantWorkAdmission;

    @Test
    void testAgentEventIgnoredByProcessListener() {
        IotProcessTriggerListener listener = new IotProcessTriggerListener(runtimeFacadeProvider,
                processDefServiceProvider, triggerFacadeProvider, instanceServiceProvider,
                themeServiceProvider, taskFacadeProvider, deviceFacadeProvider,
                deviceQueryFacadeProvider, transactionManagerProvider, tenantWorkAdmission);
        IotProcessTriggerEvent event = new IotProcessTriggerEvent();
        event.setTenantId(1L);
        event.setActionType("AGENT");
        event.setAgentGraphKey("agent_gate_judge");
        event.setIdempotentKey("r1-v1-1-agent-x");

        listener.onIotTrigger(event);

        verifyNoInteractions(runtimeFacadeProvider, processDefServiceProvider);
        verify(tenantWorkAdmission, never()).claim(any(), any());
    }
}
