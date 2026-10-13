package com.sw.ck.bpm.api.orchestration;

import com.sw.ck.bpm.api.result.MutationOutcome;

import java.util.Optional;

/**
 * P64 阶段Ⅱ（A05 主子流程等待）子流程等待节点到达端口。
 * <p>
 * SUBFLOW_WAIT 节点（ReceiveTask）令牌到达时由 engine 侧执行监听器回调；
 * 实现位于 sw-bpm-process（批次结算权威在业务编排域）：核对父实例引用的子流程批次
 * 是否全部到达终态，已全部结算则立即唤醒，否则挂起等待结算侧信号。
 * 未接线（engine 独立测试）时监听器按无操作处理，不改变翻译行为。
 * </p>
 */
public interface SubflowWaitPort {

    /**
     * 等待节点令牌到达（核对引用批次并决定立即唤醒或保持挂起）。
     *
     * @param tenantId          租户 ID
     * @param processInstanceId 父流程实例 ID
     * @param activityId        等待节点 activity ID（BPMN 元素 ID）
     * @return present = 处置结果（{@link MutationOutcome#APPLIED} 本次已唤醒等待节点；
     *         {@link MutationOutcome#ALREADY_APPLIED} 合法无操作：存在未终态批次保持挂起、
     *         图无等待引用或唤醒目标不在运行期）；
     *         empty = 父实例或租户上下文缺失，无法核对（不改动任何状态）
     */
    Optional<MutationOutcome> onWaitNodeArrival(Long tenantId, String processInstanceId, String activityId);
}
