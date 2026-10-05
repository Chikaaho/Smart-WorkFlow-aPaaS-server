package com.sw.ck.bpm.api.participant;

import com.sw.ck.bpm.api.result.MutationOutcome;

import java.util.List;
import java.util.Optional;

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
     * @return present = 冻结后的有效分支（负责人去重合并后的稳定顺序，供多实例逐分支建任务；
     *         候选全部无效时为空列表，无效原因已落 CANCELED 行）；缺少租户上下文抛参数异常
     */
    Optional<List<FrozenBranch>> freeze(String tenantId, String processInstanceId, String nodeKey,
                                        String sourceType, String sourceDesc, String mode,
                                        List<BranchCandidate> candidates);

    /**
     * 记录分支动作/取消原因（幂等：同分支同任务重复回调只保留首条）。
     *
     * @param action 动作或状态：START / APPROVE / DISAPPROVE / CANCEL 等
     * @return present = {@link MutationOutcome#APPLIED} 本次已写入 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 终态分支不被覆写（幂等保护）；
     *         empty = 该分支尚未冻结（防御路径：不凭空造分支）；缺少租户上下文抛参数异常
     */
    Optional<MutationOutcome> recordAction(String tenantId, String processInstanceId, String nodeKey,
                                           String leaderId, String taskId, String action, String reason);

    /**
     * 实例负向/终止时关闭未完成分支（幂等）：PENDING 分支置 CANCELED 并记录原因，
     * 已有终态动作的分支不改写。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次关闭了未完成分支 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 已无未完成分支（幂等）；
     *         缺少租户上下文抛参数异常
     */
    Optional<MutationOutcome> closeRemaining(String tenantId, String processInstanceId, String nodeKey,
                                             String reason);

    /** 来源部门候选：无效对象以 skipReason 表达（DEPT_INVALID / LEADER_MISSING）。 */
    record BranchCandidate(String deptId, String leaderId, String skipReason) {
    }

    /** 冻结后的有效分支：branchIndex 为进入节点的稳定顺序。 */
    record FrozenBranch(int branchIndex, String leaderId, String deptIds) {
    }

    /**
     * v2 轮次冻结（P63 语义版本 2）：按对象身份生成分支（USER=人、DEPT=部门→负责人），
     * 保留全部来源位置（表格行 id / 字段引用），同负责人不同部门保持独立分支。
     * 轮次以多实例根 executionId 标识：同 executionId 恢复/重试复用既有快照；
     * 新 executionId（退回后合法重入）开启新轮次并重新解析，旧轮次行保留不改写，
     * 其中未完成分支标记 CANCELED（原因 SUPERSEDED_BY_ROUND，旧任务不可串办）。
     *
     * @param candidates v2 候选（每对象一条；无效对象 assigneeId 为空并带 skipReason）
     * @return present = 本轮有效分支（供多实例逐分支建任务；候选全部无效时为空列表）
     */
    default Optional<List<FrozenBranchV2>> freezeRound(String tenantId, String processInstanceId,
                                                       String nodeKey, String executionId,
                                                       String sourceType, String sourceDesc,
                                                       String mode, String objectType,
                                                       List<BranchCandidateV2> candidates) {
        throw new UnsupportedOperationException("freezeRound requires P63 dynamic branch port");
    }

    /** v2 来源对象候选：无效对象以 skipReason 表达（OBJECT_INVALID / LEADER_MISSING）。 */
    record BranchCandidateV2(String objectId, String assigneeId, String skipReason,
                             String sourceRefsJson) {
    }

    /** v2 冻结后的有效分支：assigneeId 为该分支办理人（部门分支=负责人，人员分支=本人）。 */
    record FrozenBranchV2(int branchIndex, String assigneeId, String objectType,
                          String objectId, String sourceRefsJson) {
    }
}
