package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.event.BpmDeviceCommandEvent;
import com.sw.ck.iot.api.IotDeviceFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * 审批设备命令意图的事务内登记器（Phase 4 可靠业务事件 —— 设备命令 MUST_DELIVER）。
 *
 * <p>与 {@link BpmNotifyIntentRecorder} 同构：在 {@link BpmDeviceCommandEvent} 发布处同步执行，
 * 把命令以<b>调用方给出的稳定幂等键</b>写入 {@code sw_iot_device_command}（仅入队，无外部 I/O），
 * 因此命令随审批事务一起提交或回滚，提交后即使进程退出命令仍在待发送队列中，
 * 由既有发送路径与补偿调度投递。</p>
 *
 * <p>幂等键来自业务稳定身份 {@code APPROVAL:{流程实例}:{设备}:{命令标识}}：提交后加速、
 * 恢复调度与人工重试都命中同一键，不产生重复设备命令。</p>
 *
 * <h3>失败语义（Phase 5 显式化）</h3>
 * <p>契约返回 {@code Optional<Long>}：{@code empty} 表示目标/适用条件不存在（不适用）。
 * 本登记器是审批事务内唯一的意图持久化点，无法持久化意图就意味着审批会带着一个永远不会
 * 下发的设备动作提交——因此 {@code empty} 与门面缺装配一律 <b>fail closed</b>：抛出异常，
 * 让审批事务整体回滚，不允许审批单边成功。设备不存在等真实错误仍由实现侧抛出。</p>
 */
@Component
public class BpmDeviceCommandIntentRecorder {

    private static final Logger log = LoggerFactory.getLogger(BpmDeviceCommandIntentRecorder.class);

    private final ObjectProvider<IotDeviceFacade> iotDeviceFacade;

    public BpmDeviceCommandIntentRecorder(ObjectProvider<IotDeviceFacade> iotDeviceFacade) {
        this.iotDeviceFacade = iotDeviceFacade;
    }

    /** 稳定幂等键（提交后加速/补偿重试共用；不含随机成分）。 */
    public static String idempotentKey(BpmDeviceCommandEvent event) {
        return "APPROVAL:" + event.getProcessInstanceId() + ":" + event.getDeviceName()
                + ":" + event.getCommandKey();
    }

    @EventListener
    public void onBpmDeviceCommand(BpmDeviceCommandEvent event) {
        IotDeviceFacade facade = iotDeviceFacade.getIfAvailable();
        if (facade == null) {
            // 部署缺口：无法持久化意图。fail closed，审批不得单边成功。
            throw new IllegalStateException("IoT 设备门面未装配，审批设备命令意图无法持久化:"
                    + " processInstanceId=" + event.getProcessInstanceId()
                    + ", deviceName=" + event.getDeviceName()
                    + ", commandKey=" + event.getCommandKey());
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("设备命令意图登记发生在无事务上下文（命令将独立提交，审批回滚不回滚该命令）:"
                            + " processInstanceId={}, deviceName={}",
                    event.getProcessInstanceId(), event.getDeviceName());
        }
        String key = idempotentKey(event);
        Optional<Long> commandId = facade.dispatchCommandIdempotent(event.getProductId(), event.getDeviceName(),
                event.getCommandKey(), event.getCommandType(), null, event.getProcessInstanceId(), key);
        if (commandId.isEmpty()) {
            // 不适用即等于命令永远不下发；在审批事务内必须显式失败而不是静默放过。
            throw new IllegalStateException("审批设备命令意图未被受理（设备不适用或无可用下行路径）:"
                    + " idempotentKey=" + key
                    + ", processInstanceId=" + event.getProcessInstanceId()
                    + ", deviceName=" + event.getDeviceName());
        }
        log.debug("审批设备命令意图已入队: commandId={}, idempotentKey={}, inTx={}",
                commandId.orElseThrow(), key, TransactionSynchronizationManager.isActualTransactionActive());
    }
}
