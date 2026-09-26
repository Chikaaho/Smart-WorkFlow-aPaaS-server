package com.sw.ck.bpm.api.participant;

import com.sw.ck.bpm.api.result.MutationOutcome;

import java.util.Map;
import java.util.Optional;

/** 节点运行记录的业务持久化防腐接缝，避免 engine 依赖 process 的实体实现。 */
public interface NodeActionAuditPort {

    /**
     * 记录抄送（副本）投递事实。
     *
     * @return present = {@link MutationOutcome#APPLIED} 审计记录已写入 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 同一事实已存在（幂等重复未产生第二次写入）；
     *         当前实现恒 APPLIED
     */
    Optional<MutationOutcome> recordCopy(String processInstanceId, String nodeKey, String taskId,
                                         String recipientId, String status, String reason, Long tenantId);

    /**
     * 记录分支流转事实。
     *
     * @return present = {@link MutationOutcome#APPLIED} 审计记录已写入 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 同一事实已存在；当前实现恒 APPLIED
     */
    Optional<MutationOutcome> recordBranch(String processInstanceId, String nodeKey, String branchId,
                                           String conditionVersion, Map<String, Object> inputSummary,
                                           Long tenantId);
}
