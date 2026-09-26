package com.sw.ck.bpm.api.participant;

import java.util.Optional;

/**
 * 会签动作计数端口（I3 §4.5）。
 * <p>
 * 以数据库唯一约束替代单 JVM synchronized：同一任务同一人多次插入只产生一次计数，
 * 多实例部署下重复或并发动作只能形成一次合法结算依据。
 * </p>
 * <p>
 * 本端口原以 {@code -1} 哨兵表达"端口不可用回退"，现统一改由
 * {@code Optional.empty()} 表达；合法票数（含 0）一律以 present 保留。
 * </p>
 */
public interface ConsensusVotePort {

    /**
     * 记录一票。
     *
     * @return present = true 本次首次记录 / false 已存在同 (tenant, task, actor) 的票（幂等重复，
     *         不是失败）；empty = 投票服务当前不可用，无法给出记录判定，调用方不得据此认为重复
     */
    Optional<Boolean> record(String tenantId, String processInstanceId, String nodeKey,
                             String taskId, String actorId, String outcome);

    /**
     * 统计通过/否决票数。
     *
     * @return present = 票数（0 是合法零票，必须以 present 保留）；empty = 端口不可用，
     *         调用方按既有回退口径处理（原 -1 哨兵已移除）
     */
    Optional<Long> count(String tenantId, String processInstanceId, String nodeKey, String outcome);

    /**
     * 进入节点时冻结的参与人分母（去重人数）。
     * <p>
     * 权威来源 = 参与人快照（节点进入时冻结，方向 §4.5）。
     * 多实例过程变量（nrOfInstances）在首个子任务创建监听时可能尚未完整，
     * 禁止作为分母权威。
     * </p>
     *
     * @return present = 冻结分母；empty = 无冻结快照（端口不可用或快照缺失），
     *         调用方回退变量口径（原 -1 哨兵已移除）
     */
    default Optional<Long> total(String tenantId, String processInstanceId, String nodeKey) {
        return Optional.empty();
    }
}
