package com.sw.ck.bpm.api.participant;

/**
 * 会签负向结算端口（I3 §4.5）。
 * <p>
 * 会签判定为负向时由引擎调用；实现负责终局动作（关停实例 + 记录 + 通知），
 * 多实例下必须幂等：已终局的实例重复回调不产生第二次效果。
 * </p>
 */
public interface ConsensusSettlementPort {

    /**
     * 负向终局结算。
     *
     * @param tenantId          租户
     * @param processInstanceId 实例
     * @param nodeKey           会签节点
     * @param reason            负向原因（mode + 票数摘要）
     */
    void onNegativeSettlement(String tenantId, String processInstanceId,
                              String nodeKey, String reason);
}
