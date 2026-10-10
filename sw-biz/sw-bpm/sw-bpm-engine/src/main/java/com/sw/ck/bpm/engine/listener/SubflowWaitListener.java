package com.sw.ck.bpm.engine.listener;

import com.sw.ck.bpm.api.orchestration.SubflowWaitPort;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * SUBFLOW_WAIT 等待节点执行监听器（P64 阶段Ⅱ A05）。
 * <p>
 * 翻译器以 delegation expression {@code ${subflowWaitListener}} 挂载（start 事件）；
 * 令牌到达时经 {@link SubflowWaitPort} 回调编排域（sw-bpm-process）核对引用批次：
 * 引用批次已全部结算则立即唤醒，否则保持挂起等待结算侧信号。
 * 端口未装配（engine 独立测试）时无操作，不改变翻译与流程行为；
 * 回调异常向上传播（发布前配置校验已拦截主要非法形态，运行期异常按引擎事务语义处理）。
 * </p>
 */
@Component("subflowWaitListener")
public class SubflowWaitListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SubflowWaitListener.class);

    private final ObjectProvider<SubflowWaitPort> waitPort;

    public SubflowWaitListener(ObjectProvider<SubflowWaitPort> waitPort) {
        this.waitPort = waitPort;
    }

    @Override
    public void notify(DelegateExecution execution) {
        SubflowWaitPort port = waitPort.getIfAvailable();
        if (port == null) {
            log.debug("SubflowWaitPort 未装配，等待节点到达回调跳过: activityId={}",
                    execution.getCurrentActivityId());
            return;
        }
        port.onWaitNodeArrival(parseTenant(execution.getTenantId()),
                execution.getProcessInstanceId(), execution.getCurrentActivityId());
    }

    private Long parseTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(tenantId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
