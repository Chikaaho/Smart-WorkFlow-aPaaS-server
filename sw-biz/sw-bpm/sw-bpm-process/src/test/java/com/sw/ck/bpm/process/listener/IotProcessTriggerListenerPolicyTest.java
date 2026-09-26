package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.api.IotDeviceQueryFacade;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IotProcessTriggerListenerPolicyTest {

    @Test
    void failurePoliciesHaveDistinctTerminalBehavior() {
        assertPolicy("BLOCK", "A6 设备命令失败: 设计时固定设备不存在: deviceId=99999", null);
        assertPolicy("CONTINUE", null, "process-CONTINUE");
        assertPolicy("MANUAL", null, null);
    }

    private void assertPolicy(String policy, String expectedError, String expectedProcessId) {
        BpmRuntimeFacade runtime = mock(BpmRuntimeFacade.class);
        BpmProcessDefService processDefs = mock(BpmProcessDefService.class);
        IotProcessTriggerFacade triggers = mock(IotProcessTriggerFacade.class);
        BpmInstanceService instances = mock(BpmInstanceService.class);
        BpmTaskFacade tasks = mock(BpmTaskFacade.class);
        IotDeviceFacade devices = mock(IotDeviceFacade.class);
        IotDeviceQueryFacade deviceQuery = mock(IotDeviceQueryFacade.class);

        ObjectProvider<BpmRuntimeFacade> runtimeProvider = provider(runtime);
        ObjectProvider<BpmProcessDefService> processProvider = provider(processDefs);
        ObjectProvider<IotProcessTriggerFacade> triggerProvider = provider(triggers);
        ObjectProvider<BpmInstanceService> instanceProvider = provider(null);
        ObjectProvider<BpmTaskFacade> taskProvider = provider(tasks);
        ObjectProvider<IotDeviceFacade> deviceProvider = provider(devices);
        ObjectProvider<IotDeviceQueryFacade> deviceQueryProvider = provider(deviceQuery);

        BpmProcessDef def = new BpmProcessDef();
        def.setStatus("PUBLISHED");
        def.setProcessDefinitionId("flowable-policy-test");
        def.setIotAccessEnabled(true);
        def.setIotDeviceActionJson("{\"enabled\":true,\"deviceSource\":\"FIXED\","
                + "\"deviceId\":99999,\"commandKey\":\"reset\","
                + "\"failurePolicy\":\"" + policy + "\"}");
        when(processDefs.findByProcessKey("p21-policy-test")).thenReturn(def);
        when(runtime.startProcess(eq("p21-policy-test"), eq("policy-" + policy), anyMap(), eq("0")))
                .thenReturn(java.util.Optional.of("process-" + policy));
        // 契约返回 Optional<String>：设备不存在必须用 empty 表达（旧契约的 null 返回已废除）
        when(deviceQuery.getDeviceKeyById(99999L)).thenReturn(java.util.Optional.empty());

        IotProcessTriggerEvent event = new IotProcessTriggerEvent();
        event.setTenantId(0L);
        event.setProcessTemplateKey("p21-policy-test");
        event.setIdempotentKey("policy-" + policy);
        event.setTriggerId(999L);
        event.setDeviceId(2097131606828916738L);

        org.springframework.transaction.PlatformTransactionManager txManager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(txManager.getTransaction(any(org.springframework.transaction.TransactionDefinition.class)))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        ObjectProvider<org.springframework.transaction.PlatformTransactionManager> txProvider = provider(txManager);
        new IotProcessTriggerListener(runtimeProvider, processProvider, triggerProvider,
                instanceProvider, taskProvider, deviceProvider, deviceQueryProvider, txProvider)
                .onIotTrigger(event);

        if (expectedProcessId != null || expectedError != null) {
            verify(triggers).markTriggerResult(eq("policy-" + policy),
                    eq(expectedProcessId), eq(expectedError));
        } else {
            verify(triggers, never()).markTriggerResult(any(), any(), any());
        }
        System.out.printf("[H3a] policy=%s expectedProcess=%s expectedError=%s callbackVerified=%s%n",
                policy, expectedProcessId, expectedError, expectedProcessId != null || expectedError != null);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
