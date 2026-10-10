package com.sw.ck.bpm.api.orchestration;

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
     * 等待节点令牌到达。
     *
     * @param tenantId          租户 ID
     * @param processInstanceId 父流程实例 ID
     * @param activityId        等待节点 activity ID（BPMN 元素 ID）
     */
    void onWaitNodeArrival(Long tenantId, String processInstanceId, String activityId);
}
