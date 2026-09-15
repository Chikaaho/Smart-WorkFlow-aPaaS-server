package com.sw.ck.bpm.api.participant;

/**
 * 会签动作计数端口（I3 §4.5）。
 * <p>
 * 以数据库唯一约束替代单 JVM synchronized：同一任务同一人多次插入只产生一次计数，
 * 多实例部署下重复或并发动作只能形成一次合法结算依据。
 * </p>
 */
public interface ConsensusVotePort {

    /**
     * 记录一票。已存在同 (tenant, task, actor) → false（幂等）。
     */
    boolean record(String tenantId, String processInstanceId, String nodeKey,
                   String taskId, String actorId, String outcome);

    /**
     * 统计通过/否决票数：[-1 表示端口不可用回退]。
     */
    long count(String tenantId, String processInstanceId, String nodeKey, String outcome);

    /**
     * 进入节点时冻结的参与人分母（去重人数）。
     * <p>
     * 权威来源 = 参与人快照（节点进入时冻结，方向 §4.5）。
     * 多实例过程变量（nrOfInstances）在首个子任务创建监听时可能尚未完整，
     * 禁止作为分母权威；[-1 表示端口不可用，调用方回退变量口径]。
     * </p>
     */
    default long total(String tenantId, String processInstanceId, String nodeKey) {
        return -1;
    }
}
