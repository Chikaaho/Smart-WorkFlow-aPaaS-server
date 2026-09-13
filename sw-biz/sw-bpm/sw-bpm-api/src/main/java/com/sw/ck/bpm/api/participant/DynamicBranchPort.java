package com.sw.ck.bpm.api.participant;

import java.util.List;

/**
 * 动态并行分支冻结端口（I4 §3.1）。
 * <p>
 * 进入节点时冻结来源对象、解析结果与汇聚策略：冻结后表单或组织变化不改写已冻结分支。
 * 重复部门与同一负责人按可解释规则去重（部门 ID 升序合并到首条分支）；
 * 无效对象（失效部门 / 负责人缺失 / 跨租户）以 CANCELED 行记录原因，不静默跳过。
 * </p>
 */
public interface DynamicBranchPort {

    /**
     * 冻结分支。幂等：同 (tenant, instance, node) 已冻结时直接返回既有快照，不重算。
     *
     * @param candidates 候选分支（每个来源部门一条；无效对象 leaderId 为空并带 skipReason）
     * @return 冻结后的有效分支（负责人去重合并后的稳定顺序），供多实例逐分支建任务
     */
    List<FrozenBranch> freeze(String tenantId, String processInstanceId, String nodeKey,
                              String sourceType, String sourceDesc, String mode,
                              List<BranchCandidate> candidates);

    /**
     * 记录分支动作/取消原因（幂等：同分支同任务重复回调只保留首条）。
     *
     * @param action 动作或状态：START / APPROVE / DISAPPROVE / CANCEL 等
     */
    void recordAction(String tenantId, String processInstanceId, String nodeKey,
                      String leaderId, String taskId, String action, String reason);

    /**
     * 实例负向/终止时关闭未完成分支（幂等）：PENDING 分支置 CANCELED 并记录原因，
     * 已有终态动作的分支不改写。
     */
    void closeRemaining(String tenantId, String processInstanceId, String nodeKey, String reason);

    /** 来源部门候选：无效对象以 skipReason 表达（DEPT_INVALID / LEADER_MISSING）。 */
    record BranchCandidate(String deptId, String leaderId, String skipReason) {
    }

    /** 冻结后的有效分支：branchIndex 为进入节点的稳定顺序。 */
    record FrozenBranch(int branchIndex, String leaderId, String deptIds) {
    }
}
