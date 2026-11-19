package com.sw.ck.bpm.process.port;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.bpm.api.orchestration.SubflowWaitPort;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.process.service.ChildOrchestrationService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * P64 阶段Ⅱ（A05）等待节点到达端口实现：委托编排域核对引用批次并决定立即唤醒或挂起。
 */
@Component
public class SubflowWaitPortImpl implements SubflowWaitPort {

    @Autowired
    private ChildOrchestrationService childOrchestrationService;

    @Override
    public Optional<MutationOutcome> onWaitNodeArrival(Long tenantId, String processInstanceId,
                                                       String activityId) {
        return childOrchestrationService.onWaitNodeArrival(tenantId, processInstanceId, activityId);
    }
}
