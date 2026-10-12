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
 * 翻译器以 {@code flowable:class} 类委托挂载（start 事件）：令牌到达时经
 * {@link SubflowWaitPort} 回调编排域（sw-bpm-process）核对引用批次——引用批次已全部结算
 * 则立即唤醒，否则保持挂起等待结算侧信号。
 * </p>
 * <p>
 * Flowable 以反射实例化类委托（无 Spring 注入），故 Spring 单例在构造时登记到静态桥接位，
 * 反射实例在 {@link #notify(DelegateExecution)} 中转发到桥接单例；未装配（engine 独立测试）时
 * 无操作，不改变翻译与流程行为。
 * </p>
 */
@Component("subflowWaitListener")
public class SubflowWaitListener implements ExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SubflowWaitListener.class);

    /** 静态桥接位：Spring 单例构造时登记，Flowable 反射实例经此转发。 */
    private static volatile SubflowWaitListener bridge;

    private final ObjectProvider<SubflowWaitPort> waitPort;

    /** Flowable 反射实例化用（无依赖）。 */
    public SubflowWaitListener() {
        this.waitPort = null;
    }

    /** P64 阶段Ⅲ（A08 实链修复）：多构造时 Spring 缺省选无参构造，桥接单例需显式指定注入构造。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SubflowWaitListener(ObjectProvider<SubflowWaitPort> waitPort) {
        this.waitPort = waitPort;
        bridge = this;
    }

    @Override
    public void notify(DelegateExecution execution) {
        SubflowWaitListener target = bridge != null ? bridge : this;
        target.dispatch(execution);
    }

    private void dispatch(DelegateExecution execution) {
        SubflowWaitPort port = waitPort == null ? null : waitPort.getIfAvailable();
        if (port == null) {
            log.info("SubflowWaitPort 未装配，等待节点到达回调跳过: activityId={}",
                    execution.getCurrentActivityId());
            return;
        }
        Long tenantId = parseTenant(execution.getTenantId());
        String processInstanceId = execution.getProcessInstanceId();
        String activityId = execution.getCurrentActivityId();
        port.onWaitNodeArrival(tenantId, processInstanceId, activityId)
                .ifPresentOrElse(
                        outcome -> {
                            log.info("等待节点到达处置: activityId={}, outcome={}", activityId, outcome);
                            if (outcome == com.sw.ck.bpm.api.result.MutationOutcome.ALREADY_APPLIED) {
                                // P64 阶段Ⅲ（A08 实链修复）：start 监听先于执行流进入可信号等待态，
                                // 到达即"无批次应立即唤醒"时首查 signal 可能空吸收（执行流尚未可触发）。
                                // 事务提交后再次核对唤醒：幂等（有批次则继续保持挂起，无批次则推进一次）。
                                if (org.springframework.transaction.support.TransactionSynchronizationManager
                                        .isSynchronizationActive()) {
                                    org.springframework.transaction.support.TransactionSynchronizationManager
                                            .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                                                @Override
                                                public void afterCommit() {
                                                    port.onWaitNodeArrival(tenantId, processInstanceId, activityId)
                                                            .ifPresent(res -> log.info(
                                                                    "等待节点提交后重核唤醒: activityId={}, outcome={}",
                                                                    activityId, res));
                                                }
                                            });
                                }
                            }
                        },
                        () -> log.info("等待节点到达上下文缺失（父实例/租户不可核对）: activityId={}",
                                activityId));
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
