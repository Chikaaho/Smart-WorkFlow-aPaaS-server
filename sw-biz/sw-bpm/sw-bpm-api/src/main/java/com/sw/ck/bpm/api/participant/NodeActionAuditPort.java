package com.sw.ck.bpm.api.participant;

import java.util.Map;

/** 节点运行记录的业务持久化防腐接缝，避免 engine 依赖 process 的实体实现。 */
public interface NodeActionAuditPort {

    void recordCopy(String processInstanceId, String nodeKey, String taskId,
                   String recipientId, String status, String reason, Long tenantId);

    void recordBranch(String processInstanceId, String nodeKey, String branchId,
                      String conditionVersion, Map<String, Object> inputSummary, Long tenantId);
}
