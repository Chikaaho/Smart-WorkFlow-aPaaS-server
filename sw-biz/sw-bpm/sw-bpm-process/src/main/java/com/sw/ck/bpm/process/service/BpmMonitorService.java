package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmInstanceIntervention;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流程运营监控/干预/基础分析（I4 §3.3）。
 */
public interface BpmMonitorService {

    PageResult<InstanceMonitorView> monitorInstances(PageParam pageParam, MonitorQuery query);

    /** 实例级干预：SUSPEND / RESUME / TERMINATE / TRANSFER。逐项授权 + 审计落库。 */
    BpmInstanceIntervention intervene(String processInstanceId, String action,
                                      String reason, Long toAssignee, java.util.List<String> taskIds);

    List<BpmInstanceIntervention> interventions(String processInstanceId);

    /** 动态并行分支冻结快照回读（I4 §3.1 运营勾稽）。 */
    List<com.sw.ck.bpm.process.entity.DynamicBranchSnapshot> branches(String processInstanceId);

    Map<String, Object> analyticsSummary(String processDefKey,
                                         LocalDateTime timeFrom, LocalDateTime timeTo);

    /** 实例办理时限原始行（超时可查）。 */
    List<Map<String, Object>> deadlines(String processInstanceId);

    /** 分页监控行：实例 + 当前节点 + 挂起态。 */
    record InstanceMonitorView(BpmInstance instance, List<String> activeNodeIds,
                               boolean suspended) {
    }

    /** 七类条件（§3.3）：定义/实例/发起人/状态/节点/办理人/时间范围。 */
    record MonitorQuery(String processDefKey, String processInstanceId, Long initiatorId,
                        String status, String nodeKey, Long assignee,
                        LocalDateTime timeFrom, LocalDateTime timeTo) {
    }
}
