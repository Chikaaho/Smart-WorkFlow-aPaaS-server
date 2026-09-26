package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.event.BpmDeviceCommandEvent;
import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * 审批通过后下发设备命令（审批结果驱动设备）。
 * <p>
 * 监听 {@link BpmDeviceCommandEvent}（AFTER_COMMIT + 异步），经 {@link IotDeviceFacade}
 * 走统一命令路径，approvalBizId = 流程实例 ID，命令执行结果可在
 * {@code /iot/devices/{productId}/{deviceName}/commands} 回查。
 * </p>
 *
 * <h3>职责边界（Phase 5 边界抽取后）</h3>
 * <p>本监听器<b>只</b>通过 IoT 契约门面交互，不再触碰 {@code sw_iot_device_command} 的
 * entity/mapper——那属于 IoT 实现模块的内部状态。意图已在审批事务内由
 * {@link BpmDeviceCommandIntentRecorder} 以稳定幂等键持久化，因此本监听器的调用
 * 通常命中幂等键并复用既有命令；命令的发送、失败记录、有限重试、租约回收与可审计终态
 * 全部由 IoT 实现模块内的既有发送路径与补偿调度负责，审批侧不重复承担。</p>
 *
 * <h3>容错</h3>
 * <p>IoT 实现未装配（ObjectProvider 为空）或设备不适用（{@code empty}）时记录告警后返回；
 * 这是审批已提交之后的补偿路径，其失败不影响已完成审批，也不得回滚审批。</p>
 */
@Component
public class BpmDeviceCommandListener {

    private static final Logger log = LoggerFactory.getLogger(BpmDeviceCommandListener.class);

    private final ObjectProvider<IotDeviceFacade> iotDeviceFacadeProvider;

    public BpmDeviceCommandListener(ObjectProvider<IotDeviceFacade> iotDeviceFacadeProvider) {
        this.iotDeviceFacadeProvider = iotDeviceFacadeProvider;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessApproved(BpmDeviceCommandEvent event) {
        IotDeviceFacade facade = iotDeviceFacadeProvider.getIfAvailable();
        if (facade == null) {
            log.warn("IoT 设备门面未装配，跳过设备命令下发: processInstanceId={}, productId={}, deviceName={}",
                    event.getProcessInstanceId(), event.getProductId(), event.getDeviceName());
            return;
        }
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(event.getActorUserId());
        loginUser.setTenantId(event.getTenantId());
        LoginUserHolder.set(loginUser);
        try {
            Optional<Long> commandId = facade.dispatchCommandIdempotent(
                    event.getProductId(),
                    event.getDeviceName(),
                    event.getCommandKey(),
                    event.getCommandType(),
                    null,
                    event.getProcessInstanceId(),
                    BpmDeviceCommandIntentRecorder.idempotentKey(event));
            if (commandId.isEmpty()) {
                // 不适用：审批已完成，此处只记录事实；设备命令状态由 IoT 侧自身终态表达。
                log.warn("审批联动设备命令未受理（设备不适用或无可用下行路径）: processInstanceId={},"
                                + " productId={}, deviceName={}, commandKey={}",
                        event.getProcessInstanceId(), event.getProductId(), event.getDeviceName(),
                        event.getCommandKey());
                return;
            }
            log.info("审批联动设备命令已受理: commandId={}, processInstanceId={}, productId={}, deviceName={}, commandKey={}",
                    commandId.orElseThrow(), event.getProcessInstanceId(), event.getProductId(),
                    event.getDeviceName(), event.getCommandKey());
        } catch (Exception e) {
            // 审批事务已提交不可回滚；此处不得再写 IoT 侧表，发送失败/重试由 IoT 补偿调度承担。
            log.error("审批联动设备命令下发失败（不影响已完成审批）: processInstanceId={}, productId={},"
                            + " deviceName={}, commandKey={}",
                    event.getProcessInstanceId(), event.getProductId(), event.getDeviceName(),
                    event.getCommandKey(), e);
        } finally {
            LoginUserHolder.clear();
        }
    }
}
