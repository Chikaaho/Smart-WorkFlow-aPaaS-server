package com.sw.ck.bpm.process.port;

import com.sw.ck.bpm.api.orchestration.SubflowWaitPort;
import com.sw.ck.bpm.process.service.ChildOrchestrationService;
import org.springframework.stereotype.Component;

/**
 * P64 阶段Ⅱ（A05）等待节点到达端口实现：委托编排域核对引用批次并决定立即唤醒或挂起。
 */
@Component
public class SubflowWaitPortImpl implements SubflowWaitPort {

    private final ChildOrchestrationService childOrchestrationService;

    public SubflowWaitPortImpl(ChildOrchestrationService childOrchestrationService) {
        this.childOrchestrationService = childOrchestrationService;
    }

    @Override
    public void onWaitNodeArrival(Long tenantId, String processInstanceId, String activityId) {
        childOrchestrationService.onWaitNodeArrival(tenantId, processInstanceId, activityId);
    }
}
